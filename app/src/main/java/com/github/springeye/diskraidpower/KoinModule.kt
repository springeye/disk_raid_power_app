package com.github.springeye.diskraidpower

import android.app.Application
import android.bluetooth.BluetoothManager
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { androidContext().getSystemService(Application.BLUETOOTH_SERVICE) as BluetoothManager }
    viewModel { ESP32ControlViewModel(get()) }
}

