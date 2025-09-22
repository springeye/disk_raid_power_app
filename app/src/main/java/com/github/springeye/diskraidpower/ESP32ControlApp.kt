package com.github.springeye.diskraidpower
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

@Composable
fun ESP32ControlScreen(
    viewModel: ESP32ControlViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val deviceData by viewModel.deviceData.collectAsState()
    val otaProgress by viewModel.otaProgress.collectAsState()
    val otaStatus by viewModel.otaStatus.collectAsState()
    val messages by viewModel.messages.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var controlCommand by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 选项卡
        TabRow(selectedTabIndex = selectedTab) {
            listOf("连接", "控制", "OTA", "日志").forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        when (selectedTab) {
            0 -> ConnectionTab(
                viewModel = viewModel,
                devices = devices,
                scanState = scanState,
                connectionState = connectionState,
                selectedDevice = selectedDevice,
                context = context
            )
            
            1 -> ControlTab(
                viewModel = viewModel,
                ssid = ssid,
                onSsidChange = { ssid = it },
                password = password,
                onPasswordChange = { password = it },
                controlCommand = controlCommand,
                onControlCommandChange = { controlCommand = it },
                wifiStatus = wifiStatus,
                deviceData = deviceData
            )
            
            2 -> OtaTab(
                viewModel = viewModel,
                otaProgress = otaProgress,
                otaStatus = otaStatus,
                context = context
            )
            
            3 -> LogTab(
                messages = messages,
                onClear = { viewModel.clearMessages() }
            )
        }
    }
}

@Composable
fun ConnectionTab(
    viewModel: ESP32ControlViewModel,
    devices: List<BluetoothDevice>,
    scanState: ScanState,
    connectionState: BluetoothState,
    selectedDevice: BluetoothDevice?,
    context: Context
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 扫描控制
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.scanDevices(context) },
                enabled = scanState != ScanState.SCANNING
            ) {
                Text(if (scanState == ScanState.SCANNING) "扫描中..." else "扫描设备")
            }
            
            if (scanState == ScanState.SCANNING) {
                Button(onClick = { viewModel.stopScan() }) {
                    Text("停止扫描")
                }
            }
            
            if (connectionState == BluetoothState.CONNECTED) {
                Button(
                    onClick = { viewModel.disconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("断开连接")
                }
            }
        }
        
        // 设备列表
        if (devices.isNotEmpty()) {
            Text("发现设备:", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(devices) { device ->
                    DeviceItem(
                        device = device,
                        isSelected = selectedDevice?.address == device.address,
                        onConnect = { viewModel.connectToDevice(device) }
                    )
                }
            }
        } else if (scanState == ScanState.SCANNING) {
            Text("扫描中...")
            CircularProgressIndicator()
        } else {
            Text("未发现设备，点击扫描按钮开始扫描")
        }
        
        // 连接状态
        ConnectionStatusIndicator(connectionState)
    }
}

@Composable
fun ControlTab(
    viewModel: ESP32ControlViewModel,
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    controlCommand: String,
    onControlCommandChange: (String) -> Unit,
    wifiStatus: String,
    deviceData: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(
        rememberScrollState()
    )) {
        // WiFi配置
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("WiFi配置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = ssid,
                    onValueChange = onSsidChange,
                    label = { Text("WiFi SSID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("WiFi密码") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.sendWifiConfig(ssid, password) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("配置WiFi")
                }
                if (wifiStatus.isNotEmpty()) {
                    Text("状态: $wifiStatus", color = Color.Gray)
                }
            }
        }
        
        // 设备控制
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("设备控制", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.readDeviceData() }) {
                        Text("读取数据")
                    }
                    Button(onClick = { viewModel.sendControlCommand("status") }) {
                        Text("获取状态")
                    }
                    Button(
                        onClick = { viewModel.sendControlCommand("restart") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("重启设备")
                    }
                }
                
                OutlinedTextField(
                    value = controlCommand,
                    onValueChange = onControlCommandChange,
                    label = { Text("控制命令") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.sendControlCommand(controlCommand) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("发送命令")
                }
            }
        }
        
        // 设备数据
        if (deviceData.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("设备数据", style = MaterialTheme.typography.titleMedium)
                    Text(deviceData, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun OtaTab(
    viewModel: ESP32ControlViewModel,
    otaProgress: Int,
    otaStatus: OtaState,
    context: Context
) {
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("固件更新", style = MaterialTheme.typography.titleLarge)
        
        // 文件选择
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                (context as Activity).startActivityForResult(intent, FILE_PICK_REQUEST)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择固件文件")
        }
        
        selectedFile?.let { file ->
            Text("已选择文件: ${file.lastPathSegment ?: "未知文件"}")
        }
        
        // OTA进度
        if (otaStatus != OtaState.IDLE) {
            Column {
                Text("OTA状态: ${otaStatus.name}")
                LinearProgressIndicator(
                    progress = otaProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("进度: $otaProgress%")
            }
        }
        
        // 开始更新按钮
        Button(
            onClick = {
                selectedFile?.let { viewModel.startOtaUpdate(context, it) }
            },
            enabled = selectedFile != null && otaStatus == OtaState.IDLE,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始OTA更新")
        }
    }
}

@Composable
fun LogTab(messages: List<String>, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("日志信息", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onClear) {
                Text("清空日志")
            }
        }
        
        LazyColumn {
            items(messages.reversed()) { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(4.dp)
                )
                Divider()
            }
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
    val connectionState by viewModel.connectionState.collectAsState()
    ConnectionStatusIndicator(connectionState)
}


// 常量
const val FILE_PICK_REQUEST = 1001