package com.github.springeye.diskraidpower
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import org.koin.androidx.compose.koinViewModel

// 权限相关UI状态
data class PermissionUiState(
    val showDialog: Boolean = false,
    val permanentlyDenied: Boolean = false
)

data class ESP32ControlUiState(
    val selectedTab: Int = 0,
    val ssid: String = "",
    val password: String = "",
    val controlCommand: String = "",
    val selectedFile: Uri? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ESP32ControlApp() {
    val viewModel: ESP32ControlViewModel = koinViewModel()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ConnectionStatus(viewModel)
                }
            )
        }
    ) { innerPadding ->
        ESP32ControlScreen(
            viewModel = viewModel,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ESP32ControlScreen(
    viewModel: ESP32ControlViewModel,
    modifier: Modifier = Modifier
) {
    val vmState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(ESP32ControlUiState()) }
    var permissionUiState by remember { mutableStateOf(PermissionUiState()) }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    )

    // 权限检查和请求逻辑
    fun checkAndRequestPermissions(onGranted: () -> Unit) {
        if (permissionsState.allPermissionsGranted) {
            onGranted()
        } else {
            permissionUiState = permissionUiState.copy(showDialog = true)
        }
    }

    // 权限对话框
    if (permissionUiState.showDialog) {
        val deniedList = permissionsState.permissions.filter { !it.status.isGranted }
        val permanentlyDenied = deniedList.any { it.status.shouldShowRationale.not() }
        AlertDialog(
            onDismissRequest = { permissionUiState = permissionUiState.copy(showDialog = false) },
            title = { Text("需要蓝牙权限") },
            text = {
                if (permanentlyDenied) {
                    Text("您已永久拒绝蓝牙相关权限，必须前往设置页面手动授权，否则无法使用低功耗蓝牙功能。")
                } else {
                    Text("应用需要低功耗蓝牙相关权限才能正常工作，请授权。")
                }
            },
            confirmButton = {
                if (permanentlyDenied) {
                    TextButton(onClick = {
                        permissionUiState = permissionUiState.copy(showDialog = false)
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("去设置")
                    }
                } else {
                    TextButton(onClick = {
                        permissionUiState = permissionUiState.copy(showDialog = false)
                        permissionsState.launchMultiplePermissionRequest()
                    }) {
                        Text("授权")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionUiState = permissionUiState.copy(showDialog = false) }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 选项卡
        TabRow(selectedTabIndex = uiState.selectedTab) {
            listOf("连接", "控制", "OTA", "日志").forEachIndexed { index, title ->
                Tab(
                    selected = uiState.selectedTab == index,
                    onClick = { uiState = uiState.copy(selectedTab = index) },
                    text = { Text(title) }
                )
            }
        }
        when (uiState.selectedTab) {
            0 -> ConnectionTab(
                devices = vmState.devices,
                scanState = vmState.scanState,
                connectionState = vmState.connectionState,
                selectedDevice = vmState.selectedDevice,
                onScan = {
                    checkAndRequestPermissions { viewModel.scanDevices(context) }
                },
                onStopScan = { viewModel.stopScan() },
                onConnect = {
                    checkAndRequestPermissions { viewModel.connectToDevice(it) }
                },
                onDisconnect = { viewModel.disconnect() }
            )
            1 -> ControlTab(
                ssid = uiState.ssid,
                onSsidChange = { uiState = uiState.copy(ssid = it) },
                password = uiState.password,
                onPasswordChange = { uiState = uiState.copy(password = it) },
                controlCommand = uiState.controlCommand,
                onControlCommandChange = { uiState = uiState.copy(controlCommand = it) },
                wifiStatus = vmState.wifiStatus,
                deviceData = vmState.deviceData,
                onSendWifiConfig = { viewModel.sendWifiConfig(uiState.ssid, uiState.password) },
                onSendControlCommand = { viewModel.sendControlCommand(uiState.controlCommand) },
                onReadDeviceData = { viewModel.readDeviceData() },
                onQuickCommand = { viewModel.sendControlCommand(it) }
            )
            2 -> OtaTab(
                otaProgress = vmState.otaProgress,
                otaStatus = vmState.otaStatus,
                selectedFile = uiState.selectedFile,
                onFileSelected = { uiState = uiState.copy(selectedFile = it) },
                onStartOta = { file -> file?.let { viewModel.startOtaUpdate(context, it) } }
            )
            3 -> LogTab(
                messages = vmState.messages,
                onClear = { viewModel.clearMessages() }
            )
        }
    }
}

@Composable
fun ConnectionTab(
    devices: List<BluetoothDevice>,
    scanState: ScanState,
    connectionState: BluetoothState,
    selectedDevice: BluetoothDevice?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 扫描控制
        ScanControlRow(scanState, connectionState, onScan, onStopScan, onDisconnect)

        // 设备列表
        DeviceList(devices, selectedDevice, onConnect)

        // 连接状态
        ConnectionStatusIndicator(connectionState)
    }
}

@Composable
fun ScanControlRow(
    scanState: ScanState,
    connectionState: BluetoothState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onScan, enabled = scanState != ScanState.SCANNING) {
            Text(if (scanState == ScanState.SCANNING) "扫描中..." else "扫描设备")
        }
        if (scanState == ScanState.SCANNING) {
            Button(onClick = onStopScan) { Text("停止扫描") }
        }
        if (connectionState == BluetoothState.CONNECTED) {
            Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("断开连接")
            }
        }
    }
}

@Composable
fun DeviceList(
    devices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    onConnect: (BluetoothDevice) -> Unit
) {
    if (devices.isNotEmpty()) {
        Text("发现设备:", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(devices) { device ->
                DeviceItem(
                    device = device,
                    isSelected = selectedDevice?.address == device.address,
                    onConnect = { onConnect(device) }
                )
            }
        }
    } else {
        Text("未发现设备，点击扫描按钮开始扫描")
    }
}

@Composable
fun ControlTab(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    controlCommand: String,
    onControlCommandChange: (String) -> Unit,
    wifiStatus: String,
    deviceData: String,
    onSendWifiConfig: () -> Unit,
    onSendControlCommand: () -> Unit,
    onReadDeviceData: () -> Unit,
    onQuickCommand: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        // WiFi配置
        WiFiConfigForm(ssid, onSsidChange, password, onPasswordChange, wifiStatus, onSendWifiConfig)

        // 设备控制
        DeviceControlForm(controlCommand, onControlCommandChange, onSendControlCommand, onReadDeviceData, onQuickCommand)

        // 设备数据
        if (deviceData.isNotEmpty()) {
            DeviceDataCard(deviceData)
        }
    }
}

@Composable
fun WiFiConfigForm(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    wifiStatus: String,
    onSendWifiConfig: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WiFi配置", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = ssid, onValueChange = onSsidChange, label = { Text("WiFi SSID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = onPasswordChange, label = { Text("WiFi密码") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSendWifiConfig, modifier = Modifier.fillMaxWidth()) { Text("配置WiFi") }
            if (wifiStatus.isNotEmpty()) { Text("状态: $wifiStatus", color = Color.Gray) }
        }
    }
}

@Composable
fun DeviceControlForm(
    controlCommand: String,
    onControlCommandChange: (String) -> Unit,
    onSendControlCommand: () -> Unit,
    onReadDeviceData: () -> Unit,
    onQuickCommand: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设备控制", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReadDeviceData) { Text("读取数据") }
                Button(onClick = { onQuickCommand("status") }) { Text("获取状态") }
                Button(onClick = { onQuickCommand("restart") }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("重启设备") }
            }
            OutlinedTextField(value = controlCommand, onValueChange = onControlCommandChange, label = { Text("控制命令") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSendControlCommand, modifier = Modifier.fillMaxWidth()) { Text("发送命令") }
        }
    }
}

@Composable
fun DeviceDataCard(deviceData: String) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设备数据", style = MaterialTheme.typography.titleMedium)
            Text(deviceData, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun OtaTab(
    otaProgress: Int,
    otaStatus: OtaState,
    selectedFile: Uri?,
    onFileSelected: (Uri?) -> Unit,
    onStartOta: (Uri?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("固件更新", style = MaterialTheme.typography.titleLarge)

        // 文件选择
        FilePickerButton(onFileSelected)

        selectedFile?.let { file -> Text("已选择文件: ${file.lastPathSegment ?: "未知文件"}") }

        // OTA进度
        if (otaStatus != OtaState.IDLE) {
            OtaProgressIndicator(otaStatus, otaProgress)
        }

        // 开始更新按钮
        Button(onClick = { onStartOta(selectedFile) }, enabled = selectedFile != null && otaStatus == OtaState.IDLE, modifier = Modifier.fillMaxWidth()) {
            Text("开始OTA更新")
        }
    }
}

@Composable
fun FilePickerButton(onFileSelected: (Uri?) -> Unit) {
    val context = LocalContext.current
    Button(
        onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            (context as Activity).startActivityForResult(intent, FILE_PICK_REQUEST)
            // 文件选择结果需在 Activity 回调中处理并调用 onFileSelected
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("选择固件文件")
    }
}

@Composable
fun OtaProgressIndicator(otaStatus: OtaState, otaProgress: Int) {
    Column {
        Text("OTA状态: ${otaStatus.name}")
        LinearProgressIndicator(progress = otaProgress / 100f, modifier = Modifier.fillMaxWidth())
        Text("进度: $otaProgress%")
    }
}

@Composable
fun LogTab(messages: List<String>, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("日志信息", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onClear) { Text("清空日志") }
        }
        LogList(messages)
    }
}

@Composable
fun LogList(messages: List<String>) {
    LazyColumn {
        items(messages.reversed()) { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(4.dp))
            Divider()
        }
    }
}

@Composable
fun DeviceItem(
    device: BluetoothDevice,
    isSelected: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        border = if (isSelected) BorderStroke(2.dp, Color.Blue) else null
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(device.name ?: "未知设备", style = MaterialTheme.typography.bodyLarge)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Button(onClick = onConnect, enabled = !isSelected) {
                Text(if (isSelected) "已连接" else "连接")
            }
        }
    }
}

@Composable
fun ConnectionStatusIndicator(state: BluetoothState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = when (state) {
                        BluetoothState.CONNECTED -> Color.Green
                        BluetoothState.CONNECTING -> Color.Yellow
                        BluetoothState.DISCONNECTED -> Color.Red
                    },
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when (state) {
                BluetoothState.CONNECTED -> "已连接"
                BluetoothState.CONNECTING -> "连接中"
                BluetoothState.DISCONNECTED -> "未连接"
            },
            color = when (state) {
                BluetoothState.CONNECTED -> Color.Green
                BluetoothState.CONNECTING -> Color.Blue
                BluetoothState.DISCONNECTED -> Color.Red
            }
        )
    }
}

@Composable
fun ConnectionStatus(viewModel: ESP32ControlViewModel) {
    val connectionState by viewModel.uiState.collectAsState()

    ConnectionStatusIndicator(connectionState.connectionState)
}


// 常量
const val FILE_PICK_REQUEST = 1001