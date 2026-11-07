package com.example.adskipper.data

import android.content.Context

class AppSettings(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "ad_skipper_settings",
            Context.MODE_PRIVATE,
        )

    var masterEnabled: Boolean
        get() = preferences.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    private companion object {
        const val KEY_MASTER_ENABLED = "master_enabled"
    }
}
