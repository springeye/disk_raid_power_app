package com.github.springeye.diskraidpower.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

@Entity
data class Device(
    @PrimaryKey(autoGenerate = true)
    val id:Int,
    val name:String,
    val supportType:List<ConnectType> = listOf(),
    val connectType: ConnectType= ConnectType.Bluetooth
)
enum class ConnectType{
    Bluetooth,
    Wifi,
}
object RoomConverters{
    @TypeConverter
    fun supportConnectTypeToList(value:String): List<ConnectType>{
        return Json.decodeFromString(value)
    }
    @TypeConverter
    fun listToSupportConnectType(value: List<ConnectType>): String{
        return Json.encodeToString(value)
    }
}