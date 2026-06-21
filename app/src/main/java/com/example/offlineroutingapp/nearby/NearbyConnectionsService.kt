package com.example.offlineroutingapp.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.offlineroutingapp.MainActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NearbyConnectionsService
 *
 * Replaces WifiDirectService entirely.
 *
 * Responsibilities:
 *   – Advertise this device so others can find it
 *   – Discover other advertising devices
 *   – Manage Nearby Connections sessions (connect / disconnect / reconnect)
 *   – Send and receive all payload types:
 *       TEXT, IMAGE, IMAGE_CHUNK, VOICE, DOCUMENT,
 *       PROFILE_INFO, LOCATION_UPDATE, ROUTED_TEXT,
 *       DELIVERY_RECEIPT, SEEN_RECEIPT
 *   – Relay / flood-route ROUTED_TEXT packets for multi-hop messaging
 *   – Expose callbacks so Activities/Fragments can react to events
 *
 * Payload framing:
 *   All payloads are byte arrays.  The first bytes contain a pipe-delimited
 *   header so the receiver knows what type of data follows:
 *
 *     TEXT|<sendTimeMillis>|<message text>
 *     IMAGE|<base64 image bytes>
 *     IMAGE_START|<totalSize>
 *     IMAGE_CHUNK|<base64 chunk>
 *     IMAGE_END
 *     VOICE|<durationMillis>|<base64 audio bytes>
 *     DOCUMENT|<filename>|<mimeType>|<base64 bytes>
 *     PROFILE_INFO|<json: {nodeId,displayName,photoBase64}>
 *     LOCATION_UPDATE|<json: {nodeId,lat,lng,displayName,photoPath}>
 *     ROUTED_TEXT|<json: {msgId,srcId,dstId,ttl,text}>
 *     DELIVERY_RECEIPT|<messageId>
 *     SEEN_RECEIPT|<messageId>
 */
class NearbyConnectionsService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): NearbyConnectionsService = this@NearbyConnectionsService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    // ── Nearby Connections client ─────────────────────────────────────────────
    private lateinit var connectionsClient: ConnectionsClient

    // ── My identity ───────────────────────────────────────────────────────────
    private val myNodeId: String by lazy { getOrCreateNodeId() }
    private val logicalGoManager: LogicalGoManager by lazy {
        LogicalGoManager(this, myNodeId)
    }
    fun getNodeId(): String = myNodeId

    // ── Connected endpoints ───────────────────────────────────────────────────
    /** endpointId → NearbyEndpoint */
    private val endpoints = ConcurrentHashMap<String, NearbyEndpoint>()

    /** nodeId → endpointId  (reverse lookup after profile exchange) */
    private val nodeToEndpoint = ConcurrentHashMap<String, String>()

    // ── Relay dedup ───────────────────────────────────────────────────────────
    private val seenRoutedMessages = ConcurrentHashMap<String, Boolean>()
    private val seenOfflinePackets = ConcurrentHashMap<String, Boolean>()

    // ── Store & Forward (pending routed messages) ─────────────────────────────
    private data class PendingRouted(
        val msgId: String, val srcId: String, val dstId: String,
        val ttl: Int, val text: String
    )
    private val pendingByDst = ConcurrentHashMap<String, MutableList<PendingRouted>>()

    // ── Chunked image reassembly ──────────────────────────────────────────────
    /** endpointId → accumulation buffer */
    private val imageBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // ── State ─────────────────────────────────────────────────────────────────
    var currentChatId: String? = null
    val isConnected: Boolean get() = endpoints.any { it.value.isConnected }
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Chunked image build ───────────────────────────────────────────────────
    private val CHUNK_SIZE = 48 * 1024   // 48 KB per chunk

    // =========================================================================
    // Callbacks exposed to Activities
    // =========================================================================

    /** text, isImage, imageData, fromNodeId, isRouted */
    var onMessageReceivedWithFrom: ((String, Boolean, String?, String?, Boolean) -> Unit)? = null

    /** text, isImage, imageData */
    var onMessageReceived: ((String, Boolean, String?) -> Unit)? = null

    /** connected: Boolean */
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null

    /** nodeId, displayName, photoBase64 */
    var onProfileReceived: ((String, String, String) -> Unit)? = null

    /** nodeId, lat, lng, displayName, photoPath */
    var onLocationUpdateReceived: ((String, Double, Double, String, String?) -> Unit)? = null

    /** messageId, delivered */
    var onDeliveryStatusChanged: ((String, Boolean) -> Unit)? = null

    /** messageId, seen */
    var onSeenStatusChanged: ((String, Boolean) -> Unit)? = null

    /** list of discovered endpointIds not yet connected */
    var onEndpointDiscovered: ((String, String) -> Unit)? = null   // endpointId, endpointName

    /** endpointId lost */
    var onEndpointLost: ((String) -> Unit)? = null

    var isChatActivityVisible = false
    var visibleChatId: String? = null

    var onLogicalGoChanged: ((String) -> Unit)? = null
    var onGoRequestReceived: ((String) -> Unit)? = null

    /** Offline data-offloading packets such as FILE_OFFER / PUBLIC_REQUEST. */
    var onOfflinePacketReceived: ((String, String?) -> Unit)? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Nearby Chat Running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        serviceScope.cancel()
    }

    // =========================================================================
    // Advertising  (this device becomes visible to others)
    // =========================================================================

    /**
     * Start advertising so nearby devices can discover and connect to us.
     * Call this from MainActivity once the user profile is ready.
     *
     * [localUserName] is shown to the discovering peer in their device list.
     */
    fun startAdvertising(localUserName: String) {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)   // many-to-many mesh
            .build()

        connectionsClient.startAdvertising(
            localUserName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started as '$localUserName'")
            updateNotification("Advertising — waiting for peers")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed: ${e.message}")
        }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        Log.d(TAG, "Advertising stopped")
    }

    // =========================================================================
    // Discovery  (find other advertising devices)
    // =========================================================================

    /**
     * Start scanning for nearby advertising devices.
     * Results arrive via [onEndpointDiscovered].
     * Call this from MainActivity's Discover tab.
     */
    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed: ${e.message}")
        }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        Log.d(TAG, "Discovery stopped")
    }

    // =========================================================================
    // Connection  (initiate / accept / reject)
    // =========================================================================

    /**
     * Request a connection to a discovered endpoint.
     * [localUserName] is shown to the remote peer during the handshake.
     */
    fun requestConnection(endpointId: String, localUserName: String) {
        connectionsClient.requestConnection(
            localUserName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "Connection requested to $endpointId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Connection request failed to $endpointId: ${e.message}")
        }
    }

    /** Disconnect from a specific endpoint. */
    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        endpoints.remove(endpointId)
        Log.d(TAG, "Disconnected from $endpointId")
    }

    /** Disconnect from all endpoints and stop advertising/discovering. */
    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        endpoints.clear()
        nodeToEndpoint.clear()
        Log.d(TAG, "All endpoints stopped")
    }

    // =========================================================================
    // Connection lifecycle callback
    // =========================================================================

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated: $endpointId name='${info.endpointName}'")

            // Store endpoint immediately so we have the name
            endpoints[endpointId] = NearbyEndpoint(
                endpointId   = endpointId,
                endpointName = info.endpointName,
                isConnected  = false
            )

            // Auto-accept all connections — the app uses nodeId-level identity
            // after the PROFILE_INFO exchange rather than the Nearby handshake.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener { Log.d(TAG, "Connection accepted: $endpointId") }
                .addOnFailureListener { e -> Log.e(TAG, "Accept failed: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to $endpointId")
                    endpoints[endpointId] = endpoints[endpointId]?.copy(isConnected = true)
                        ?: NearbyEndpoint(endpointId = endpointId, isConnected = true)

                    serviceScope.launch(Dispatchers.Main) {
                        onConnectionStatusChanged?.invoke(true)
                        updateNotification("Connected — ${endpoints.size} peer(s)")
                    }

                    // Flush any pending routed messages to this new endpoint
                    flushPendingMessages(endpointId)

                    // Schedule profile exchange
                    serviceScope.launch {
                        delay(500)
                        requestProfileExchange(endpointId)
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                    endpoints.remove(endpointId)
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "Connection error with $endpointId")
                    endpoints.remove(endpointId)
                    if (endpoints.isEmpty()) {
                        serviceScope.launch(Dispatchers.Main) {
                            onConnectionStatusChanged?.invoke(false)
                        }
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")

            // Remove endpoint and get its nodeId
            val endpoint = endpoints.remove(endpointId)
            val disconnectedNodeId = endpoint?.nodeId

            Log.d(TAG, "Disconnected nodeId = $disconnectedNodeId")
            Log.d(TAG, "Current Logical GO = ${logicalGoManager.getCurrentGo()}")

            // Remove node mapping
            disconnectedNodeId?.let {
                nodeToEndpoint.remove(it)
            }

            // Remove any image buffer related to this endpoint
            imageBuffers.remove(endpointId)

            // Check if the disconnected node was the Logical GO
            val wasLogicalGo =
                disconnectedNodeId != null &&
                        disconnectedNodeId == logicalGoManager.getCurrentGo()

            if (wasLogicalGo) {
                Log.d(TAG, "Logical GO disconnected. Starting new election.")
                startLogicalElection()
            }

            // Fallback for two-device scenario:
            // If this device is now alone, make it the Logical GO
            if (endpoints.isEmpty()) {
                Log.d(TAG, "No connected endpoints. Setting this device as Logical GO fallback.")

                announceLogicalGo(myNodeId)

                serviceScope.launch(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                    onLogicalGoChanged?.invoke(myNodeId)
                    updateNotification("Disconnected — this device is now App GO")
                }
            }
        }
    }

    // =========================================================================
    // Endpoint discovery callback
    // =========================================================================

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: $endpointId name='${info.endpointName}'")
            serviceScope.launch(Dispatchers.Main) {
                onEndpointDiscovered?.invoke(endpointId, info.endpointName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            serviceScope.launch(Dispatchers.Main) {
                onEndpointLost?.invoke(endpointId)
            }
        }
    }

    // =========================================================================
    // Payload callback  (receive)
    // =========================================================================

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    handleBytesPayload(endpointId, bytes)
                }
                Payload.Type.STREAM -> {
                    // Streams are used for very large files via sendLargeFile()
                    // Read on IO thread to avoid blocking
                    serviceScope.launch {
                        try {
                            val stream = payload.asStream()?.asInputStream() ?: return@launch
                            val bytes  = stream.readBytes()
                            handleBytesPayload(endpointId, bytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Stream payload read error: ${e.message}")
                        }
                    }
                }
                else -> Log.w(TAG, "Unhandled payload type: ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used for progress bars on large file transfers
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS ->
                    Log.d(TAG, "Payload transfer complete to/from $endpointId")
                PayloadTransferUpdate.Status.FAILURE ->
                    Log.e(TAG, "Payload transfer FAILED to/from $endpointId")
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    Log.v(TAG, "Transfer progress ${update.bytesTransferred}/${update.totalBytes}")
                PayloadTransferUpdate.Status.CANCELED ->
                    Log.w(TAG, "Transfer cancelled to/from $endpointId")
            }
        }
    }

    // =========================================================================
    // Payload parsing  (the heart of the receive logic)
    // =========================================================================

    private fun handleBytesPayload(fromEndpointId: String, bytes: ByteArray) {
        // The payload starts with a TYPE prefix followed by '|'
        val raw    = String(bytes, Charsets.UTF_8)
        val pipeIdx = raw.indexOf('|')
        if (pipeIdx < 0) {
            Log.w(TAG, "Payload has no type prefix from $fromEndpointId")
            return
        }

        val type    = raw.substring(0, pipeIdx)
        val body    = raw.substring(pipeIdx + 1)
        val fromNodeId = endpoints[fromEndpointId]?.nodeId

        Log.d(TAG, "Payload type=$type from endpoint=$fromEndpointId nodeId=$fromNodeId")

        when (type) {

            // ── TEXT ──────────────────────────────────────────────────────────
            "TEXT" -> {
                // body = "<sendTimeMillis>|<message text>"
                val parts   = body.split("|", limit = 2)
                val sendTime = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val text     = parts.getOrNull(1) ?: body
                val delay    = System.currentTimeMillis() - sendTime
                Log.d(TAG, "TEXT delay=${delay}ms")

                showMessageNotification(text, currentChatId)
                deliverToApp(text, false, null, fromNodeId ?: fromEndpointId, false)
                sendDeliveryReceipt(fromEndpointId, "msg_${System.currentTimeMillis()}")
            }

            // ── IMAGE (small, single payload) ─────────────────────────────────
            "IMAGE" -> {
                // body = base64 image
                showMessageNotification("📷 Image", currentChatId)
                deliverToApp("", true, body, fromNodeId ?: fromEndpointId, false)
                sendDeliveryReceipt(fromEndpointId, "img_${System.currentTimeMillis()}")
            }

            // ── IMAGE chunked protocol ────────────────────────────────────────
            "IMAGE_START" -> {
                imageBuffers[fromEndpointId] = ByteArrayOutputStream()
            }

            "IMAGE_CHUNK" -> {
                // body = base64 chunk
                val buffer = imageBuffers[fromEndpointId] ?: return
                try {
                    val chunkBytes = Base64.decode(body, Base64.NO_WRAP)
                    buffer.write(chunkBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Chunk decode error: ${e.message}")
                }
            }

            "IMAGE_END" -> {
                val buffer = imageBuffers.remove(fromEndpointId) ?: return
                val imageBytes = buffer.toByteArray()
                val base64     = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                showMessageNotification("📷 Image", currentChatId)
                deliverToApp("", true, base64, fromNodeId ?: fromEndpointId, false)
            }

            // ── VOICE ─────────────────────────────────────────────────────────
            "VOICE" -> {
                // body = "<durationMillis>|<base64 audio>"
                val parts    = body.split("|", limit = 2)
                val duration = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val base64   = parts.getOrNull(1) ?: ""
                Log.d(TAG, "VOICE received duration=${duration}ms")

                showMessageNotification("🎤 Voice Message", currentChatId)
                deliverToApp("🎤 Voice Message", false, base64, fromNodeId ?: fromEndpointId, false)
                sendDeliveryReceipt(fromEndpointId, "voice_${System.currentTimeMillis()}")
            }

            // ── DOCUMENT ──────────────────────────────────────────────────────
            "DOCUMENT" -> {
                // body = "<filename>|<mimeType>|<base64 bytes>"
                val parts    = body.split("|", limit = 3)
                val fileName = parts.getOrNull(0) ?: "document"
                val mimeType = parts.getOrNull(1) ?: "*/*"
                val base64   = parts.getOrNull(2) ?: ""

                showMessageNotification("📄 $fileName", currentChatId)
                deliverToApp(fileName, false, base64, fromNodeId ?: fromEndpointId, false)
                sendDeliveryReceipt(fromEndpointId, "doc_${System.currentTimeMillis()}")
            }

            // ── PROFILE_INFO ──────────────────────────────────────────────────
            "PROFILE_INFO" -> {
                // body = JSON: {"nodeId":"...","displayName":"...","photoBase64":"..."}
                try {
                    val json        = JSONObject(body)
                    val nodeId      = json.getString("nodeId")
                    val displayName = json.getString("displayName")
                    val photoBase64 = json.optString("photoBase64", "")

                    // Map endpointId ↔ nodeId
                    endpoints[fromEndpointId] = endpoints[fromEndpointId]?.copy(
                        nodeId      = nodeId,
                        displayName = displayName
                    ) ?: NearbyEndpoint(
                        endpointId  = fromEndpointId,
                        nodeId      = nodeId,
                        displayName = displayName,
                        isConnected = true
                    )
                    nodeToEndpoint[nodeId] = fromEndpointId

                    Log.d(TAG, "Profile received: $nodeId / $displayName")

                    serviceScope.launch(Dispatchers.Main) {
                        onProfileReceived?.invoke(nodeId, displayName, photoBase64)
                    }

                    // Forward profile to other connected peers so they can build
                    // their chat lists / directories
                    forwardProfileToOthers(fromEndpointId, body)

                    // Flush any pending routed messages to this nodeId
                    flushPendingForNode(nodeId)

                } catch (e: Exception) {
                    Log.e(TAG, "PROFILE_INFO parse error: ${e.message}")
                }
            }

            // ── LOCATION_UPDATE ───────────────────────────────────────────────
            "LOCATION_UPDATE" -> {
                try {
                    val json        = JSONObject(body)
                    val nodeId      = json.getString("nodeId")
                    val lat         = json.getDouble("lat")
                    val lng         = json.getDouble("lng")
                    val displayName = json.getString("displayName")
                    val photoPath   = json.optString("photoPath", "").ifBlank { null }
                    val accuracy    = if (json.has("accuracy")) json.optDouble("accuracy").toFloat() else null

                    com.example.offlineroutingapp.location.LocationStore.updatePeer(nodeId, lat, lng, accuracy)

                    serviceScope.launch(Dispatchers.Main) {
                        onLocationUpdateReceived?.invoke(nodeId, lat, lng, displayName, photoPath)
                    }

                    // Forward to other peers (mesh propagation)
                    forwardPayloadToOthers(fromEndpointId, bytes)

                } catch (e: Exception) {
                    Log.e(TAG, "LOCATION_UPDATE parse error: ${e.message}")
                }
            }

            // ── ROUTED_TEXT ───────────────────────────────────────────────────
            "ROUTED_TEXT" -> {
                // body = JSON: {"msgId":"...","srcId":"...","dstId":"...","ttl":8,"text":"..."}
                try {
                    val json  = JSONObject(body)
                    val msgId = json.getString("msgId")
                    val srcId = json.getString("srcId")
                    val dstId = json.getString("dstId")
                    val ttl   = json.getInt("ttl")
                    val text  = json.getString("text")

                    handleRoutedText(fromEndpointId, msgId, srcId, dstId, ttl, text)
                } catch (e: Exception) {
                    Log.e(TAG, "ROUTED_TEXT parse error: ${e.message}")
                }
            }

            // ── DELIVERY_RECEIPT ──────────────────────────────────────────────
            "DELIVERY_RECEIPT" -> {
                serviceScope.launch(Dispatchers.Main) {
                    onDeliveryStatusChanged?.invoke(body, true)
                }
            }

            // ── SEEN_RECEIPT ──────────────────────────────────────────────────
            "SEEN_RECEIPT" -> {
                serviceScope.launch(Dispatchers.Main) {
                    onSeenStatusChanged?.invoke(body, true)
                }
            }

            "OFFLINE_PACKET" -> {
                val packetId = try {
                    JSONObject(body).optString("packetId", "")
                } catch (_: Exception) {
                    ""
                }

                if (packetId.isNotBlank() && seenOfflinePackets.putIfAbsent(packetId, true) != null) {
                    Log.d(TAG, "Drop duplicate OFFLINE_PACKET packetId=$packetId")
                    return
                }

                serviceScope.launch(Dispatchers.Main) {
                    onOfflinePacketReceived?.invoke(body, fromNodeId ?: fromEndpointId)
                }

                // Forward the packet to other peers to support simple decentralized propagation.
                forwardPayloadToOthers(fromEndpointId, bytes)
            }
            "GO_REQUEST" -> {
                try {
                    val json = JSONObject(body)
                    val candidateId = json.getString("candidateId")

                    logicalGoManager.addCandidate(candidateId)

                    // MVP behavior: automatically vote for the candidate
                    sendGoVote(candidateId)

                    serviceScope.launch(Dispatchers.Main) {
                        onGoRequestReceived?.invoke(candidateId)
                    }

                    Log.d(TAG, "GO_REQUEST received from candidate=$candidateId")
                } catch (e: Exception) {
                    Log.e(TAG, "GO_REQUEST parse error: ${e.message}")
                }
            }

            "GO_VOTE" -> {
                try {
                    val json = JSONObject(body)
                    val candidateId = json.getString("candidateId")
                    val voterId = json.getString("voterId")

                    logicalGoManager.addCandidate(candidateId)
                    logicalGoManager.addVote(candidateId, voterId)

                    val activeNodes = getActiveNodeIds()
                    val winner = logicalGoManager.electWinner(activeNodes)

                    if (winner != null) {
                        announceLogicalGo(winner)
                    }

                    Log.d(TAG, "GO_VOTE received: voter=$voterId candidate=$candidateId")
                } catch (e: Exception) {
                    Log.e(TAG, "GO_VOTE parse error: ${e.message}")
                }
            }

            "LOGICAL_GO_ANNOUNCE" -> {
                try {
                    val json = JSONObject(body)
                    val goId = json.getString("goId")

                    logicalGoManager.setLogicalGo(goId)

                    serviceScope.launch(Dispatchers.Main) {
                        onLogicalGoChanged?.invoke(goId)
                    }

                    Log.d(TAG, "Logical GO announced: $goId")
                } catch (e: Exception) {
                    Log.e(TAG, "LOGICAL_GO_ANNOUNCE parse error: ${e.message}")
                }
            }
            else -> Log.w(TAG, "Unknown payload type: $type from $fromEndpointId")
        }
    }

    // =========================================================================
    // Send functions
    // =========================================================================

    /** Send a data-offloading packet to currently connected peers. */
    fun sendOfflinePacket(packetJson: String): Boolean {
        val connectedIds = endpoints.filter { it.value.isConnected }.keys.toList()
        if (connectedIds.isEmpty()) {
            Log.w(TAG, "sendOfflinePacket: no connected endpoints")
            return false
        }

        val packetId = try {
            JSONObject(packetJson).optString("packetId", "")
        } catch (_: Exception) {
            ""
        }
        if (packetId.isNotBlank()) {
            seenOfflinePackets[packetId] = true
        }

        val payload = buildBytesPayload("OFFLINE_PACKET|$packetJson")
        connectionsClient.sendPayload(connectedIds, payload)
            .addOnFailureListener { e ->
                Log.e(TAG, "sendOfflinePacket failed: ${e.message}")
            }

        Log.d(TAG, "OFFLINE_PACKET sent to ${connectedIds.size} peer(s)")
        return true
    }

    /** Send a plain text message to the directly connected peer. */
    fun sendMessage(text: String) {
        val payload = buildBytesPayload("TEXT|${System.currentTimeMillis()}|$text")
        sendToAll(payload)
    }
    fun sendGoRequest() {
        logicalGoManager.clearElection()
        logicalGoManager.addCandidate(myNodeId)

        val json = JSONObject().apply {
            put("candidateId", myNodeId)
        }

        val payload = buildBytesPayload("GO_REQUEST|$json")
        sendToAll(payload)

        // The requester votes for himself
        sendGoVote(myNodeId)

        Log.d(TAG, "GO_REQUEST sent by $myNodeId")
    }

    fun sendGoVote(candidateId: String) {
        logicalGoManager.addVote(candidateId, myNodeId)

        val json = JSONObject().apply {
            put("candidateId", candidateId)
            put("voterId", myNodeId)
        }

        val payload = buildBytesPayload("GO_VOTE|$json")
        sendToAll(payload)

        Log.d(TAG, "GO_VOTE sent: candidate=$candidateId voter=$myNodeId")
    }

    fun announceLogicalGo(goId: String) {
        logicalGoManager.setLogicalGo(goId)

        val json = JSONObject().apply {
            put("goId", goId)
        }

        val payload = buildBytesPayload("LOGICAL_GO_ANNOUNCE|$json")
        sendToAll(payload)

        serviceScope.launch(Dispatchers.Main) {
            onLogicalGoChanged?.invoke(goId)
        }

        Log.d(TAG, "LOGICAL_GO_ANNOUNCE sent: $goId")
    }
    /** Send a small image (< ~300 KB) as a single payload. */
    fun sendImage(imageBytes: ByteArray) {
        val base64  = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val payload = buildBytesPayload("IMAGE|$base64")
        sendToAll(payload)
    }

    /** Send a large image as multiple chunk payloads. */
    fun sendImageChunked(imageBytes: ByteArray) {
        serviceScope.launch {
            val startPayload = buildBytesPayload("IMAGE_START|${imageBytes.size}")
            sendToAllRaw(startPayload)

            var offset = 0
            while (offset < imageBytes.size) {
                val chunkSize  = minOf(CHUNK_SIZE, imageBytes.size - offset)
                val chunk      = imageBytes.copyOfRange(offset, offset + chunkSize)
                val base64     = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val chunkPayload = buildBytesPayload("IMAGE_CHUNK|$base64")
                sendToAllRaw(chunkPayload)
                offset += chunkSize
            }

            val endPayload = buildBytesPayload("IMAGE_END|")
            sendToAllRaw(endPayload)
        }
    }

    /** Send a voice recording. */
    fun sendVoice(voiceBytes: ByteArray, durationMs: Long) {
        val base64  = Base64.encodeToString(voiceBytes, Base64.NO_WRAP)
        val payload = buildBytesPayload("VOICE|$durationMs|$base64")
        sendToAll(payload)
    }

    /** Send a document file. */
    fun sendDocument(documentBytes: ByteArray, fileName: String, mimeType: String) {
        val base64  = Base64.encodeToString(documentBytes, Base64.NO_WRAP)
        val payload = buildBytesPayload("DOCUMENT|$fileName|$mimeType|$base64")
        sendToAll(payload)
    }

    /** Send profile information to all connected peers. */
    fun sendProfileInfo(nodeId: String, displayName: String, photoBase64: String?) {
        val json = JSONObject().apply {
            put("nodeId",      nodeId)
            put("displayName", displayName)
            put("photoBase64", photoBase64 ?: "")
        }
        val payload = buildBytesPayload("PROFILE_INFO|${json}")
        sendToAll(payload)
    }

    /** Broadcast GPS location to all connected peers. */
    fun broadcastLocationUpdate(
        lat: Double, lng: Double,
        displayName: String, photoPath: String?,
        accuracy: Float? = null
    ) {
        com.example.offlineroutingapp.location.LocationStore.updateMyLocation(lat, lng, accuracy)

        val json = JSONObject().apply {
            put("nodeId",      myNodeId)
            put("lat",         lat)
            put("lng",         lng)
            put("displayName", displayName)
            put("photoPath",   photoPath ?: "")
            accuracy?.let { put("accuracy", it.toDouble()) }
            put("timestamp", System.currentTimeMillis())
        }
        val payload = buildBytesPayload("LOCATION_UPDATE|$json")
        sendToAll(payload)
    }

    /** Send a delivery receipt back to the sender endpoint. */
    fun sendDeliveryReceipt(endpointId: String, messageId: String) {
        val payload = buildBytesPayload("DELIVERY_RECEIPT|$messageId")
        sendToOne(endpointId, payload)
    }

    /** Send a seen receipt back to the sender endpoint. */
    fun sendSeenReceipt(endpointId: String, messageId: String) {
        val payload = buildBytesPayload("SEEN_RECEIPT|$messageId")
        sendToOne(endpointId, payload)
    }

    /**
     * Send a routed text message destined for [dstNodeId].
     * If there is a direct connection to the destination, it is sent directly.
     * Otherwise it is flooded to all connected peers for relay.
     */
    fun sendRoutedText(dstNodeId: String, text: String, ttl: Int = 8) {
        val msgId = "r_${myNodeId}_${System.currentTimeMillis()}"
        seenRoutedMessages[msgId] = true

        val json = buildRoutedJson(msgId, myNodeId, dstNodeId, ttl, text)
        val payload = buildBytesPayload("ROUTED_TEXT|$json")

        // Store for delivery if no path available yet
        pendingByDst.compute(dstNodeId) { _, list ->
            val l = list ?: mutableListOf()
            l.add(PendingRouted(msgId, myNodeId, dstNodeId, ttl, text))
            l
        }

        // Try direct first, then flood
        val directEndpointId = nodeToEndpoint[dstNodeId]
        if (directEndpointId != null && endpoints[directEndpointId]?.isConnected == true) {
            sendToOne(directEndpointId, payload)
        } else {
            sendToAll(payload)
        }
    }

    // =========================================================================
    // Routing / relay logic
    // =========================================================================

    private fun handleRoutedText(
        fromEndpointId: String,
        msgId: String, srcId: String, dstId: String, ttl: Int, text: String
    ) {
        // Dedup — drop if already seen
        if (seenRoutedMessages.putIfAbsent(msgId, true) != null) {
            Log.d(TAG, "Drop duplicate routed msgId=$msgId")
            return
        }

        // For me — deliver to app
        if (dstId == myNodeId) {
            showMessageNotification(text, srcId)
            deliverToApp(text, false, null, srcId, true)
            return
        }

        // Not for me — store and forward if TTL allows
        val newTtl = ttl - 1
        if (newTtl <= 0) {
            Log.d(TAG, "Drop TTL=0 routed msgId=$msgId")
            return
        }

        pendingByDst.compute(dstId) { _, list ->
            val l = list ?: mutableListOf()
            l.add(PendingRouted(msgId, srcId, dstId, newTtl, text))
            l
        }

        // Forward to all peers except sender
        val json    = buildRoutedJson(msgId, srcId, dstId, newTtl, text)
        val payload = buildBytesPayload("ROUTED_TEXT|$json")
        for ((epId, ep) in endpoints) {
            if (epId == fromEndpointId || !ep.isConnected) continue
            sendToOne(epId, payload)
        }
    }

    private fun flushPendingMessages(newEndpointId: String) {
        val all = pendingByDst.values.flatten()
        if (all.isEmpty()) return
        serviceScope.launch {
            for (p in all) {
                val json    = buildRoutedJson(p.msgId, p.srcId, p.dstId, p.ttl, p.text)
                val payload = buildBytesPayload("ROUTED_TEXT|$json")
                sendToOne(newEndpointId, payload)
            }
            Log.d(TAG, "Flushed ${all.size} pending messages to $newEndpointId")
        }
    }

    private fun flushPendingForNode(nodeId: String) {
        val list = pendingByDst.remove(nodeId) ?: return
        val endpointId = nodeToEndpoint[nodeId] ?: return
        serviceScope.launch {
            for (p in list) {
                val json    = buildRoutedJson(p.msgId, p.srcId, p.dstId, p.ttl, p.text)
                val payload = buildBytesPayload("ROUTED_TEXT|$json")
                sendToOne(endpointId, payload)
            }
            Log.d(TAG, "Flushed ${list.size} pending messages to nodeId=$nodeId")
        }
    }

    private fun forwardProfileToOthers(fromEndpointId: String, profileJson: String) {
        val payload = buildBytesPayload("PROFILE_INFO|$profileJson")
        for ((epId, ep) in endpoints) {
            if (epId == fromEndpointId || !ep.isConnected) continue
            sendToOne(epId, payload)
        }
    }

    private fun forwardPayloadToOthers(fromEndpointId: String, originalBytes: ByteArray) {
        val payload = Payload.fromBytes(originalBytes)
        for ((epId, ep) in endpoints) {
            if (epId == fromEndpointId || !ep.isConnected) continue
            connectionsClient.sendPayload(epId, payload)
        }
    }

    // =========================================================================
    // Profile exchange helper
    // =========================================================================

    /**
     * Ask the app layer to re-send our profile to the newly connected endpoint.
     * We fire a callback so MainActivity handles the DB lookup (it already has
     * this logic from the WiFi Direct implementation).
     */
    private fun requestProfileExchange(endpointId: String) {
        // Trigger MainActivity's existing profile exchange logic via callback
        serviceScope.launch(Dispatchers.Main) {
            onConnectionStatusChanged?.invoke(true)
        }
    }

    // =========================================================================
    // Low-level send helpers
    // =========================================================================

    private fun buildBytesPayload(content: String): Payload =
        Payload.fromBytes(content.toByteArray(Charsets.UTF_8))

    private fun sendToAll(payload: Payload) {
        val connectedIds = endpoints.filter { it.value.isConnected }.keys.toList()
        if (connectedIds.isEmpty()) {
            Log.w(TAG, "sendToAll: no connected endpoints")
            return
        }
        connectionsClient.sendPayload(connectedIds, payload)
    }

    private fun sendToOne(endpointId: String, payload: Payload) {
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e ->
                Log.e(TAG, "sendToOne failed to $endpointId: ${e.message}")
            }
    }

    private fun sendToAllRaw(payload: Payload) {
        val connectedIds = endpoints.filter { it.value.isConnected }.keys.toList()
        if (connectedIds.isNotEmpty()) {
            connectionsClient.sendPayload(connectedIds, payload)
        }
    }

    // =========================================================================
    // Deliver to app layer
    // =========================================================================

    private fun deliverToApp(
        text: String, isImage: Boolean, imageData: String?,
        fromNodeId: String?, isRouted: Boolean
    ) {
        serviceScope.launch(Dispatchers.Main) {
            if (onMessageReceivedWithFrom != null) {
                onMessageReceivedWithFrom?.invoke(text, isImage, imageData, fromNodeId, isRouted)
            } else {
                onMessageReceived?.invoke(text, isImage, imageData)
            }
        }
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun showMessageNotification(messageText: String, chatId: String?) {
        if (isChatActivityVisible && visibleChatId == chatId) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_CHAT_ID", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("New Message")
            .setContentText(messageText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Chat")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nearby Chat Service",
                    NotificationManager.IMPORTANCE_LOW)
            )
            mgr.createNotificationChannel(
                NotificationChannel(MESSAGE_CHANNEL_ID, "Messages",
                    NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
            )
        }
    }

    // =========================================================================
    // Node ID persistence
    // =========================================================================

    private fun getOrCreateNodeId(): String {
        val prefs   = getSharedPreferences("routing_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("node_id", null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("node_id", newId).apply()
        return newId
    }

    // =========================================================================
    // JSON helpers
    // =========================================================================

    private fun buildRoutedJson(
        msgId: String, srcId: String, dstId: String, ttl: Int, text: String
    ): JSONObject = JSONObject().apply {
        put("msgId", msgId)
        put("srcId", srcId)
        put("dstId", dstId)
        put("ttl",   ttl)
        put("text",  text)
    }

    // =========================================================================
    // Public endpoint queries
    // =========================================================================

    /** Get all currently connected endpoints. */
    fun getConnectedEndpoints(): List<NearbyEndpoint> =
        endpoints.values.filter { it.isConnected }

    /** Get endpoint for a given nodeId, or null if not connected. */
    fun getEndpointForNode(nodeId: String): NearbyEndpoint? {
        val epId = nodeToEndpoint[nodeId] ?: return null
        return endpoints[epId]
    }

    // =========================================================================
    // Constants
    // =========================================================================
    private fun getActiveNodeIds(): List<String> {
        val ids = endpoints.values
            .filter { it.isConnected }
            .mapNotNull { it.nodeId }
            .toMutableList()

        ids.add(myNodeId)

        return ids.distinct()
    }

    private fun startLogicalElection() {
        val activeNodes = getActiveNodeIds()

        if (activeNodes.isEmpty()) {
            return
        }

        logicalGoManager.clearElection()

        // Simple deterministic election:
        // choose the smallest nodeId so all devices can reach the same result
        val newGo = activeNodes.sorted().firstOrNull() ?: return

        announceLogicalGo(newGo)

        Log.d(TAG, "Logical election completed. New GO=$newGo")
    }

    fun isMeLogicalGo(): Boolean {
        return logicalGoManager.isMeLogicalGo()
    }

    fun getCurrentLogicalGo(): String? {
        return logicalGoManager.getCurrentGo()
    }
    companion object {
        private const val TAG               = "NearbyConnService"
        private const val CHANNEL_ID        = "NearbyServiceChannel"
        private const val MESSAGE_CHANNEL_ID = "NearbyMessageChannel"
        private const val NOTIFICATION_ID   = 2
        /**
         * SERVICE_ID uniquely identifies your app to the Nearby Connections API.
         * Must be the same on all devices. Using the package name is the standard approach.
         */
        const val SERVICE_ID = "com.example.offlineroutingapp.nearby"
    }
}
