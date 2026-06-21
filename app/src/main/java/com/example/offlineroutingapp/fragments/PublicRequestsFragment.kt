package com.example.offlineroutingapp.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.MainActivity
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.adapters.PublicRequestAdapter
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.offloading.CachedSharedFile
import com.example.offlineroutingapp.offloading.FileAckPayload
import com.example.offlineroutingapp.offloading.FileChunkPayload
import com.example.offlineroutingapp.offloading.FileOfferPayload
import com.example.offlineroutingapp.offloading.FileRequestPayload
import com.example.offlineroutingapp.offloading.NetworkPacket
import com.example.offlineroutingapp.offloading.OfflinePacketTypes
import com.example.offlineroutingapp.offloading.PendingFileOffer
import com.example.offlineroutingapp.offloading.PublicRequestPayload
import com.example.offlineroutingapp.offloading.SharedFileCacheStore
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PublicRequestsFragment : Fragment() {

    companion object {
        private const val TAG = "PublicRequests"
        private const val DEFAULT_USER_NAME = "Me"
        private const val FILE_CHUNK_SIZE_BYTES = 12 * 1024
        private const val REQUESTS_PREFS = "public_requests_cache"
        private const val REQUESTS_CACHE_KEY = "requests_json"
    }

    private lateinit var requestInput: EditText
    private lateinit var sendRequestBtn: Button
    private lateinit var requestsRecyclerView: RecyclerView
    private lateinit var emptyRequestsText: TextView
    private lateinit var adapter: PublicRequestAdapter

    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private val requests = mutableListOf<PublicRequestUiModel>()
    private val pendingFileOffers = mutableMapOf<String, PendingFileOffer>()
    private val incomingFileTransfers = mutableMapOf<String, IncomingFileTransfer>()
    private val cachedSharedFiles = mutableListOf<CachedSharedFile>()
    private val gson = Gson()
    private var selectedRequestForFile: PublicRequestUiModel? = null
    private var localUserName: String = DEFAULT_USER_NAME

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            selectedRequestForFile = null
            return@registerForActivityResult
        }

        handleSelectedFile(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_public_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestInput = view.findViewById(R.id.requestInput)
        sendRequestBtn = view.findViewById(R.id.sendRequestBtn)
        requestsRecyclerView = view.findViewById(R.id.requestsRecyclerView)
        emptyRequestsText = view.findViewById(R.id.emptyRequestsText)

        loadLocalUserName()
        setupRecyclerView()
        loadCachedSharedFiles()
        loadSavedRequests()
        setupSendButton()
        updateEmptyState()
    }

    private fun loadLocalUserName() {
        lifecycleScope.launch {
            localUserName = database.userDao().getUser()?.displayName ?: DEFAULT_USER_NAME
        }
    }

    private fun setupRecyclerView() {
        adapter = PublicRequestAdapter(
            onHaveItClick = { request ->
                selectedRequestForFile = request
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onDownloadClick = { request ->
                sendFileRequest(request)
            },
            onOpenFileClick = { request ->
                openDownloadedFile(request)
            }
        )

        requestsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        requestsRecyclerView.adapter = adapter
    }

    private fun setupSendButton() {
        sendRequestBtn.setOnClickListener {
            val text = requestInput.text.toString().trim()

            if (text.isEmpty()) {
                requestInput.error = "Write your request first"
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val senderName = database.userDao().getUser()?.displayName ?: getLocalUserName()
                localUserName = senderName

                val now = System.currentTimeMillis()
                val request = PublicRequestUiModel(
                    requestId = UUID.randomUUID().toString(),
                    requesterName = senderName,
                    requestText = text,
                    timeText = formatTime(now),
                    status = "ACTIVE",
                    requesterNodeId = getLocalNodeId(),
                    timestamp = now,
                    ttl = 30 * 60 * 1000L,
                    hopCount = 0,
                    maxHopCount = 3,
                    isMine = true
                )

                requests.add(0, request)
                adapter.submitList(requests.toList())
                saveRequestsToStorage()
                requestInput.text.clear()
                updateEmptyState()

                val sent = sendPublicRequestPacket(request)
                Toast.makeText(
                    requireContext(),
                    if (sent) "Request sent to connected peers" else "Request saved locally, but no connected peers",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        val request = selectedRequestForFile ?: return
        selectedRequestForFile = null

        persistReadPermission(uri)

        val fileName = getFileName(uri)
        val fileSizeBytes = getFileSizeBytes(uri)
        val fileSizeText = formatFileSize(fileSizeBytes)
        val fileHash = calculateSha256FromStoredUri(uri.toString())
        val category = guessCategory(fileName)
        val tags = generateTagsFromFileName(fileName)
        val pendingOffer = createPendingFileOffer(
            request = request,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            fileSizeText = fileSizeText,
            fileUri = uri,
            fileHash = fileHash,
            category = category,
            tags = tags
        )
        pendingFileOffers[pendingOffer.fileId] = pendingOffer
        pendingFileOffers[pendingOffer.requestId] = pendingOffer
        pendingFileOffers[pendingOffer.offerId] = pendingOffer

        registerCachedSharedFile(
            fileId = pendingOffer.fileId,
            fileName = pendingOffer.fileName,
            fileSizeBytes = pendingOffer.fileSizeBytes,
            fileSizeText = pendingOffer.fileSizeText,
            localUri = pendingOffer.localFileUri,
            sourceType = "LOCAL",
            fileHash = pendingOffer.fileHash,
            category = pendingOffer.category,
            tags = pendingOffer.tags
        )

        val updatedRequest = request.copy(
            offeredFileName = fileName,
            offeredFileSizeText = fileSizeText,
            status = "OFFER SENT",
            fileOfferJson = pendingOffer.packetJson,
            offerId = pendingOffer.offerId,
            fileId = pendingOffer.fileId,
            ownerNodeId = getLocalNodeId(),
            ownerName = getLocalUserName(),
            canDownload = false
        )

        val index = requests.indexOfFirst { it.requestId == request.requestId }
        if (index != -1) {
            requests[index] = updatedRequest
            adapter.submitList(requests.toList())
            saveRequestsToStorage()
        }

        Log.d(TAG, "Prepared FILE_OFFER packet: ${pendingOffer.packetJson}")
        val sent = (activity as? MainActivity)?.sendOfflinePacket(pendingOffer.packetJson) == true
        val toastMessage = if (sent) {
            "Offer sent: $fileName ($fileSizeText)"
        } else {
            "Offer ready but no connected peers: $fileName ($fileSizeText)"
        }

        Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_LONG).show()
    }

    fun syncActivePublicRequestsToPeers(): Int {
        var sentCount = 0
        val now = System.currentTimeMillis()

        requests
            .filter { request ->
                val timestamp = request.timestamp ?: return@filter false
                val ttl = request.ttl ?: return@filter false
                val notExpired = now <= timestamp + ttl
                val canStillPropagate = request.hopCount < request.maxHopCount
                val isActive = request.status !in setOf("EXPIRED")
                notExpired && canStillPropagate && isActive
            }
            .forEach { request ->
                if (sendPublicRequestPacket(request)) sentCount++
            }

        Log.d(TAG, "Synced $sentCount active public request(s) to connected peers")
        return sentCount
    }

    fun handleIncomingOfflinePacket(packetJson: String, fromNodeId: String?) {
        try {
            val packet = gson.fromJson(packetJson, NetworkPacket::class.java) ?: return

            if (packet.receiverNodeId != null && packet.receiverNodeId != getLocalNodeId()) {
                Log.d(TAG, "Ignored packet not targeted to this node. type=${packet.type}")
                return
            }

            when (packet.type) {
                OfflinePacketTypes.PUBLIC_REQUEST -> handleIncomingPublicRequest(packet, fromNodeId)
                OfflinePacketTypes.FILE_OFFER -> handleIncomingFileOffer(packet, fromNodeId)
                OfflinePacketTypes.FILE_REQUEST -> handleIncomingFileRequest(packet, fromNodeId)
                OfflinePacketTypes.FILE_CHUNK -> handleIncomingFileChunk(packet, fromNodeId)
                OfflinePacketTypes.FILE_ACK -> handleIncomingFileAck(packet, fromNodeId)
                else -> Log.d(TAG, "Ignored offline packet type=${packet.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse offline packet: ${e.message}")
        }
    }

    private fun sendPublicRequestPacket(request: PublicRequestUiModel): Boolean {
        val payload = PublicRequestPayload(
            requestId = request.requestId,
            requesterNodeId = request.requesterNodeId ?: getLocalNodeId(),
            requesterName = request.requesterName,
            requestText = request.requestText,
            timestamp = request.timestamp ?: System.currentTimeMillis(),
            ttl = request.ttl ?: 30 * 60 * 1000L,
            hopCount = request.hopCount,
            maxHopCount = request.maxHopCount,
            status = request.status
        )

        val packet = NetworkPacket(
            type = OfflinePacketTypes.PUBLIC_REQUEST,
            packetId = request.requestId,
            senderNodeId = getLocalNodeId(),
            receiverNodeId = null,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )

        val packetJson = gson.toJson(packet)
        Log.d(TAG, "Sending PUBLIC_REQUEST packet: $packetJson")
        return (activity as? MainActivity)?.sendOfflinePacket(packetJson) == true
    }

    private fun handleIncomingPublicRequest(packet: NetworkPacket, fromNodeId: String?) {
        val payload = gson.fromJson(packet.payload, PublicRequestPayload::class.java) ?: return

        val isExpired = System.currentTimeMillis() > payload.timestamp + payload.ttl
        if (isExpired) {
            Log.d(TAG, "Ignored expired PUBLIC_REQUEST requestId=${payload.requestId}")
            return
        }

        val alreadyExists = requests.any { it.requestId == payload.requestId }
        if (alreadyExists) {
            Log.d(TAG, "Ignored duplicate PUBLIC_REQUEST requestId=${payload.requestId}")
            return
        }

        val incomingRequest = PublicRequestUiModel(
            requestId = payload.requestId,
            requesterName = payload.requesterName.ifBlank { fromNodeId ?: "Unknown" },
            requestText = payload.requestText,
            timeText = formatTime(payload.timestamp),
            status = payload.status,
            requesterNodeId = payload.requesterNodeId,
            timestamp = payload.timestamp,
            ttl = payload.ttl,
            hopCount = payload.hopCount + 1,
            maxHopCount = payload.maxHopCount,
            isMine = payload.requesterNodeId == getLocalNodeId()
        )

        requests.add(0, incomingRequest)
        adapter.submitList(requests.toList())
        saveRequestsToStorage()
        updateEmptyState()

        offerCachedFileIfAvailable(incomingRequest)

        Toast.makeText(
            requireContext(),
            "New public request from ${incomingRequest.requesterName}",
            Toast.LENGTH_SHORT
        ).show()

        Log.d(TAG, "PUBLIC_REQUEST received from $fromNodeId: $payload")
    }

    private fun handleIncomingFileOffer(packet: NetworkPacket, fromNodeId: String?) {
        val offer = gson.fromJson(packet.payload, FileOfferPayload::class.java) ?: return
        if (offer.ownerNodeId == getLocalNodeId()) return

        val index = requests.indexOfFirst { it.requestId == offer.requestId }
        if (index != -1) {
            val current = requests[index]
            val offerForMe = current.isMine || offer.requesterNodeId == getLocalNodeId()
            requests[index] = current.copy(
                offeredFileName = offer.fileName,
                offeredFileSizeText = offer.fileSizeText,
                status = if (offerForMe) "OFFER RECEIVED" else "OFFER AVAILABLE",
                offerId = offer.offerId,
                fileId = offer.fileId,
                ownerNodeId = offer.ownerNodeId,
                ownerName = offer.ownerName,
                canDownload = offerForMe
            )
            adapter.submitList(requests.toList())
            saveRequestsToStorage()
        }

        Toast.makeText(
            requireContext(),
            "File offer from ${offer.ownerName}: ${offer.fileName}",
            Toast.LENGTH_LONG
        ).show()

        Log.d(TAG, "FILE_OFFER received from $fromNodeId: $offer")
    }

    private fun sendFileRequest(request: PublicRequestUiModel) {
        val offerId = request.offerId
        val fileId = request.fileId
        val ownerNodeId = request.ownerNodeId

        if (offerId.isNullOrBlank() || fileId.isNullOrBlank() || ownerNodeId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No valid file offer to download", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = FileRequestPayload(
            requestId = request.requestId,
            offerId = offerId,
            fileId = fileId,
            requesterNodeId = getLocalNodeId(),
            ownerNodeId = ownerNodeId,
            timestamp = System.currentTimeMillis()
        )

        val packet = NetworkPacket(
            type = OfflinePacketTypes.FILE_REQUEST,
            packetId = UUID.randomUUID().toString(),
            senderNodeId = getLocalNodeId(),
            receiverNodeId = ownerNodeId,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )

        val sent = (activity as? MainActivity)?.sendOfflinePacket(gson.toJson(packet)) == true
        Toast.makeText(
            requireContext(),
            if (sent) "Download request sent" else "Owner is not connected now",
            Toast.LENGTH_SHORT
        ).show()

        updateRequestStatus(request.requestId, "DOWNLOAD REQUESTED")
    }

    private fun handleIncomingFileRequest(packet: NetworkPacket, fromNodeId: String?) {
        val fileRequest = gson.fromJson(packet.payload, FileRequestPayload::class.java) ?: return
        val pendingOffer = pendingFileOffers[fileRequest.fileId]
            ?: pendingFileOffers[fileRequest.offerId]
            ?: pendingFileOffers[fileRequest.requestId]

        if (pendingOffer == null) {
            Log.w(TAG, "No pending file found for request: $fileRequest")
            return
        }

        try {
            val bytes = readBytesFromStoredUri(pendingOffer.localFileUri)
            if (bytes == null) {
                Toast.makeText(requireContext(), "Could not read selected file", Toast.LENGTH_SHORT).show()
                return
            }

            val totalChunks = ((bytes.size + FILE_CHUNK_SIZE_BYTES - 1) / FILE_CHUNK_SIZE_BYTES).coerceAtLeast(1)
            var sentChunks = 0

            for (chunkIndex in 0 until totalChunks) {
                val start = chunkIndex * FILE_CHUNK_SIZE_BYTES
                val end = minOf(start + FILE_CHUNK_SIZE_BYTES, bytes.size)
                val chunkBytes = bytes.copyOfRange(start, end)

                val chunkPayload = FileChunkPayload(
                    requestId = fileRequest.requestId,
                    offerId = fileRequest.offerId,
                    fileId = fileRequest.fileId,
                    fileName = pendingOffer.fileName,
                    fileSizeBytes = bytes.size.toLong(),
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP),
                    timestamp = System.currentTimeMillis(),
                    fileHash = pendingOffer.fileHash,
                    category = pendingOffer.category,
                    tags = pendingOffer.tags
                )

                val responsePacket = NetworkPacket(
                    type = OfflinePacketTypes.FILE_CHUNK,
                    packetId = "${fileRequest.fileId}_chunk_$chunkIndex",
                    senderNodeId = getLocalNodeId(),
                    receiverNodeId = fileRequest.requesterNodeId,
                    payload = gson.toJson(chunkPayload),
                    timestamp = System.currentTimeMillis()
                )

                val sent = (activity as? MainActivity)?.sendOfflinePacket(gson.toJson(responsePacket)) == true
                if (sent) sentChunks++ else Log.w(TAG, "Failed to send chunk $chunkIndex/${totalChunks - 1}")
            }

            Toast.makeText(
                requireContext(),
                "Sending ${pendingOffer.fileName}: $sentChunks/$totalChunks chunks",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file chunks: ${e.message}", e)
            Toast.makeText(requireContext(), "Failed to send file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingFileChunk(packet: NetworkPacket, fromNodeId: String?) {
        val chunk = gson.fromJson(packet.payload, FileChunkPayload::class.java) ?: return

        try {
            val transferKey = "${chunk.requestId}_${chunk.offerId}_${chunk.fileId}"
            val transfer = incomingFileTransfers.getOrPut(transferKey) {
                IncomingFileTransfer(
                    requestId = chunk.requestId,
                    offerId = chunk.offerId,
                    fileId = chunk.fileId,
                    fileName = chunk.fileName,
                    fileSizeBytes = chunk.fileSizeBytes,
                    totalChunks = chunk.totalChunks,
                    ownerNodeId = fromNodeId ?: packet.senderNodeId,
                    fileHash = chunk.fileHash,
                    category = chunk.category,
                    tags = chunk.tags,
                    receivedChunks = mutableMapOf()
                )
            }

            if (!transfer.receivedChunks.containsKey(chunk.chunkIndex)) {
                transfer.receivedChunks[chunk.chunkIndex] = Base64.decode(chunk.base64Data, Base64.NO_WRAP)
            }

            val receivedCount = transfer.receivedChunks.size
            updateRequestStatus(chunk.requestId, "DOWNLOADING $receivedCount/${chunk.totalChunks}")
            Log.d(TAG, "Received chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks} for ${chunk.fileName}")

            if (receivedCount == chunk.totalChunks) {
                saveCompletedTransfer(transfer)
                incomingFileTransfers.remove(transferKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process file chunk: ${e.message}", e)
            Toast.makeText(requireContext(), "Failed to process file chunk: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCompletedTransfer(transfer: IncomingFileTransfer) {
        val downloadsDir = File(requireContext().filesDir, "offline_downloads").also { it.mkdirs() }
        val safeFileName = transfer.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(downloadsDir, "${System.currentTimeMillis()}_$safeFileName")

        ByteArrayOutputStream().use { outputStream ->
            for (index in 0 until transfer.totalChunks) {
                val bytes = transfer.receivedChunks[index]
                    ?: throw IllegalStateException("Missing chunk $index for ${transfer.fileName}")
                outputStream.write(bytes)
            }
            outputFile.writeBytes(outputStream.toByteArray())
        }

        val index = requests.indexOfFirst { it.requestId == transfer.requestId }
        if (index != -1) {
            val current = requests[index]
            requests[index] = current.copy(
                status = "DOWNLOADED",
                downloadedFilePath = outputFile.absolutePath,
                canDownload = false
            )
            adapter.submitList(requests.toList())
            saveRequestsToStorage()
        }

        registerCachedSharedFile(
            fileId = transfer.fileId,
            fileName = transfer.fileName,
            fileSizeBytes = transfer.fileSizeBytes,
            fileSizeText = formatFileSize(transfer.fileSizeBytes),
            localUri = Uri.fromFile(outputFile).toString(),
            sourceType = "DOWNLOADED",
            fileHash = transfer.fileHash ?: calculateSha256(outputFile.readBytes()),
            category = transfer.category,
            tags = transfer.tags
        )

        sendFileAck(transfer, outputFile.absolutePath)

        Toast.makeText(
            requireContext(),
            "Downloaded: ${transfer.fileName}",
            Toast.LENGTH_LONG
        ).show()

        Log.d(TAG, "Completed file saved to ${outputFile.absolutePath}")
    }

    private fun sendFileAck(transfer: IncomingFileTransfer, savedPath: String) {
        val ackPayload = FileAckPayload(
            requestId = transfer.requestId,
            offerId = transfer.offerId,
            fileId = transfer.fileId,
            fileName = transfer.fileName,
            receiverNodeId = getLocalNodeId(),
            ownerNodeId = transfer.ownerNodeId,
            savedPath = savedPath,
            timestamp = System.currentTimeMillis()
        )

        val ackPacket = NetworkPacket(
            type = OfflinePacketTypes.FILE_ACK,
            packetId = UUID.randomUUID().toString(),
            senderNodeId = getLocalNodeId(),
            receiverNodeId = transfer.ownerNodeId,
            payload = gson.toJson(ackPayload),
            timestamp = System.currentTimeMillis()
        )

        (activity as? MainActivity)?.sendOfflinePacket(gson.toJson(ackPacket))
    }

    private fun handleIncomingFileAck(packet: NetworkPacket, fromNodeId: String?) {
        val ack = gson.fromJson(packet.payload, FileAckPayload::class.java) ?: return
        if (ack.ownerNodeId != getLocalNodeId()) return

        updateRequestStatus(ack.requestId, "TRANSFER COMPLETE")
        Toast.makeText(
            requireContext(),
            "${ack.fileName} downloaded successfully",
            Toast.LENGTH_SHORT
        ).show()

        Log.d(TAG, "FILE_ACK received from $fromNodeId: $ack")
    }

    private fun updateRequestStatus(requestId: String, status: String) {
        val index = requests.indexOfFirst { it.requestId == requestId }
        if (index != -1) {
            requests[index] = requests[index].copy(status = status)
            adapter.submitList(requests.toList())
            saveRequestsToStorage()
        }
    }

    private fun openDownloadedFile(request: PublicRequestUiModel) {
        val filePath = request.downloadedFilePath
        if (filePath.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No downloaded file found", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Downloaded file was not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )

        val mimeType = getMimeType(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, "Open downloaded file"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No app found to open this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not allow persistable permission. The current URI can still be used now.
        }
    }

    private fun readBytesFromStoredUri(storedUri: String): ByteArray? {
        return try {
            val uri = Uri.parse(storedUri)
            when (uri.scheme) {
                "file" -> {
                    val path = uri.path ?: return null
                    File(path).takeIf { it.exists() }?.readBytes()
                }
                else -> requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stored uri: ${e.message}", e)
            null
        }
    }

    private fun registerCachedSharedFile(
        fileId: String,
        fileName: String,
        fileSizeBytes: Long,
        fileSizeText: String,
        localUri: String,
        sourceType: String,
        fileHash: String? = calculateSha256FromStoredUri(localUri),
        description: String = buildFileDescription(fileName, sourceType),
        category: String = guessCategory(fileName),
        tags: String = generateTagsFromFileName(fileName)
    ) {
        val cachedFile = CachedSharedFile(
            fileId = fileId,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            fileSizeText = fileSizeText,
            localUri = localUri,
            sourceType = sourceType,
            cachedAt = System.currentTimeMillis(),
            fileHash = fileHash,
            category = category,
            description = description,
            tags = tags
        )

        cachedSharedFiles.clear()
        cachedSharedFiles.addAll(SharedFileCacheStore.upsertAndPrune(requireContext(), cachedFile))
        Log.d(TAG, "Cached shared file registered: $cachedFile")
    }

    private fun offerCachedFileIfAvailable(request: PublicRequestUiModel) {
        if (request.requesterNodeId == getLocalNodeId()) return
        if (request.status == "EXPIRED") return

        loadCachedSharedFiles()
        val cachedFile = findBestCachedFileMatch(request.requestText) ?: return
        val pendingOffer = createPendingFileOfferFromCachedFile(request, cachedFile)
        pendingFileOffers[pendingOffer.fileId] = pendingOffer
        pendingFileOffers[pendingOffer.requestId] = pendingOffer
        pendingFileOffers[pendingOffer.offerId] = pendingOffer

        val sent = (activity as? MainActivity)?.sendOfflinePacket(pendingOffer.packetJson) == true
        Log.d(
            TAG,
            "Auto cached FILE_OFFER ${if (sent) "sent" else "queued only"}: ${cachedFile.fileName} for request=${request.requestText}"
        )

        if (sent) {
            Toast.makeText(
                requireContext(),
                "Cached file offered: ${cachedFile.fileName}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun findBestCachedFileMatch(requestText: String): CachedSharedFile? {
        val requestTokens = tokenizeForMatching(requestText)
        if (requestTokens.isEmpty()) return null

        return cachedSharedFiles
            .mapNotNull { file ->
                val searchableText = listOf(
                    file.fileName,
                    file.description,
                    file.category,
                    file.tags,
                    file.fileHash.orEmpty()
                ).joinToString(" ")
                val fileTokens = tokenizeForMatching(searchableText)
                val score = calculateMatchScore(requestTokens, fileTokens, requestText, searchableText)
                if (score > 0) file to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun calculateMatchScore(
        requestTokens: Set<String>,
        fileTokens: Set<String>,
        requestText: String,
        fileName: String
    ): Int {
        val normalizedRequest = normalizeForMatching(requestText)
        val normalizedFileName = normalizeForMatching(fileName.substringBeforeLast('.', fileName))
        val commonCount = requestTokens.intersect(fileTokens).size

        return when {
            normalizedFileName.isNotBlank() && normalizedRequest.contains(normalizedFileName) -> 100 + commonCount
            normalizedRequest.isNotBlank() && normalizedFileName.contains(normalizedRequest) -> 90 + commonCount
            commonCount >= 2 -> 50 + commonCount
            commonCount == 1 && requestTokens.size <= 2 -> 20
            else -> 0
        }
    }

    private fun tokenizeForMatching(text: String): Set<String> {
        return normalizeForMatching(text)
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 3 && it !in setOf("need", "want", "file", "data", "pdf", "the", "for", "with") }
            .toSet()
    }

    private fun normalizeForMatching(text: String): String {
        return text
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun createPendingFileOfferFromCachedFile(
        request: PublicRequestUiModel,
        cachedFile: CachedSharedFile
    ): PendingFileOffer {
        val now = System.currentTimeMillis()
        val offerId = UUID.randomUUID().toString()
        val fileIdForOffer = cachedFile.fileId.ifBlank { UUID.randomUUID().toString() }

        val offerPayload = FileOfferPayload(
            offerId = offerId,
            requestId = request.requestId,
            ownerNodeId = getLocalNodeId(),
            ownerName = getLocalUserName(),
            requesterNodeId = request.requesterNodeId,
            fileId = fileIdForOffer,
            fileName = cachedFile.fileName,
            fileSizeBytes = cachedFile.fileSizeBytes,
            fileSizeText = cachedFile.fileSizeText,
            timestamp = now,
            fileHash = cachedFile.fileHash,
            category = cachedFile.category,
            tags = cachedFile.tags
        )

        val packet = NetworkPacket(
            type = OfflinePacketTypes.FILE_OFFER,
            packetId = UUID.randomUUID().toString(),
            senderNodeId = getLocalNodeId(),
            receiverNodeId = request.requesterNodeId,
            payload = gson.toJson(offerPayload),
            timestamp = now
        )

        return PendingFileOffer(
            offerId = offerId,
            requestId = request.requestId,
            fileId = fileIdForOffer,
            fileName = cachedFile.fileName,
            fileSizeBytes = cachedFile.fileSizeBytes,
            fileSizeText = cachedFile.fileSizeText,
            localFileUri = cachedFile.localUri,
            packetJson = gson.toJson(packet),
            createdAt = now,
            fileHash = cachedFile.fileHash,
            category = cachedFile.category,
            tags = cachedFile.tags
        )
    }

    private fun loadCachedSharedFiles() {
        cachedSharedFiles.clear()
        cachedSharedFiles.addAll(SharedFileCacheStore.load(requireContext()))
        Log.d(TAG, "Loaded ${cachedSharedFiles.size} cached shared file(s)")
    }

    private fun calculateSha256FromStoredUri(storedUri: String): String? {
        return try {
            val bytes = readBytesFromStoredUri(storedUri) ?: return null
            calculateSha256(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Could not calculate SHA-256: ${e.message}")
            null
        }
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun guessCategory(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt" -> "Documents"
            "jpg", "jpeg", "png", "gif", "webp" -> "Images"
            "mp3", "wav", "m4a", "aac", "ogg" -> "Audio"
            "mp4", "mkv", "avi", "mov" -> "Video"
            "zip", "rar", "7z" -> "Archives"
            else -> "General"
        }
    }

    private fun generateTagsFromFileName(fileName: String): String {
        return tokenizeForMatching(fileName.substringBeforeLast('.', fileName))
            .joinToString(", ")
    }

    private fun buildFileDescription(fileName: String, sourceType: String): String {
        return when (sourceType) {
            "LOCAL" -> "User-selected file available for offline sharing: $fileName"
            "DOWNLOADED" -> "Downloaded file cached for peer-to-peer re-sharing: $fileName"
            else -> "Cached file available for offline sharing: $fileName"
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "Selected file"

        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                fileName = cursor.getString(nameIndex) ?: fileName
            }
        }

        return fileName
    }

    private fun getLocalNodeId(): String {
        return (activity as? MainActivity)?.getLocalNodeId() ?: "LOCAL_NODE"
    }

    private fun getLocalUserName(): String {
        return localUserName.ifBlank { (activity as? MainActivity)?.getLocalDisplayName() ?: DEFAULT_USER_NAME }
    }

    private fun createPendingFileOffer(
        request: PublicRequestUiModel,
        fileName: String,
        fileSizeBytes: Long,
        fileSizeText: String,
        fileUri: Uri,
        fileHash: String?,
        category: String,
        tags: String
    ): PendingFileOffer {
        val now = System.currentTimeMillis()
        val offerId = UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()

        val offerPayload = FileOfferPayload(
            offerId = offerId,
            requestId = request.requestId,
            ownerNodeId = getLocalNodeId(),
            ownerName = getLocalUserName(),
            requesterNodeId = request.requesterNodeId,
            fileId = fileId,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            fileSizeText = fileSizeText,
            timestamp = now,
            fileHash = fileHash,
            category = category,
            tags = tags
        )

        val packet = NetworkPacket(
            type = OfflinePacketTypes.FILE_OFFER,
            packetId = UUID.randomUUID().toString(),
            senderNodeId = getLocalNodeId(),
            receiverNodeId = request.requesterNodeId,
            payload = gson.toJson(offerPayload),
            timestamp = now
        )

        return PendingFileOffer(
            offerId = offerId,
            requestId = request.requestId,
            fileId = fileId,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            fileSizeText = fileSizeText,
            localFileUri = fileUri.toString(),
            packetJson = gson.toJson(packet),
            createdAt = now,
            fileHash = fileHash,
            category = category,
            tags = tags
        )
    }

    private fun getFileSizeBytes(uri: Uri): Long {
        var sizeBytes = -1L

        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex != -1) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }

        return sizeBytes
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes < 0) return "Unknown size"

        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0

        return if (mb >= 1) {
            String.format(Locale.getDefault(), "%.2f MB", mb)
        } else {
            String.format(Locale.getDefault(), "%.1f KB", kb)
        }
    }

    private fun loadSavedRequests() {
        val prefs = requireContext().getSharedPreferences(REQUESTS_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(REQUESTS_CACHE_KEY, null) ?: return

        runCatching {
            val array = gson.fromJson(json, Array<PublicRequestUiModel>::class.java).toList()
            val now = System.currentTimeMillis()
            val validRequests = array.filter { request ->
                val timestamp = request.timestamp ?: return@filter true
                val ttl = request.ttl ?: return@filter true
                now <= timestamp + ttl
            }

            requests.clear()
            requests.addAll(validRequests)
            adapter.submitList(requests.toList())
            saveRequestsToStorage()
        }.onFailure { error ->
            Log.e(TAG, "Failed to load cached public requests: ${error.message}")
        }
    }

    private fun saveRequestsToStorage() {
        if (!isAdded) return

        val now = System.currentTimeMillis()
        val validRequests = requests.filter { request ->
            val timestamp = request.timestamp ?: return@filter true
            val ttl = request.ttl ?: return@filter true
            now <= timestamp + ttl
        }

        requireContext()
            .getSharedPreferences(REQUESTS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(REQUESTS_CACHE_KEY, gson.toJson(validRequests))
            .apply()
    }

    private fun updateEmptyState() {
        emptyRequestsText.visibility = if (requests.isEmpty()) View.VISIBLE else View.GONE
        requestsRecyclerView.visibility = if (requests.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatTime(timeMillis: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMillis))
    }
}

data class IncomingFileTransfer(
    val requestId: String,
    val offerId: String,
    val fileId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val totalChunks: Int,
    val ownerNodeId: String,
    val fileHash: String?,
    val category: String,
    val tags: String,
    val receivedChunks: MutableMap<Int, ByteArray>
)

data class PublicRequestUiModel(
    val requestId: String,
    val requesterName: String,
    val requestText: String,
    val timeText: String,
    val status: String,
    val offeredFileName: String? = null,
    val offeredFileSizeText: String? = null,
    val fileOfferJson: String? = null,
    val requesterNodeId: String? = null,
    val timestamp: Long? = null,
    val ttl: Long? = null,
    val hopCount: Int = 0,
    val maxHopCount: Int = 3,
    val isMine: Boolean = false,
    val offerId: String? = null,
    val fileId: String? = null,
    val ownerNodeId: String? = null,
    val ownerName: String? = null,
    val canDownload: Boolean = false,
    val downloadedFilePath: String? = null
)
