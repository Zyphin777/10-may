package com.example.offlineroutingapp.discovery

import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log

/**
 * NearbyDiscoveryManager
 *
 * Uses WiFi Direct DNS-SD service discovery to broadcast and detect
 * nearby users without requiring a full WiFi Direct connection.
 */
class NearbyDiscoveryManager(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {

    var onPeerDiscovered: ((
        nodeId: String,
        displayName: String,
        lat: Double,
        lng: Double,
        photoPath: String?
    ) -> Unit)? = null

    var onPeerLost: ((nodeId: String) -> Unit)? = null

    private var isRegistered  = false
    private var isDiscovering = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    companion object {
        private const val TAG          = "NearbyDiscovery"
        private const val SERVICE_TYPE = "_offlinchat._tcp"
        private const val KEY_NODE_ID  = "nid"
        private const val KEY_NAME     = "nm"
        private const val KEY_LAT      = "lat"
        private const val KEY_LNG      = "lng"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast
    // ─────────────────────────────────────────────────────────────────────────

    fun startBroadcastingMyself(
        nodeId: String,
        displayName: String,
        lat: Double,
        lng: Double
    ) {
        if (isRegistered) stopBroadcasting()

        val record = mapOf(
            KEY_NODE_ID to nodeId,
            KEY_NAME    to displayName.take(32),
            KEY_LAT     to "%.6f".format(lat),
            KEY_LNG     to "%.6f".format(lng)
        )

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            nodeId,
            SERVICE_TYPE,
            record
        )

        try {
            manager.addLocalService(
                channel,
                serviceInfo,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        isRegistered = true
                        Log.d(TAG, "Broadcast started: $nodeId at $lat,$lng")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Broadcast failed: $reason")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "addLocalService denied: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "addLocalService error: ${e.message}")
        }
    }

    fun stopBroadcasting() {
        try {
            manager.clearLocalServices(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        isRegistered = false
                        Log.d(TAG, "Broadcast stopped")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "clearLocalServices failed: $reason")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "clearLocalServices denied: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "clearLocalServices error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Discover
    // ─────────────────────────────────────────────────────────────────────────

    fun startDiscovering() {
        if (isDiscovering) return

        // Use underscore for unused lambda parameters instead of @Suppress
        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            val nodeId = record[KEY_NODE_ID] ?: return@DnsSdTxtRecordListener
            val name   = record[KEY_NAME]    ?: device.deviceName ?: "Unknown"
            val latStr = record[KEY_LAT]     ?: return@DnsSdTxtRecordListener
            val lngStr = record[KEY_LNG]     ?: return@DnsSdTxtRecordListener
            val lat    = latStr.toDoubleOrNull() ?: return@DnsSdTxtRecordListener
            val lng    = lngStr.toDoubleOrNull() ?: return@DnsSdTxtRecordListener

            Log.d(TAG, "Peer discovered: $name at $lat,$lng")
            onPeerDiscovered?.invoke(nodeId, name, lat, lng, null)
        }

        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { _, _, device ->
            Log.d(TAG, "Service found from: ${device.deviceName}")
        }

        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener)

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)

        try {
            manager.addServiceRequest(
                channel,
                serviceRequest,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Service request added")
                        discoverServices()
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "addServiceRequest failed: $reason")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "addServiceRequest denied: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "addServiceRequest error: ${e.message}")
        }
    }

    private fun discoverServices() {
        try {
            manager.discoverServices(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        isDiscovering = true
                        Log.d(TAG, "Discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        isDiscovering = false
                        Log.e(TAG, "discoverServices failed: $reason")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "discoverServices denied: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices error: ${e.message}")
        }
    }

    fun stopDiscovering() {
        val req = serviceRequest ?: return
        try {
            manager.removeServiceRequest(
                channel,
                req,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        isDiscovering = false
                        Log.d(TAG, "Discovery stopped")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "removeServiceRequest failed: $reason")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "removeServiceRequest denied: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "removeServiceRequest error: ${e.message}")
        }
        serviceRequest = null
    }

    fun cleanup() {
        stopDiscovering()
        stopBroadcasting()
    }
}
