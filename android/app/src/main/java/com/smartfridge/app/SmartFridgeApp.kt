package com.smartfridge.app

import android.app.Application
import com.smartfridge.app.core.SkinManager
import com.smartfridge.app.data.AppServices

class SmartFridgeApp : Application() {
    val services: AppServices by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()
        SkinManager.init(this) // 皮肤选择持久化
        com.smartfridge.app.domain.PreservationTable.init(this) // 保质期表 V4(xlsx权威表)
    }
}
