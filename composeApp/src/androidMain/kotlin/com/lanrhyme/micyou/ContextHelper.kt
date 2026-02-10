package com.lanrhyme.micyou

import android.content.Context

object ContextHelper {
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getContext(): Context? {
        return applicationContext
    }
}

