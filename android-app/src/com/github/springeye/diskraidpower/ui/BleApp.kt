package com.github.springeye.diskraidpower.ui

import android.app.Application
import com.github.springeye.diskraidpower.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BleApp: Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@BleApp)
            modules(appModule)
        }
    }
}