package com.github.springeye.diskraidpower.ui.home

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.springeye.diskraidpower.db.DeviceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.pow


@Suppress("UNUSED_PARAMETER")
class HomeViewModel(
    private val app: Application,
    private val deviceDao: DeviceDao,
    private val savedStateHandle: SavedStateHandle // intentionally unused for now
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<HomeState>(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val wifiManager = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    init {
        deviceDao.findAll().onEach {devices->
            _state.update {
                it.copy(devices=devices)
            }
        }.launchIn(viewModelScope)
    }

    // 启动：扫描 AP -> 找到目标 SSID -> 请求临时连接 -> 连接后尝试获取 IP 或做子网探测
    fun startConnectToEsp(targetSsid: String, passphrase: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(espState = EspState.Scanning) }

            // 检查定位权限（用于获取扫描结果）
            if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                _state.update { it.copy(espState = EspState.Error("需要定位权限以扫描 Wi‑Fi")) }
                return@launch
            }

            // 简单触发扫描（设备/厂商不同可能节流）
            try { wifiManager.startScan() } catch (_: Exception) { }
            delay(700) // give system a moment to fill scanResults (实战中请使用 registerScanResultsCallback)
            val results: List<ScanResult> = try { wifiManager.scanResults ?: emptyList() } catch (_: SecurityException) { emptyList() }
            val found = results.any { it.SSID == targetSsid }

            if (!found) {
                _state.update { it.copy(espState = EspState.Error("未在扫描结果中找到 SSID: $targetSsid")) }
                return@launch
            }
            _state.update { it.copy(espState = EspState.FoundAp(targetSsid)) }
            // 请求临时连接
            requestTemporaryConnection(targetSsid, passphrase)
        }
    }

    @Suppress("NewApi")
    private fun requestTemporaryConnection(ssid: String, passphrase: String?) {
        _state.update { it.copy(espState = EspState.Connecting(ssid)) }

        // WifiNetworkSpecifier requires API 29+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.update { it.copy(espState = EspState.Error("需要 Android Q(API 29) 及以上以使用 WifiNetworkSpecifier")) }
            return
        }

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
                    _state.update { it.copy(espState = EspState.Connected(network, null)) }

                    // 获取 link properties（需要 ACCESS_NETWORK_STATE）
                    var gateway: String? = null
                    if (app.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
                        val lp = connectivityManager.getLinkProperties(network)
                        gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                            ?: lp?.linkAddresses?.firstOrNull()?.address?.hostAddress
                    } else {
                        _state.update { it.copy(espState = EspState.Error("需要网络状态权限以获取链路信息")) }
                    }

                    _state.update { it.copy(espState = EspState.Connected(network, gateway)) }

                    // 1) 如果得到了 gateway 很可能就是设备 IP（esp32 常见） -> 直接试 HTTP
                    if (!gateway.isNullOrBlank()) {
                        val found = probeHttpOnIp(gateway)
                        if (found) return@launch
                    }

                    // 2) 如果没有网关或网关不可用，做子网扫描（并发 HTTP 探测常见端口）
                    // 为了保证探测走这个 network，临时 bind process 到 network（简单做法）
                    // 注意：这会影响本进程的所有网络请求，完成后请解绑
                    connectivityManager.bindProcessToNetwork(network)
                    try {
                        val success = scanSubnetAndFindDevice(network)
                        if (!success) {
                            _state.update { it.copy(espState = EspState.Error("在子网内未发现设备")) }
                        }
                    } finally {
                        // 解绑
                        connectivityManager.bindProcessToNetwork(null)
                    }
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                viewModelScope.launch { _state.update { it.copy(espState = EspState.Error("系统无法提供所请求的网络（onUnavailable）")) } }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                viewModelScope.launch { _state.update { it.copy(espState = EspState.Error("临时网络已断开")) } }
            }
        }

        networkCallback = cb
        connectivityManager.requestNetwork(nr, cb)
    }

    // 直接尝试对单个 IP 发起 HTTP 请求
    private suspend fun probeHttpOnIp(ip: String, port: Int = 80, path: String = "/"): Boolean {
        return withContext(Dispatchers.Default) {
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
                        _state.update { it.copy(espState = EspState.DeviceFound(ip, body?.take(500))) }
                        return@withContext true
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
            return@withContext false
        }
    }

    // 子网扫描：计算子网范围并并发试 HTTP（端口 80 / 8080）—— 并发受限以避免阻塞系统
    private suspend fun scanSubnetAndFindDevice(network: Network): Boolean {
        // 尝试从 linkProperties 获取本地地址与 prefix
        val lp = try { connectivityManager.getLinkProperties(network) } catch (_: SecurityException) { null } ?: return false
        val linkAddr = lp.linkAddresses.firstOrNull() ?: return false
        val addr = linkAddr.address ?: return false
        val prefixLen = linkAddr.prefixLength
        val ips = generateIpsFromPrefix(addr, prefixLen)

        val total = ips.size
        var checked = 0
        _state.update { it.copy(espState = EspState.DiscoveryProgress(checked, total)) }

        val semaphore = Semaphore(60) // 限制并发
        val scope = CoroutineScope(Dispatchers.Default)
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
                                val found = probeHttpOnIp(ip, p)
                                if (found) return@async ip
                            }
                        } catch (_: Exception) {
                            // not responsive on this port
                        }
                    }
                } finally {
                    semaphore.release()
                    checked++
                    _state.update { it.copy(espState = EspState.DiscoveryProgress(checked, total)) }
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
        if (hostBits <= 0) return emptyList()
        // 为简单起见限制 hostBits（太大时只扫描最多 /16）
        val effectiveHostBits = if (hostBits > 16) 16 else hostBits
        val netPrefix = (ipInt shr effectiveHostBits) shl effectiveHostBits
        val size = 2.0.pow(effectiveHostBits.toDouble()).toInt()
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