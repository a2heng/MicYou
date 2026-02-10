package com.lanrhyme.micyou

enum class PlatformType {
    Android, Desktop
}

interface Platform {
    val name: String
    val type: PlatformType
    val ipAddress: String
}

expect fun getPlatform(): Platform

expect fun uninstallVBCable()

