package com.github.springeye.diskraidpower

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.springeye.diskraidpower.BleApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ESP32ControlViewModel(
    application: Application
) : AndroidViewModel(application) {
    val TAG = "ESP32ControlViewModel"

    // 状态管理
    private val _connectionState = mutableStateOf(BluetoothState.DISCONNECTED)
    val connectionState: State<BluetoothState> = _connectionState

    private val _scanState = mutableStateOf(ScanState.IDLE)
    val scanState: State<ScanState> = _scanState

    private val _devices = mutableStateListOf<BluetoothDevice>()
    val devices: List<BluetoothDevice> = _devices

    private val _selectedDevice = mutableStateOf<BluetoothDevice?>(null)
    val selectedDevice: State<BluetoothDevice?> = _selectedDevice

    private val _wifiStatus = mutableStateOf("")
    val wifiStatus: State<String> = _wifiStatus

    private val _deviceData = mutableStateOf("")
    val deviceData: State<String> = _deviceData

    private val _otaProgress = mutableStateOf(0)
    val otaProgress: State<Int> = _otaProgress

    private val _otaStatus = mutableStateOf(OtaState.IDLE)
    val otaStatus: State<OtaState> = _otaStatus

    private val _messages = mutableStateListOf<String>()
    val messages: List<String> = _messages

    // 蓝牙相关对象
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    // 记录上次已连接设备的地址
    private var lastConnectedDeviceAddress: String? = null

    init {
        initializeBluetooth()
    }

    private fun initializeBluetooth() {
        val bluetoothManager =
            getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    // 扫描设备
    fun scanDevices(context: Context) {
        if (!checkBluetoothPermissions(context)) {
            _messages.add("缺少蓝牙权限")
            Log.d(TAG, "缺少蓝牙权限")
            return
        }
        _scanState.value = ScanState.SCANNING
        _devices.clear()
        _messages.add("开始扫描设备...")
        Log.d(TAG, "开始扫描设备...")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                result.device?.let { device ->
                    if (device.name?.contains("disk_raid_power", ignoreCase = true) == true) {
                        if (_devices.none { it.address == device.address }) {
                            _devices.add(device)
                            _messages.add("发现设备: ${device.name ?: "Unknown"} - ${device.address}")
                            Log.d(TAG, "发现设备: ${device.name ?: "Unknown"} - ${device.address}")
                        }
                        // 自动重连逻辑
                        if (lastConnectedDeviceAddress != null &&
                            device.address == lastConnectedDeviceAddress &&
                            _connectionState.value != BluetoothState.CONNECTED &&
                            _connectionState.value != BluetoothState.CONNECTING
                        ) {
                            _messages.add("检测到上次已连接设备，自动重连: ${device.address}")
                            Log.d(TAG, "检测到上次已连接设备，自动重连: ${device.address}")
                            connectToDevice(device)
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                _scanState.value = ScanState.IDLE
                _messages.add("扫描失败: $errorCode")
                Log.d(TAG, "扫描失败: $errorCode")
            }
        }
        scanCallback?.let { callback ->
            val filters = listOf(ScanFilter.Builder().build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(filters, settings, callback)

            // 10秒后停止扫描
//            viewModelScope.launch {
//                delay(10000)
//                stopScan()
//            }
        }
    }

    fun stopScan() {
        scanCallback?.let {
            bleScanner?.stopScan(it)
            _scanState.value = ScanState.IDLE
            _messages.add("停止扫描")
            Log.d(TAG, "停止扫描")
        }
    }

    // 连接设备
    fun connectToDevice(device: BluetoothDevice) {
        _selectedDevice.value = device
        _connectionState.value = BluetoothState.CONNECTING
        _messages.add("正在连接: ${device.name ?: device.address}")
        Log.d(TAG, "正在连接: ${device.name ?: device.address}")
        lastConnectedDeviceAddress = device.address

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _connectionState.value = BluetoothState.CONNECTED
                        _messages.add("连接成功")
                        Log.d(TAG, "连接成功")
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _connectionState.value = BluetoothState.DISCONNECTED
                        _messages.add("连接断开")
                        Log.d(TAG, "连接断开")
                        bluetoothGatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _messages.add("服务发现成功")
                    Log.d(TAG, "服务发现成功")
                    enableNotifications(gatt)
                } else {
                    _messages.add("服务发现失败: $status")
                    Log.d(TAG, "服务发现失败: $status")
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value?.toString(Charsets.UTF_8)
                data?.let { handleIncomingData(it) }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _messages.add("数据发送成功")
                    Log.d(TAG, "数据发送成功")
                } else {
                    _messages.add("数据发送失败: $status")
                    Log.d(TAG, "数据发送失败: $status")
                }
            }
        }

        bluetoothGatt = device.connectGatt(
            getApplication<BleApp>(),
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SERVICE_UUID))
        service?.let {
            val characteristic = it.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
            characteristic?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                _messages.add("已启用通知")
                Log.d(TAG, "已启用通知")
            } ?: run {
                _messages.add("未找到特征")
                Log.d(TAG, "未找到特征")
            }
        } ?: run {
            _messages.add("未找到服务")
            Log.d(TAG, "未找到服务")
        }
    }

    private fun handleIncomingData(data: String) {
        _messages.add("收到数据: $data")
        Log.d(TAG, "收到数据: $data")

        when {
            data.startsWith("WIFI:") -> _wifiStatus.value = data
            data.startsWith("STATUS:") -> _deviceData.value = data
            data.startsWith("OTA:") -> handleOtaResponse(data)
            else -> _deviceData.value = data
        }
    }

    // WiFi配置
    fun sendWifiConfig(ssid: String, password: String) {
        if (ssid.isBlank() || password.isBlank()) {
            _messages.add("SSID和密码不能为空")
            Log.d(TAG, "SSID和密码不能为空")
            return
        }

        val data = "WIFI:$ssid;$password"
        sendData(data)
        _messages.add("发送WiFi配置: $ssid")
        Log.d(TAG, "发送WiFi配置: $ssid")
    }

    // 控制命令
    fun sendControlCommand(command: String) {
        val fullCommand = when (command) {
            "restart" -> "CMD:RESTART"
            "status" -> "CMD:GET_STATUS"
            else -> "CMD:$command"
        }
        sendData(fullCommand)
        _messages.add("发送命令: $fullCommand")
        Log.d(TAG, "发送命令: $fullCommand")
    }

    fun setDeviceParameter(value: Int) {
        sendData("SET_PARAM:$value")
        _messages.add("设置参数: $value")
        Log.d(TAG, "设置参数: $value")
    }

    fun readDeviceData() {
        sendData("READ_DATA")
        _messages.add("请求读取数据")
        Log.d(TAG, "请求读取数据")
    }

    // OTA更新
    fun startOtaUpdate(context: Context, fileUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _otaStatus.value = OtaState.PREPARING
                val contentResolver = context.contentResolver
                val inputStream = contentResolver.openInputStream(fileUri)
                val file = readFileFromUri(context, fileUri)

                inputStream?.use { stream ->
                    val fileSize = stream.available().toLong()
                    _messages.add("开始OTA更新，文件大小: $fileSize bytes")
                    Log.d(TAG, "开始OTA更新，文件大小: $fileSize bytes")

                    // 发送开始命令
                    sendData("OTA:START:$fileSize")

                    // 等待设备准备就绪
                    delay(1000)

                    // 发送数据
                    val buffer = ByteArray(512)
                    var totalRead = 0
                    var bytesRead: Int

                    _otaStatus.value = OtaState.TRANSFERRING

                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        val data = "OTA:DATA" + String(buffer, 0, bytesRead, Charsets.UTF_8)
                        sendData(data)
                        totalRead += bytesRead

                        val progress = (totalRead * 100 / fileSize).toInt()
                        _otaProgress.value = progress

                        delay(20) // 控制发送速率
                    }

                    // 发送结束命令
                    sendData("OTA:END")
                    _otaStatus.value = OtaState.COMPLETED
                    _messages.add("OTA更新完成")
                    Log.d(TAG, "OTA更新完成")
                }
            } catch (e: Exception) {
                _otaStatus.value = OtaState.FAILED
                _messages.add("OTA更新失败: ${e.message}")
                Log.d(TAG, "OTA更新失败: ${e.message}")
            }
        }
    }

    private fun handleOtaResponse(response: String) {
        when {
            response == "OTA:READY" -> {
                _otaStatus.value = OtaState.READY
                _messages.add("设备准备就绪，开始传输")
                Log.d(TAG, "设备准备就绪，开始传输")
            }

            response == "OTA:SUCCESS" -> {
                _otaStatus.value = OtaState.COMPLETED
                _messages.add("OTA更新成功")
                Log.d(TAG, "OTA更新成功")
            }

            response.startsWith("OTA:FAIL") -> {
                _otaStatus.value = OtaState.FAILED
                _messages.add("OTA更新失败: $response")
                Log.d(TAG, "OTA更新失败: $response")
            }
        }
    }

    // 数据发送
    private fun sendData(data: String) {
        bluetoothGatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            service?.let {
                val characteristic = it.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
                characteristic?.let { char ->
                    char.value = data.toByteArray(Charsets.UTF_8)
                    gatt.writeCharacteristic(char)
                } ?: run {
                    _messages.add("未找到特征")
                    Log.d(TAG, "未找到特征")
                }
            } ?: run {
                _messages.add("未找到服务")
                Log.d(TAG, "未找到服务")
            }
        } ?: run {
            _messages.add("未连接设备")
            Log.d(TAG, "未连接设备")
        }
    }

    // 断开连接
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BluetoothState.DISCONNECTED
        _selectedDevice.value = null
        _messages.add("已断开连接")
        Log.d(TAG, "已断开连接")
        // 断开时不清除 lastConnectedDeviceAddress，便于自动重连
        // 自动重连：断开后自动开始扫描
        val context = getApplication<Application>().applicationContext
        scanDevices(context)
    }

    // 工具函数
    private fun checkBluetoothPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun readFileFromUri(context: Context, uri: Uri): ByteArray {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
        }
    }

    fun clearMessages() {
        _messages.clear()
    }

    companion object {
        private const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        private const val CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}

// 状态枚举
enum class BluetoothState { DISCONNECTED, CONNECTING, CONNECTED }
enum class ScanState { IDLE, SCANNING }
enum class OtaState { IDLE, PREPARING, READY, TRANSFERRING, COMPLETED, FAILED }