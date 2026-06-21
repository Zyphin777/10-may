package com.example.offlineroutingapp.location

data class NearbyUser(
    val nodeId: String,
    val displayName: String,
    val photoPath: String?,
    val latitude: Double,
    val longitude: Double,
    val lastSeen: Long = System.currentTimeMillis()
)