package com.github.springeye.diskraidpower.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Device::class], version = 1)
@TypeConverters(value = [RoomConverters::class])
abstract class AppDatabase: RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}