package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.offlineroutingapp.adapters.ViewPagerAdapter
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.fragments.CloudFragment
import com.example.offlineroutingapp.fragments.DiscoverFragment
import com.example.offlineroutingapp.fragments.PublicRequestsFragment
import com.example.offlineroutingapp.location.LocationStore
import com.example.offlineroutingapp.nearby.NearbyConnectionsService
import com.example.offlineroutingapp.nativebridge.MasaarBridge
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    // ── Nearby service ────────────────────────────────────────────────────────
    private var nearbyService: NearbyConnectionsService? = null
    private var serviceBound = false

    // ── Discovered endpoints (for Discover tab list) ──────────────────────────
    /** endpointId → display name */
    private val discoveredEndpoints = mutableMapOf<String, String>()
    private lateinit var peersAdapter: ArrayAdapter<String>

    // ── Data ──────────────────────────────────────────────────────────────────
    private var currentChatId: String? = null
    private val nodeDirectory = LinkedHashMap<String, Pair<String, String?>>()
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var localDisplayName: String = "Me"

    // Keep a fresh local GPS point while the app is open so chat/map distances
    // are based on the latest high-accuracy location, not only when the map is opened.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val appLocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 4_000L
    ).setMinUpdateIntervalMillis(2_000L).build()

    private val appLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)
            broadcastFreshLocation(loc.latitude, loc.longitude, loc.accuracy)
        }
    }

    // ── Permission codes ──────────────────────────────────────────────────────
    private val REQ_BLUETOOTH   = 3001
    private val REQ_LOCATION    = 3002
    private val REQ_STORAGE     = 3003

    // ── Image picker ──────────────────────────────────────────────────────────
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { sendImage(it) } }

    // ─────────────────────────────────────────────────────────────────────────
    // Service connection
    // ─────────────────────────────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as NearbyConnectionsService.LocalBinder
            nearbyService = b.getService()
            serviceBound  = true

            Log.d(TAG, "NearbyConnectionsService connected. NodeId=${nearbyService?.getNodeId()}")

            // Set my nodeId in LocationStore
            LocationStore.myNodeId = nearbyService?.getNodeId()
            // ── Wire callbacks ─────────────────────────────────────────────
            nearbyService?.onMessageReceivedWithFrom =
                { text, isImage, imageData, fromId, isRouted ->
                    handleReceivedMessage(text, isImage, imageData, fromId, isRouted)
                }

            nearbyService?.onConnectionStatusChanged = { connected ->
                Log.d(TAG, "Connection status changed: $connected")
                if (connected) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                    }
                    // Exchange our profile with the newly connected peer
                    exchangeProfileInfo()

                    // Sync cached public requests with any newly connected peer.
                    // This lets a new user see requests from the network even if
                    // they are not directly connected to the original requester.
                    lifecycleScope.launch {
                        delay(800)
                        syncPublicRequestsWithPeers()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            nearbyService?.onProfileReceived = { nodeId, displayName, photoBase64 ->
                handleReceivedProfile(nodeId, displayName, photoBase64)
            }

            nearbyService?.onEndpointDiscovered = { endpointId, endpointName ->
                runOnUiThread { handleEndpointDiscovered(endpointId, endpointName) }
            }

            nearbyService?.onEndpointLost = { endpointId ->
                runOnUiThread { handleEndpointLost(endpointId) }
            }

            nearbyService?.onOfflinePacketReceived = { packetJson, fromNodeId ->
                runOnUiThread { handleOfflinePacketReceived(packetJson, fromNodeId) }
            }

            // Start advertising immediately so others can find us
            lifecycleScope.launch {
                val user = database.userDao().getUser()
                val name = user?.displayName ?: "Unknown"
                nearbyService?.startAdvertising(name)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nearbyService = null
            serviceBound  = false
            Log.d(TAG, "NearbyConnectionsService disconnected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_USE_ROUTED_MODE = "USE_ROUTED_MODE"
        const val EXTRA_DST_NODE_ID     = "DST_NODE_ID"
        const val EXTRA_CHAT_ID         = "CHAT_ID"
        const val EXTRA_USER_NAME       = "USER_NAME"
        const val EXTRA_USER_PHOTO      = "USER_PHOTO"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_tabbed)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initViews()
        setupViewPager()
        loadLocalDisplayName()
        startAndBindService()
        handleNotificationIntent(intent)
        requestAllPermissions()
        startAppLocationUpdatesIfAllowed()

        // Nearby map FAB
        findViewById<FloatingActionButton>(R.id.fabNearbyMap).setOnClickListener {
            startActivity(Intent(this, NearbyMapActivity::class.java))
        }

        // Test MasaarBridge (C++ routing core — unchanged)
        val testMsg = MasaarBridge.buildMessage("Hello from app!", "user_b1234567")
        Log.d("MasaarTest", "Generated message: $testMsg")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        setupDiscoverFragment()
        startAppLocationUpdatesIfAllowed()
    }

    override fun onPause() {
        super.onPause()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(appLocationCallback)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    private fun initViews() {
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
    }

    private fun setupViewPager() {
        viewPagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = viewPagerAdapter
        viewPager.offscreenPageLimit = 3

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Chats"
                1 -> "Cloud"
                2 -> "Discover"
                3 -> "Profile"
                else -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 2) setupDiscoverFragment()
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Discover fragment wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupDiscoverFragment() {
        val fragment = supportFragmentManager.fragments
            .filterIsInstance<DiscoverFragment>().firstOrNull() ?: return

        val discoverBtn = fragment.getDiscoverButton()
        val peersList   = fragment.getPeersList()

        if (!::peersAdapter.isInitialized) {
            peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
            peersList.adapter = peersAdapter
        }

        // "Discover" button now starts Nearby discovery
        discoverBtn.setOnClickListener {
            startNearbyDiscovery()
        }

        // Tapping a discovered peer requests a connection
        peersList.setOnItemClickListener { _, _, position, _ ->
            val endpointId = discoveredEndpoints.keys.toList().getOrNull(position) ?: return@setOnItemClickListener
            val name       = discoveredEndpoints[endpointId] ?: "Unknown"
            connectToEndpoint(endpointId, name)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Offline public-request packet bridge
    // ─────────────────────────────────────────────────────────────────────────

    fun sendOfflinePacket(packetJson: String): Boolean {
        val sent = nearbyService?.sendOfflinePacket(packetJson) == true
        if (!sent) {
            Toast.makeText(this, "No connected peers to send offline packet", Toast.LENGTH_SHORT).show()
        }
        return sent
    }

    fun getLocalNodeId(): String = nearbyService?.getNodeId() ?: "LOCAL_NODE"

    fun getLocalDisplayName(): String = localDisplayName

    private fun loadLocalDisplayName() {
        lifecycleScope.launch {
            localDisplayName = database.userDao().getUser()?.displayName ?: "Me"
        }
    }

    private fun handleOfflinePacketReceived(packetJson: String, fromNodeId: String?) {
        val fragment = supportFragmentManager.fragments
            .filterIsInstance<PublicRequestsFragment>()
            .firstOrNull()

        val cloudFragment = supportFragmentManager.fragments
            .filterIsInstance<CloudFragment>()
            .firstOrNull()

        if (cloudFragment != null) {
            cloudFragment.handleIncomingOfflinePacket(packetJson, fromNodeId)
        } else {
            fragment?.handleIncomingOfflinePacket(packetJson, fromNodeId)
        }
        Log.d(TAG, "Offline packet received from $fromNodeId: $packetJson")
    }

    private fun syncPublicRequestsWithPeers() {
        val fragment = supportFragmentManager.fragments
            .filterIsInstance<PublicRequestsFragment>()
            .firstOrNull()

        val cloudFragment = supportFragmentManager.fragments
            .filterIsInstance<CloudFragment>()
            .firstOrNull()

        val count = cloudFragment?.syncActivePublicRequestsToPeers()
            ?: fragment?.syncActivePublicRequestsToPeers()
            ?: 0
        Log.d(TAG, "Public request sync triggered after connection. Sent=$count")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nearby discovery
    // ─────────────────────────────────────────────────────────────────────────

    private fun startNearbyDiscovery() {
        if (!hasNearbyPermissions()) {
            requestAllPermissions()
            return
        }
        nearbyService?.startDiscovery()
        Toast.makeText(this, getString(R.string.search_started), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Nearby discovery started")
    }

    private fun handleEndpointDiscovered(endpointId: String, endpointName: String) {
        Log.d(TAG, "Endpoint discovered: $endpointId name='$endpointName'")
        discoveredEndpoints[endpointId] = endpointName

        if (::peersAdapter.isInitialized) {
            peersAdapter.clear()
            peersAdapter.addAll(discoveredEndpoints.values.toList())
        }
    }

    private fun handleEndpointLost(endpointId: String) {
        Log.d(TAG, "Endpoint lost: $endpointId")
        discoveredEndpoints.remove(endpointId)
        if (::peersAdapter.isInitialized) {
            peersAdapter.clear()
            peersAdapter.addAll(discoveredEndpoints.values.toList())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connect to a discovered peer
    // ─────────────────────────────────────────────────────────────────────────

    private fun connectToEndpoint(endpointId: String, endpointName: String) {
        Log.d(TAG, "Requesting connection to $endpointId ($endpointName)")
        Toast.makeText(this, "Connecting to $endpointName…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val user = database.userDao().getUser()
            val myName = user?.displayName ?: "Unknown"
            nearbyService?.requestConnection(endpointId, myName)

            // Create a placeholder chat entry using endpointId until profile arrives
            if (database.chatDao().getChatById(endpointId) == null) {
                database.chatDao().insertChat(
                    ChatEntity(chatId = endpointId, userName = endpointName)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconnect helper (called from ChatsFragment)
    // ─────────────────────────────────────────────────────────────────────────

    fun reconnectToDevice(chatId: String) {
        Log.d(TAG, "Reconnect requested for chatId: $chatId")

        // Check if already connected via nodeId
        val endpoint = nearbyService?.getEndpointForNode(chatId)
        if (endpoint?.isConnected == true) {
            Toast.makeText(this, "Already connected!", Toast.LENGTH_SHORT).show()
            return
        }

        // Switch to Discover tab and start discovery
        Toast.makeText(this, "Searching for device…", Toast.LENGTH_SHORT).show()
        viewPager.currentItem = 1
        lifecycleScope.launch {
            delay(300)
            startNearbyDiscovery()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service start + bind
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, NearbyConnectionsService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "NearbyConnectionsService started and binding…")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Profile exchange
    // ─────────────────────────────────────────────────────────────────────────

    private fun exchangeProfileInfo() {
        lifecycleScope.launch {
            val user = database.userDao().getUser() ?: return@launch
            var photoBase64: String? = null
            if (!user.profilePhotoPath.isNullOrEmpty()) {
                try {
                    val file = File(user.profilePhotoPath)
                    if (file.exists()) {
                        photoBase64 = android.util.Base64.encodeToString(
                            file.readBytes(), android.util.Base64.NO_WRAP
                        )
                    }
                } catch (_: Exception) {}
            }
            val nodeId = nearbyService?.getNodeId() ?: user.userId
            nearbyService?.sendProfileInfo(nodeId, user.displayName, photoBase64)
            Log.d(TAG, "Profile sent: nodeId=$nodeId name=${user.displayName}")
        }
    }

    private fun handleReceivedProfile(nodeId: String, displayName: String, photoBase64: String) {
        lifecycleScope.launch {
            Log.d(TAG, "Profile received: nodeId=$nodeId name=$displayName")

            var photoPath: String? = null
            if (photoBase64.isNotEmpty()) {
                try {
                    val bytes      = android.util.Base64.decode(photoBase64, android.util.Base64.NO_WRAP)
                    val profileDir = File(filesDir, "received_profiles").also { it.mkdirs() }
                    val file       = File(profileDir, "profile_${nodeId}_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(bytes)
                    photoPath = file.absolutePath
                } catch (_: Exception) {}
            }

            nodeDirectory[nodeId] = Pair(displayName, photoPath)

            // Upsert chat entry — migrate from endpointId placeholder if needed
            val existing = database.chatDao().getChatById(nodeId)
            if (existing != null) {
                database.chatDao().updateChat(
                    existing.copy(userName = displayName, userProfilePhoto = photoPath)
                )
            } else {
                // Check if there's a placeholder under an endpointId and migrate it
                val connectedEndpoints = nearbyService?.getConnectedEndpoints() ?: emptyList()
                val matchingEndpoint   = connectedEndpoints.find { it.nodeId == nodeId }
                val oldId              = matchingEndpoint?.endpointId

                if (oldId != null && oldId != nodeId) {
                    try {
                        database.messageDao().migrateChatId(oldId, nodeId)
                        database.chatDao().getChatById(oldId)?.let { database.chatDao().deleteChat(it) }
                        Log.d(TAG, "Migrated chat from endpointId($oldId) → nodeId($nodeId)")
                    } catch (_: Exception) {}
                }

                database.chatDao().insertChat(
                    ChatEntity(
                        chatId           = nodeId,
                        userName         = displayName,
                        userProfilePhoto = photoPath
                    )
                )
            }

            // Navigate to Chats tab
            withContext(Dispatchers.Main) {
                viewPager.currentItem = 0
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Received messages
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleReceivedMessage(
        text: String, isImage: Boolean, imageData: String?,
        fromId: String?, isRouted: Boolean
    ) {
        val effectiveChatId = fromId ?: currentChatId
        if (effectiveChatId.isNullOrBlank()) return

        lifecycleScope.launch {

            // Detect location JSON
            val locationJson = if (!isImage) {
                try { JSONObject(text).takeIf { it.optString("type") == "LOCATION" } }
                catch (_: Exception) { null }
            } else null

            val entity = if (locationJson != null) {
                MessageEntity(
                    chatId        = effectiveChatId,
                    text          = text,
                    isSentByMe    = false,
                    isLocation    = true,
                    locationLat   = locationJson.getDouble("lat"),
                    locationLng   = locationJson.getDouble("lng"),
                    locationLabel = locationJson.optString("label", "Shared Location"),
                    isDelivered   = true
                )
            } else {
                MessageEntity(
                    chatId      = effectiveChatId,
                    text        = text,
                    isSentByMe  = false,
                    isImage     = isImage,
                    imageData   = imageData,
                    isDelivered = true
                )
            }

            val newId = database.messageDao().insertMessage(entity)

            // Offload media
            if (!entity.isLocation) {
                try {
                    com.example.offlineroutingapp.offload.OffloadHelper.maybeOffload(
                        applicationContext, entity.copy(id = newId), effectiveChatId
                    )
                } catch (se: SecurityException) {
                    Log.w(TAG, "Offload skipped: ${se.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Offload failed: ${e.message}", e)
                }
            }

            // Update chat list row
            val last = when {
                locationJson != null -> "📍 Location"
                isImage              -> "📷 Image"
                else                 -> text
            }
            val chat = database.chatDao().getChatById(effectiveChatId)
            if (chat != null) {
                database.chatDao().updateChat(
                    chat.copy(
                        lastMessage     = last,
                        lastMessageTime = System.currentTimeMillis(),
                        unreadCount     = chat.unreadCount + 1
                    )
                )
            } else {
                database.chatDao().insertChat(
                    ChatEntity(
                        chatId           = effectiveChatId,
                        userName         = nodeDirectory[effectiveChatId]?.first ?: "Unknown",
                        userProfilePhoto = nodeDirectory[effectiveChatId]?.second,
                        lastMessage      = last,
                        lastMessageTime  = System.currentTimeMillis(),
                        unreadCount      = 1
                    )
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification intent
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleNotificationIntent(intent: Intent) {
        val chatId = intent.getStringExtra("OPEN_CHAT_ID") ?: return
        lifecycleScope.launch {
            val chat = database.chatDao().getChatById(chatId) ?: return@launch
            startActivity(Intent(this@MainActivity, ChatActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID,          chat.chatId)
                putExtra(EXTRA_USER_NAME,         chat.userName)
                putExtra(EXTRA_USER_PHOTO,        chat.userProfilePhoto)
                putExtra(EXTRA_USE_ROUTED_MODE,   false)
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image send (from image picker)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendImage(imageUri: Uri) {
        if (!hasStoragePermission()) { requestStoragePermission(); return }
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap      = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                val compressed  = compressImage(bitmap)
                val baos        = ByteArrayOutputStream()
                compressed.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val imageBytes  = baos.toByteArray()
                nearbyService?.sendImage(imageBytes)
                currentChatId?.let { chatId ->
                    val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    database.messageDao().insertMessage(
                        MessageEntity(chatId = chatId, text = "", isSentByMe = true, isImage = true, imageData = base64)
                    )
                }
            } catch (e: Exception) { Log.e(TAG, "sendImage error: ${e.message}") }
        }
    }

    private fun compressImage(bitmap: Bitmap): Bitmap {
        val scale = minOf(800f / bitmap.width, 600f / bitmap.height)
        return if (scale < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App-wide high-accuracy location for precise distances
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAppLocationUpdatesIfAllowed() {
        if (!::fusedLocationClient.isInitialized) return
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return

        try {
            fusedLocationClient.requestLocationUpdates(
                appLocationRequest,
                appLocationCallback,
                mainLooper
            )
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc ?: return@addOnSuccessListener
                LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)
                broadcastFreshLocation(loc.latitude, loc.longitude, loc.accuracy)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Location update skipped: ${se.message}")
        }
    }

    private fun broadcastFreshLocation(lat: Double, lng: Double, accuracy: Float) {
        lifecycleScope.launch {
            val user = database.userDao().getUser() ?: return@launch
            nearbyService?.broadcastLocationUpdate(
                lat = lat,
                lng = lng,
                displayName = user.displayName,
                photoPath = user.profilePhotoPath,
                accuracy = accuracy
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Request all permissions required by Nearby Connections at once.
     * Nearby needs Bluetooth + Location + WiFi permissions.
     */
    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()

        // Location (required by Nearby for WiFi Direct discovery)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // WiFi Devices (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLUETOOTH)
        }
    }

    private fun hasNearbyPermissions(): Boolean {
        val locationOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val bluetoothOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }

        return locationOk && bluetoothOk
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BLUETOOTH -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Nearby permissions granted")
                    // Restart advertising now that we have permissions
                    lifecycleScope.launch {
                        val user = database.userDao().getUser()
                        nearbyService?.startAdvertising(user?.displayName ?: "Unknown")
                    }
                    startAppLocationUpdatesIfAllowed()
                } else {
                    Toast.makeText(this, "Bluetooth/Location permission required for discovery", Toast.LENGTH_LONG).show()
                }
            }
            REQ_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
