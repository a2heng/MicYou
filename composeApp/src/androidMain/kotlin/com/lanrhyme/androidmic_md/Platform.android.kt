package com.lanrhyme.androidmic_md

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val type: PlatformType = PlatformType.Android
    override val ipAddress: String = "Client"
}

actual fun getPlatform(): Platform = AndroidPlatform()
