package com.example.adskipper.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.adskipper.service.SkipAdService

object AccessibilityStatus {
    fun isServiceEnabled(context: Context): Boolean {
        val enabledServices =
            Settings.Secure
                .getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
        if (enabledServices.isBlank()) return false

        val expected = ComponentName(context, SkipAdService::class.java)
        return enabledServices.split(':').any { value ->
            runCatching { ComponentName.unflattenFromString(value) == expected }.getOrDefault(false)
        }
    }
}
