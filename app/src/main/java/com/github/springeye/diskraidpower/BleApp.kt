package com.github.springeye.diskraidpower

import android.app.Application
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