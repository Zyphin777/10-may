package com.example.offlineroutingapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.discovery.NearbyDiscoveryManager
import com.example.offlineroutingapp.location.LocationStore
import com.example.offlineroutingapp.location.NearbyUser
import com.example.offlineroutingapp.nearby.NearbyConnectionsService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class NearbyMapActivity : AppCompatActivity() {

    // Fully offline map view: no OSM tiles, no internet, no tile cache.
    private lateinit var offlineMapView: OfflinePeerMapView

    // ── Location ──────────────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private var myLocation: Location? = null
    private var myNodeId: String? = null
    private var myDisplayName: String? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3_000L
    )
        .setMinUpdateIntervalMillis(1_500L)
        .setWaitForAccurateLocation(true)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            myLocation = loc
            updateMyLocationOnMap(loc)
            LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)

            val nodeId = myNodeId ?: return
            val name = myDisplayName ?: return
            discoveryManager?.startBroadcastingMyself(nodeId, name, loc.latitude, loc.longitude)
            broadcastToConnectedPeers(loc)
        }
    }

    // ── WiFi Direct P2P discovery ─────────────────────────────────────────────
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private var discoveryManager: NearbyDiscoveryManager? = null

    // ── Nearby service for connected peers ────────────────────────────────────
    private var wifiService: NearbyConnectionsService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            wifiService = (binder as NearbyConnectionsService.LocalBinder).getService()
            serviceBound = true
            wifiService?.onLocationUpdateReceived = { nodeId, lat, lng, displayName, photoPath ->
                runOnUiThread {
                    handlePeerLocationUpdate(nodeId, lat, lng, displayName, photoPath)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService = null
            serviceBound = false
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────
    private val nearbyUsers = mutableMapOf<String, NearbyUser>()
    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var bottomSheet: LinearLayout
    private lateinit var tvPeerName: TextView
    private lateinit var tvPeerDistance: TextView
    private lateinit var btnChat: MaterialButton
    private lateinit var btnClose: ImageButton
    private lateinit var tvOnlineCount: TextView
    private var selectedNodeId: String? = null

    private val LOCATION_PERMISSION_REQUEST = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_map)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)

        initViews()
        initOfflineMap()
        loadMyProfile()
        bindWifiService()
        checkLocationPermissionAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        discoveryManager?.cleanup()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun loadMyProfile() {
        lifecycleScope.launch {
            val user = database.userDao().getUser() ?: return@launch
            myNodeId = wifiService?.getNodeId() ?: user.userId
            myDisplayName = user.displayName
            offlineMapView.setMyLocation(myLocation, user.displayName)
        }
    }

    private fun initViews() {
        bottomSheet = findViewById(R.id.bottomSheetPeer)
        tvPeerName = findViewById(R.id.tvPeerName)
        tvPeerDistance = findViewById(R.id.tvPeerDistance)
        btnChat = findViewById(R.id.btnChatWithPeer)
        btnClose = findViewById(R.id.btnClosePeerSheet)
        tvOnlineCount = findViewById(R.id.tvOnlineCount)

        findViewById<ImageButton>(R.id.btnMapBack).setOnClickListener { finish() }
        btnClose.setOnClickListener { hideBottomSheet() }

        btnChat.setOnClickListener {
            val nodeId = selectedNodeId ?: return@setOnClickListener
            val user = nearbyUsers[nodeId] ?: return@setOnClickListener
            lifecycleScope.launch {
                if (database.chatDao().getChatById(nodeId) == null) {
                    database.chatDao().insertChat(
                        ChatEntity(
                            chatId = nodeId,
                            userName = user.displayName,
                            userProfilePhoto = user.photoPath
                        )
                    )
                }
            }
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CHAT_ID, nodeId)
                putExtra(MainActivity.EXTRA_USER_NAME, user.displayName)
                putExtra(MainActivity.EXTRA_USER_PHOTO, user.photoPath)
                putExtra(MainActivity.EXTRA_USE_ROUTED_MODE, false)
            })
        }
    }

    private fun initOfflineMap() {
        offlineMapView = findViewById(R.id.offlineMapView)
        offlineMapView.onPeerClicked = { nodeId -> showPeerBottomSheet(nodeId) }
    }

    private fun checkLocationPermissionAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocationAndDiscovery()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationAndDiscovery()
        } else {
            Toast.makeText(this, "Location permission required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startLocationAndDiscovery() {
        startLocationUpdates()
        startNearbyDiscovery()
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) return
        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc ?: return@addOnSuccessListener
                myLocation = loc
                updateMyLocationOnMap(loc)
                LocationStore.updateMyLocation(loc.latitude, loc.longitude, loc.accuracy)

                val nodeId = myNodeId ?: return@addOnSuccessListener
                val name = myDisplayName ?: return@addOnSuccessListener
                discoveryManager?.startBroadcastingMyself(nodeId, name, loc.latitude, loc.longitude)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "Location permission denied: ${se.message}")
        }
    }

    private fun startNearbyDiscovery() {
        discoveryManager = NearbyDiscoveryManager(wifiP2pManager, wifiP2pChannel)
        discoveryManager?.onPeerDiscovered = { nodeId, displayName, lat, lng, photoPath ->
            runOnUiThread { handlePeerLocationUpdate(nodeId, lat, lng, displayName, photoPath) }
        }
        discoveryManager?.onPeerLost = { nodeId ->
            runOnUiThread { removePeerMarker(nodeId) }
        }
        discoveryManager?.startDiscovering()
    }

    private fun updateMyLocationOnMap(loc: Location) {
        offlineMapView.setMyLocation(loc, myDisplayName ?: "Me")
    }

    private fun handlePeerLocationUpdate(
        nodeId: String,
        lat: Double,
        lng: Double,
        displayName: String,
        photoPath: String?
    ) {
        if (nodeId == myNodeId) return

        val user = NearbyUser(nodeId, displayName, photoPath, lat, lng)
        nearbyUsers[nodeId] = user
        LocationStore.updatePeer(nodeId, lat, lng)
        offlineMapView.updatePeer(user)
        updateOnlineCount()
    }

    private fun removePeerMarker(nodeId: String) {
        offlineMapView.removePeer(nodeId)
        nearbyUsers.remove(nodeId)
        updateOnlineCount()
    }

    private fun showPeerBottomSheet(nodeId: String) {
        val user = nearbyUsers[nodeId] ?: return
        selectedNodeId = nodeId
        tvPeerName.text = user.displayName
        tvPeerDistance.text = myLocation?.let { myLoc ->
            val res = FloatArray(1)
            Location.distanceBetween(myLoc.latitude, myLoc.longitude, user.latitude, user.longitude, res)
            formatDistance(res[0])
        } ?: "Unknown distance"
        bottomSheet.visibility = View.VISIBLE
        bottomSheet.animate().translationY(0f).setDuration(250).start()
    }

    private fun hideBottomSheet() {
        selectedNodeId = null
        bottomSheet.animate().translationY(400f).setDuration(200).withEndAction {
            bottomSheet.visibility = View.GONE
        }.start()
    }

    private fun formatDistance(metres: Float) = when {
        metres < 10 -> "${"%.1f".format(metres)} m away"
        metres < 1000 -> "${"%.0f".format(metres)} m away"
        else -> "${"%.2f".format(metres / 1000)} km away"
    }

    private fun updateOnlineCount() {
        val count = nearbyUsers.size
        tvOnlineCount.text = if (count == 0) "No users nearby"
        else "$count user${if (count > 1) "s" else ""} nearby"
    }

    private fun broadcastToConnectedPeers(loc: Location) {
        lifecycleScope.launch {
            val user = database.userDao().getUser() ?: return@launch
            wifiService?.broadcastLocationUpdate(
                lat = loc.latitude,
                lng = loc.longitude,
                displayName = user.displayName,
                photoPath = user.profilePhotoPath,
                accuracy = loc.accuracy
            )
        }
    }

    private fun bindWifiService() {
        bindService(
            Intent(this, NearbyConnectionsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    companion object {
        private const val TAG = "NearbyMapActivity"
    }
}
