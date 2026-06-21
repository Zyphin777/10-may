package com.example.offlineroutingapp.nearby

/**
 * NearbyEndpoint
 *
 * Represents a peer device that is either:
 *   - Discovered (found but not yet connected)
 *   - Connected (full two-way Nearby Connections session established)
 *
 * The [endpointId] is assigned by the Nearby Connections API and is
 * temporary — it changes every session.
 *
 * The [nodeId] is your app's permanent UUID stored in SharedPreferences.
 * It is populated after the PROFILE_INFO exchange completes.
 *
 * The [displayName] and [photoPath] are populated after PROFILE_INFO exchange.
 */
data class NearbyEndpoint(
    /** Temporary ID assigned by Nearby Connections — used for all API calls. */
    val endpointId: String,

    /** Permanent app-level UUID — populated after profile exchange. Null until then. */
    val nodeId: String? = null,

    /** Human-readable name advertised by the peer during discovery. */
    val endpointName: String = "",

    /** Display name from PROFILE_INFO exchange. Null until profile received. */
    val displayName: String? = null,

    /** Local file path of the peer's profile photo. Null until profile received. */
    val photoPath: String? = null,

    /** Whether a full Nearby Connections session is currently active. */
    val isConnected: Boolean = false,

    /** Epoch millis of the last received payload — used for stale detection. */
    val lastSeen: Long = System.currentTimeMillis()
) {
    /**
     * The best available name to display in the UI.
     * Uses displayName (from profile) if available, otherwise endpointName
     * (from Nearby discovery), otherwise the endpointId as a last resort.
     */
    val bestDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: endpointName.takeIf { it.isNotBlank() }
            ?: endpointId
}
