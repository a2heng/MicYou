package com.lanrhyme.micyou

import java.util.prefs.Preferences

actual object SettingsFactory {
    actual fun getSettings(): Settings = JvmSettings()
}

class JvmSettings : Settings {
    private val prefs = Preferences.userNodeForPackage(JvmSettings::class.java)

    override fun getString(key: String, defaultValue: String): String {
        return prefs.get(key, defaultValue)
    }

    override fun putString(key: String, value: String) {
        prefs.put(key, value)
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return prefs.getFloat(key, defaultValue)
    }

    override fun putFloat(key: String, value: Float) {
        prefs.putFloat(key, value)
    }
}

