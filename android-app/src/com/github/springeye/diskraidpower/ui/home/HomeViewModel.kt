package com.github.springeye.diskraidpower.ui.home

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.pow

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

class HomeViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<EspState>(EspState.Idle)
    val state: StateFlow<EspState> = _state.asStateFlow()

    private val wifiManager = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 启动：扫描 AP -> 找到目标 SSID -> 请求临时连接 -> 连接后尝试获取 IP 或做子网探测
    fun startConnectToEsp(targetSsid: String, passphrase: String? = null) {
        viewModelScope.launch {
            _state.value = EspState.Scanning

            // 简单触发扫描（设备/厂商不同可能节流）
            val started = try { wifiManager.startScan() } catch (e: Exception) { false }
            delay(700) // give system a moment to fill scanResults (实战中请使用 registerScanResultsCallback)
            val results: List<ScanResult> = wifiManager.scanResults ?: emptyList()
            val found = results.any { it.SSID == targetSsid }

            if (!found) {
                _state.value = EspState.Error("未在扫描结果中找到 SSID: $targetSsid")
                return@launch
            }

            _state.value = EspState.FoundAp(targetSsid)
            // 请求临时连接
            requestTemporaryConnection(targetSsid, passphrase)
        }
    }

    private fun requestTemporaryConnection(ssid: String, passphrase: String?) {
        _state.value = EspState.Connecting(ssid)

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (!passphrase.isNullOrBlank()) {
            specifierBuilder.setWpa2Passphrase(passphrase)
        }
        val specifier = specifierBuilder.build()

        val nr = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        // 记录 callback 以便 later unregister
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                viewModelScope.launch {
                    _state.value = EspState.Connected(network, null)
                    // 获取 link properties，尝试直接读出网关
                    val lp = connectivityManager.getLinkProperties(network)
                    val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                        ?: lp?.linkAddresses?.firstOrNull()?.address?.hostAddress
                    _state.value = EspState.Connected(network, gateway)

                    // 1) 如果得到了 gateway 很可能就是设备 IP（esp32 常见） -> 直接试 HTTP
                    if (!gateway.isNullOrBlank()) {
                        val found = probeHttpOnIp(network, gateway)
                        if (found) return@launch
                    }

                    // 2) 如果没有网关或网关不可用，做子网扫描（并发 HTTP 探测常见端口）
                    // 为了保证探测走这个 network，临时 bind process 到 network（简单做法）
                    // 注意：这会影响本进程的所有网络请求，完成后请解绑
                    val wasBound = connectivityManager.bindProcessToNetwork(network)
                    try {
                        val success = scanSubnetAndFindDevice(network)
                        if (!success) {
                            _state.value = EspState.Error("在子网内未发现设备")
                        }
                    } finally {
                        // 解绑
                        connectivityManager.bindProcessToNetwork(null)
                    }
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                viewModelScope.launch { _state.value = EspState.Error("系统无法提供所请求的网络（onUnavailable）") }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                viewModelScope.launch { _state.value = EspState.Error("临时网络已断开") }
            }
        }

        networkCallback = cb
        connectivityManager.requestNetwork(nr, cb)
    }

    // 直接尝试对单个 IP 发起 HTTP 请求
    private suspend fun probeHttpOnIp(network: Network, ip: String, port: Int = 80, path: String = "/"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$ip:$port$path"
                val client = OkHttpClient.Builder()
                    // 这里没有设置 socketFactory; we rely on bindProcessToNetwork having been called if needed.
                    .callTimeout(java.time.Duration.ofSeconds(4))
                    .build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful) {
                        _state.value = EspState.DeviceFound(ip, body?.take(500))
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            return@withContext false
        }
    }

    // 子网扫描：计算子网范围并并发试 HTTP（端口 80 / 8080）—— 并发受限以避免阻塞系统
    private suspend fun scanSubnetAndFindDevice(network: Network): Boolean {
        // 尝试从 linkProperties 获取本地地址与 prefix
        val lp = connectivityManager.getLinkProperties(network) ?: return false
        val linkAddr = lp.linkAddresses.firstOrNull() ?: return false
        val addr = linkAddr.address ?: return false
        val prefixLen = linkAddr.prefixLength.toInt()
        val ips = generateIpsFromPrefix(addr, prefixLen)

        val total = ips.size
        var checked = 0
        _state.value = EspState.DiscoveryProgress(checked, total)

        val semaphore = Semaphore(60) // 限制并发
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = ips.map { ip ->
            scope.async {
                semaphore.acquire()
                try {
                    // 尝试常见端口
                    val ports = listOf(80, 8080)
                    for (p in ports) {
                        try {
                            // 快速 TCP connect to port to see if something is there
                            Socket().use { sock ->
                                sock.soTimeout = 1500
                                sock.connect(InetSocketAddress(ip, p), 1500)
                                // 如果能连上，尝试 HTTP GET
                                val found = probeHttpOnIp(network, ip, p)
                                if (found) return@async ip
                            }
                        } catch (e: Exception) {
                            // not responsive on this port
                        }
                    }
                } finally {
                    semaphore.release()
                    checked++
                    _state.value = EspState.DiscoveryProgress(checked, total)
                }
                return@async null
            }
        }

        val result = jobs.awaitAll().firstOrNull { it != null }
        return result != null
    }

    // 根据地址和 prefix 生成可探测的 IP 列表（排除网络号与广播）
    private fun generateIpsFromPrefix(addr: InetAddress, prefixLen: Int): List<String> {
        val bytes = addr.address
        // 支持 IPv4 only for simplicity
        if (bytes.size != 4) return emptyList()
        val ipInt = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val hostBits = 32 - prefixLen
        if (hostBits <= 0 || hostBits > 16) {
            // 为简单起见限制 hostBits（太大时返回局域网内常见范围）
        }
        val netPrefix = (ipInt shr hostBits) shl hostBits
        val size = 2.0.pow(hostBits.toDouble()).toInt()
        val ips = mutableListOf<String>()
        // skip first (network) and last (broadcast)
        for (i in 1 until size - 1) {
            val ip = netPrefix + i
            val b1 = (ip shr 24) and 0xFF
            val b2 = (ip shr 16) and 0xFF
            val b3 = (ip shr 8) and 0xFF
            val b4 = ip and 0xFF
            ips += "$b1.$b2.$b3.$b4"
        }
        return ips
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
    }

}