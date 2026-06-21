package com.example.offlineroutingapp.data.dao

import androidx.room.*
import com.example.offlineroutingapp.data.entities.UserEntity
import kotlinx.coroutines.flow.Flow



@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getUser(): UserEntity?

    @Query("SELECT * FROM users LIMIT 1")
    fun getUserFlow(): Flow<UserEntity?>

    @Delete
    suspend fun deleteUser(user: UserEntity)
}
