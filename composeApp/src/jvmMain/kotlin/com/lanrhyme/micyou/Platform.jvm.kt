package com.lanrhyme.micyou

import java.net.InetAddress

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val type: PlatformType = PlatformType.Desktop
    override val ipAddress: String
        get() = try {
            InetAddress.getLocalHost().hostAddress
        } catch (e: Exception) {
            "Unknown"
        }
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun uninstallVBCable() {
    VBCableManager.uninstallVBCable()
}

