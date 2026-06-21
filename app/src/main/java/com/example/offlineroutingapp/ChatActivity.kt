package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.util.Base64
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.location.LocationStore
import com.example.offlineroutingapp.nearby.NearbyConnectionsService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.*
import android.util.Log
import android.widget.Button
import android.widget.Toast
private var recordStartTime: Long = 0L

class ChatActivity : AppCompatActivity() {

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var mediaBtn: ImageButton
    private lateinit var voiceBtn: ImageButton
    private lateinit var locationBtn: ImageButton
    private lateinit var backBtn: ImageButton
    private lateinit var chatUserProfile: ImageView
    private lateinit var chatUserName: TextView
    private lateinit var chatUserDistance: TextView
    private lateinit var requestLeadershipBtn: Button

    // ── Data ──────────────────────────────────────────────────────────────────
    private val messages = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── Chat / Routing ────────────────────────────────────────────────────────
    private var chatId: String? = null
    private var useRoutedMode = false
    private var targetNodeId: String? = null

    // ── Nearby service ────────────────────────────────────────────────────────
    private var nearbyService: NearbyConnectionsService? = null
    private var serviceBound = false

    // ── Voice ─────────────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    private var isRecording = false

    // ── Location ──────────────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST = 3001

    private val chatLocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 4_000L
    ).setMinUpdateIntervalMillis(2_000L).build()

    private val chatLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)
            lifecycleScope.launch {
                val user = database.userDao().getUser() ?: return@launch
                nearbyService?.broadcastLocationUpdate(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    displayName = user.displayName,
                    photoPath = user.profilePhotoPath,
                    accuracy = loc.accuracy
                )
            }
            updateDistanceDisplay()
        }
    }

    // ── Distance refresh ──────────────────────────────────────────────────────
    private val distanceHandler  = Handler(Looper.getMainLooper())
    private val distanceRunnable = object : Runnable {
        override fun run() {
            updateDistanceDisplay()
            distanceHandler.postDelayed(this, 10_000L)
        }
    }

    // ── Service connection ────────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            nearbyService = (service as NearbyConnectionsService.LocalBinder).getService()
            serviceBound = true

            // Existing message callback
            nearbyService?.onMessageReceivedWithFrom =
                { text, isImage, imageData, fromId, isRouted ->
                    handleReceivedMessage(text, isImage, imageData, fromId, isRouted)
                }

            // Logical GO update callback
            nearbyService?.onLogicalGoChanged = { goId ->
                runOnUiThread {
                    requestLeadershipBtn.text =
                        if (nearbyService?.isMeLogicalGo() == true) {
                            "You are App GO"
                        } else {
                            "App GO: ${goId.take(6)}"
                        }
                }
            }

            // Show current Logical GO if already saved
            val currentGo = nearbyService?.getCurrentLogicalGo()

            if (!currentGo.isNullOrBlank()) {
                requestLeadershipBtn.text =
                    if (nearbyService?.isMeLogicalGo() == true) {
                        "You are App GO"
                    } else {
                        "App GO: ${currentGo.take(6)}"
                    }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nearbyService = null
            serviceBound = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatId        = intent.getStringExtra("CHAT_ID")
        useRoutedMode = intent.getBooleanExtra("USE_ROUTED_MODE", false)
        targetNodeId  = intent.getStringExtra("DST_NODE_ID")

        initViews()
        voiceBtn.bringToFront()
        setupRecycler()
        bindToService()
        loadMessages()
        setupListeners()
        loadHeaderInfo()
        startChatLocationUpdatesIfAllowed()
        distanceHandler.post(distanceRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        distanceHandler.removeCallbacks(distanceRunnable)
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(chatLocationCallback)
        }
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI init
    // ─────────────────────────────────────────────────────────────────────────

    private fun initViews() {
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageInput         = findViewById(R.id.messageInput)
        sendBtn              = findViewById(R.id.sendBtn)
        mediaBtn             = findViewById(R.id.mediaBtn)
        voiceBtn             = findViewById(R.id.voiceBtn)
        locationBtn          = findViewById(R.id.locationBtn)
        backBtn              = findViewById(R.id.backBtn)
        chatUserProfile      = findViewById(R.id.chatUserProfile)
        chatUserName         = findViewById(R.id.chatUserName)
        chatUserDistance     = findViewById(R.id.chatUserDistance)

        backBtn.setOnClickListener { finish() }
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        requestLeadershipBtn = findViewById(R.id.requestLeadershipBtn)
    }

    private fun setupRecycler() {
        messageAdapter = MessageAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter       = messageAdapter
    }

    private fun bindToService() {
        bindService(
            Intent(this, NearbyConnectionsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header — profile photo + display name + distance
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadHeaderInfo() {
        val nameFromIntent  = intent.getStringExtra(MainActivity.EXTRA_USER_NAME)
        val photoFromIntent = intent.getStringExtra(MainActivity.EXTRA_USER_PHOTO)

        if (!nameFromIntent.isNullOrBlank()) chatUserName.text = nameFromIntent
        applyProfilePhoto(photoFromIntent)

        chatId?.let { id ->
            lifecycleScope.launch {
                val chat = database.chatDao().getChatById(id)
                chat?.let {
                    withContext(Dispatchers.Main) {
                        if (!it.userName.isNullOrBlank()) chatUserName.text = it.userName
                        applyProfilePhoto(it.userProfilePhoto)
                    }
                }
            }
        }
    }

    private fun applyProfilePhoto(photoPath: String?) {
        if (!photoPath.isNullOrEmpty()) {
            val file = File(photoPath)
            if (file.exists()) {
                try {
                    chatUserProfile.setImageBitmap(BitmapFactory.decodeFile(photoPath))
                    return
                } catch (_: Exception) {}
            }
        }
        chatUserProfile.setImageResource(android.R.drawable.ic_menu_camera)
    }

    private fun updateDistanceDisplay() {
        val id       = chatId ?: return
        val distance = LocationStore.formattedDistanceTo(id)
        if (distance != null) {
            chatUserDistance.text       = "📍 $distance"
            chatUserDistance.visibility = View.VISIBLE
        } else {
            chatUserDistance.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        sendBtn.setOnClickListener { sendMessage() }
        mediaBtn.setOnClickListener { mediaPicker.launch("*/*") }
        locationBtn.setOnClickListener { requestAndSendLocation() }

        voiceBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN   -> { if (checkAudioPermission()) startRecording(); true }
                MotionEvent.ACTION_UP     -> { stopRecording(send = true); true }
                MotionEvent.ACTION_CANCEL -> { stopRecording(send = false); true }
                else -> false
            }
        }

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrBlank()) {
                    sendBtn.visibility = View.GONE
                    voiceBtn.visibility = View.VISIBLE
                    locationBtn.visibility = View.VISIBLE
                } else {
                    sendBtn.visibility = View.VISIBLE
                    voiceBtn.visibility = View.GONE
                    locationBtn.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        requestLeadershipBtn.setOnClickListener {
            nearbyService?.sendGoRequest()
            Toast.makeText(this, "Leadership request sent", Toast.LENGTH_SHORT).show()
        }
        requestLeadershipBtn.setOnLongClickListener {
            val service = nearbyService ?: return@setOnLongClickListener true

            if (service.isMeLogicalGo()) {
                service.sendMessage("📢 Official announcement from Logical GO")
                Toast.makeText(this, "Official announcement sent", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Only Logical GO can send announcements", Toast.LENGTH_SHORT).show()
            }

            true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load messages
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadMessages() {
        chatId?.let { id ->
            lifecycleScope.launch {
                database.messageDao().getMessagesByChatId(id).collect { list ->
                    messages.clear()
                    messages.addAll(list.map {
                        Message(
                            text             = it.text,
                            isSentByMe       = it.isSentByMe,
                            timestamp        = it.timestamp,
                            isImage          = it.isImage,
                            imageData        = it.imageData,
                            isDelivered      = it.isDelivered,
                            isSeen           = it.isSeen,
                            isAudio          = it.isAudio,
                            audioDuration    = it.audioDuration,
                            isDocument       = it.isDocument,
                            documentFileName = it.documentFileName,
                            isLocation       = it.isLocation,
                            locationLat      = it.locationLat,
                            locationLng      = it.locationLng,
                            locationLabel    = it.locationLabel
                        )
                    })
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty())
                        messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send text
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        val service = nearbyService ?: return
        val id      = chatId ?: return

        if (useRoutedMode && !targetNodeId.isNullOrBlank()) {
            service.sendRoutedText(targetNodeId!!, text)
        } else {
            service.sendMessage(text)
        }

        lifecycleScope.launch {
            database.messageDao().insertMessage(
                MessageEntity(chatId = id, text = text, isSentByMe = true)
            )
        }
        messageInput.text.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receive message
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleReceivedMessage(
        text: String, isImage: Boolean, imageData: String?,
        fromId: String?, isRouted: Boolean
    ) {
        val effectiveChatId = if (isRouted && !fromId.isNullOrBlank()) fromId else chatId
        if (effectiveChatId.isNullOrBlank()) return

        val locationJson = if (!isImage) {
            try { JSONObject(text).takeIf { it.optString("type") == "LOCATION" } }
            catch (_: Exception) { null }
        } else null

        val isAudio    = text == "🎤 Voice Message"
        val isDocument = text.startsWith("📄")

        lifecycleScope.launch {
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
                    chatId           = effectiveChatId,
                    text             = if (isImage) "" else text,
                    isSentByMe       = false,
                    isImage          = isImage,
                    isAudio          = isAudio,
                    imageData        = imageData,
                    isDelivered      = true,
                    isDocument       = isDocument,
                    documentFileName = if (isDocument) text.removePrefix("📄 ").trim() else null
                )
            }

            val newId = database.messageDao().insertMessage(entity)

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
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Location sharing
    // ─────────────────────────────────────────────────────────────────────────

    private fun startChatLocationUpdatesIfAllowed() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) return
        try {
            fusedClient.requestLocationUpdates(chatLocationRequest, chatLocationCallback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc ?: return@addOnSuccessListener
                LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)
                updateDistanceDisplay()
            }
        } catch (se: SecurityException) {
            Log.w("ChatActivity", "Location update skipped: ${se.message}")
        }
    }

    private fun requestAndSendLocation() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        captureAndSendLocation()
    }

    private fun captureAndSendLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) return

        Toast.makeText(this, "Getting location…", Toast.LENGTH_SHORT).show()
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    location ?: run {
                        Toast.makeText(this, "Could not get location.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }
                    val lat = location.latitude
                    val lng = location.longitude
                    LocationStore.updateMyLocation(lat, lng, location.accuracy)

                    lifecycleScope.launch(Dispatchers.IO) {
                        val label = try {
                            @Suppress("DEPRECATION")
                            val addresses = Geocoder(this@ChatActivity, Locale.getDefault())
                                .getFromLocation(lat, lng, 1)
                            if (!addresses.isNullOrEmpty()) {
                                listOfNotNull(
                                    addresses[0].thoroughfare,
                                    addresses[0].locality,
                                    addresses[0].countryName
                                ).joinToString(", ").ifBlank { "%.5f, %.5f".format(lat, lng) }
                            } else "%.5f, %.5f".format(lat, lng)
                        } catch (_: Exception) { "%.5f, %.5f".format(lat, lng) }

                        withContext(Dispatchers.Main) { doSendLocationMessage(lat, lng, label) }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Location error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "Location permission denied: ${se.message}")
        }
    }

    private fun doSendLocationMessage(lat: Double, lng: Double, label: String) {
        val service = nearbyService ?: return
        val id      = chatId ?: return
        val payload = """{"type":"LOCATION","lat":$lat,"lng":$lng,"label":"${label.replace("\"", "\\\"")}"}"""

        if (useRoutedMode && !targetNodeId.isNullOrBlank()) service.sendRoutedText(targetNodeId!!, payload)
        else service.sendMessage(payload)

        lifecycleScope.launch {
            database.messageDao().insertMessage(
                MessageEntity(
                    chatId = id, text = payload, isSentByMe = true,
                    isLocation = true, locationLat = lat, locationLng = lng, locationLabel = label
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Voice recording
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) true
        else { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); false }
    }

    private fun startRecording() {
        recordStartTime = System.currentTimeMillis()
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.3gp")
        audioFilePath = file.absolutePath
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFilePath)
            prepare(); start()
        }
        isRecording = true
    }

    private fun stopRecording(send: Boolean) {
        if (!isRecording) return
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release(); mediaRecorder = null; isRecording = false
        val duration = System.currentTimeMillis() - recordStartTime

        if (send && audioFilePath != null) {
            val bytes  = FileInputStream(File(audioFilePath!!)).readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            nearbyService?.sendVoice(bytes, duration)
            chatId?.let { id ->
                lifecycleScope.launch {
                    val entity = MessageEntity(
                        chatId = id, text = "🎤 Voice Message", isSentByMe = true,
                        isAudio = true, imageData = base64, audioDuration = duration
                    )
                    val newId = database.messageDao().insertMessage(entity)
                    try {
                        com.example.offlineroutingapp.offload.OffloadHelper.maybeOffload(
                            applicationContext, entity.copy(id = newId), id
                        )
                    } catch (se: SecurityException) { Log.w(TAG, "Voice offload skipped: ${se.message}") }
                    catch (e: Exception)          { Log.e(TAG, "Voice offload failed: ${e.message}") }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media picker
    // ─────────────────────────────────────────────────────────────────────────

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("image/")) sendImage(uri) else sendDocument(uri)
    }

    private fun sendDocument(uri: Uri) {
        val bytes    = contentResolver.openInputStream(uri)?.readBytes() ?: return
        val fileName = getFileName(uri)
        nearbyService?.sendDocument(bytes, fileName, "*/*")
        chatId?.let { id ->
            lifecycleScope.launch {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val entity = MessageEntity(
                    chatId = id, text = "📄 $fileName", isSentByMe = true,
                    isDocument = true, documentFileName = fileName, imageData = base64
                )
                val newId = database.messageDao().insertMessage(entity)
                try {
                    com.example.offlineroutingapp.offload.OffloadHelper.maybeOffload(
                        applicationContext, entity.copy(id = newId), id
                    )
                } catch (se: SecurityException) { Log.w(TAG, "Doc offload skipped: ${se.message}") }
                catch (e: Exception)          { Log.e(TAG, "Doc offload failed: ${e.message}") }
            }
        }
    }

    private fun sendImage(uri: Uri) {
        val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
        if (bytes.size < 300 * 1024) nearbyService?.sendImage(bytes)
        else nearbyService?.sendImageChunked(bytes)
        chatId?.let { id ->
            lifecycleScope.launch {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val entity = MessageEntity(
                    chatId = id, text = "", isSentByMe = true, isImage = true, imageData = base64
                )
                val newId = database.messageDao().insertMessage(entity)
                try {
                    com.example.offlineroutingapp.offload.OffloadHelper.maybeOffload(
                        applicationContext, entity.copy(id = newId), id
                    )
                } catch (se: SecurityException) { Log.w(TAG, "Image offload skipped: ${se.message}") }
                catch (e: Exception)          { Log.e(TAG, "Image offload failed: ${e.message}") }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "document"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startChatLocationUpdatesIfAllowed()
            captureAndSendLocation()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ChatActivity"
    }
}
