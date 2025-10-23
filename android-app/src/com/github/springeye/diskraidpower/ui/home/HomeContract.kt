package com.github.springeye.diskraidpower.ui.home

import android.net.Network
import com.github.springeye.diskraidpower.db.Device

data class HomeState(
    val devices:List<Device> = listOf(),
    val espState: EspState = EspState.Idle,
)
/**
 * UI State that represents HomeScreen
 **/
sealed class EspState {
    object Idle : EspState()
    object Scanning : EspState()
    data class FoundAp(val ssid: String) : EspState()
    data class Connecting(val ssid: String) : EspState()
    data class Connected(val network: Network, val gateway: String?) : EspState()
    data class DiscoveryProgress(val checked: Int, val total: Int) : EspState()
    data class DeviceFound(val ip: String, val httpBodyPreview: String?) : EspState()
    data class Error(val message: String) : EspState()
}


/**
 * Home Actions emitted from the UI Layer
 * passed to the coordinator to handle
 **/

sealed interface HomeAction {
    data object OnClick : HomeAction
}

