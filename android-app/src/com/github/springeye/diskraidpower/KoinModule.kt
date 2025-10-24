package com.github.springeye.diskraidpower

import android.app.Application
import android.bluetooth.BluetoothManager
import com.github.springeye.diskraidpower.http.createEspApi
import com.github.springeye.diskraidpower.ui.home.HomeViewModel
import de.jensklingenberg.ktorfit.Ktorfit
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { androidContext().getSystemService(Application.BLUETOOTH_SERVICE) as BluetoothManager }
    single{
        Ktorfit.Builder().baseUrl("https://swapi.dev/api/").build()
    }
    single {
        get<Ktorfit>().createEspApi()
    }
    viewModel { ESP32ControlViewModel(get()) }
    viewModel { HomeViewModel(get(),get()) }
}

