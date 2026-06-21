package com.example.offlineroutingapp.location

import android.location.Location

/**
 * LocationStore
 *
 * In-memory store of the last known GPS coordinates for every user
 * (both the local user and all peers).
 *
 * Updated by:
 *   - NearbyMapActivity  (GPS updates + DNS-SD peer discoveries)
 *   - WifiDirectService  (LOCATION_UPDATE packets from connected peers)
 *
 * Read by:
 *   - ChatActivity       (distance shown in header under display name)
 *   - ChatListAdapter    (distance shown under name in chat list)
 */
object LocationStore {

    // nodeId → Pair(latitude, longitude)
    private val locations = mutableMapOf<String, Pair<Double, Double>>()

    // nodeId → GPS accuracy in meters, when available
    private val accuracies = mutableMapOf<String, Float>()

    // nodeId → last update time
    private val updatedAt = mutableMapOf<String, Long>()

    // My own nodeId — set once on app start
    var myNodeId: String? = null

    // My own last location
    var myLocation: Pair<Double, Double>? = null

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Store or update a peer's location. */
    fun updatePeer(nodeId: String, lat: Double, lng: Double, accuracyMeters: Float? = null) {
        locations[nodeId] = Pair(lat, lng)
        accuracyMeters?.let { accuracies[nodeId] = it }
        updatedAt[nodeId] = System.currentTimeMillis()
    }

    /** Store my own location. */
    fun updateMyLocation(lat: Double, lng: Double, accuracyMeters: Float? = null) {
        myLocation = Pair(lat, lng)
        myNodeId?.let {
            locations[it] = Pair(lat, lng)
            accuracyMeters?.let { acc -> accuracies[it] = acc }
            updatedAt[it] = System.currentTimeMillis()
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Get last known location for a nodeId, or null if never seen. */
    fun getLocation(nodeId: String): Pair<Double, Double>? = locations[nodeId]

    /**
     * Calculate distance in metres between my location and a peer.
     * Returns null if either location is unknown.
     */
    fun distanceTo(peerNodeId: String): Float? {
        val me   = myLocation ?: return null
        val peer = locations[peerNodeId] ?: return null

        val results = FloatArray(1)
        Location.distanceBetween(me.first, me.second, peer.first, peer.second, results)
        return results[0]
    }

    /**
     * Format distance as a human-readable string.
     * Returns null if distance cannot be calculated.
     */
    fun formattedDistanceTo(peerNodeId: String): String? {
        val metres = distanceTo(peerNodeId) ?: return null
        return when {
            metres < 10 -> "${"%.1f".format(metres)} m away"
            metres < 1000 -> "${"%.0f".format(metres)} m away"
            else -> "${"%.2f".format(metres / 1000)} km away"
        }
    }

    fun accuracyFor(nodeId: String): Float? = accuracies[nodeId]

    fun lastUpdatedAt(nodeId: String): Long? = updatedAt[nodeId]

    /** Clear all stored locations (e.g. on logout). */
    fun clear() {
        locations.clear()
        accuracies.clear()
        updatedAt.clear()
        myLocation = null
    }
}
