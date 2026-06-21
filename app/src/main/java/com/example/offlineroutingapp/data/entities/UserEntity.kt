package com.example.offlineroutingapp.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val userId: String,
    val displayName: String,
    val profilePhotoPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

