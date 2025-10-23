package com.github.springeye.diskraidpower.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device")
    fun findAll(): Flow<List<Device>>
}