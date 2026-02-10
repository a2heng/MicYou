package com.lanrhyme.micyou

import android.content.Context
import android.content.SharedPreferences

object AndroidContext {
    var context: Context? = null
    
    fun init(ctx: Context) {
        context = ctx.applicationContext
    }
}

actual object SettingsFactory {
    actual fun getSettings(): Settings {
        val ctx = AndroidContext.context
            ?: throw IllegalStateException("AndroidContext not initialized. Call AndroidContext.init(context) in MainActivity.")
        return AndroidSettings(ctx)
    }
}

class AndroidSettings(context: Context) : Settings {
    private val prefs: SharedPreferences = context.getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return prefs.getFloat(key, defaultValue)
    }

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
}

