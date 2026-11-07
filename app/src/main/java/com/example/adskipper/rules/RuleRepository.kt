package com.example.adskipper.rules

import android.content.Context
import java.io.File

class RuleRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val importedFile = File(appContext.filesDir, RuleSubscriptionManager.IMPORTED_FILE)
    private val lock = Any()

    private val builtInRules: List<AppRule> by lazy {
        runCatching {
            val json =
                appContext.assets
                    .open("rules.json")
                    .bufferedReader()
                    .use { it.readText() }
            RulePackageCodec.parse(json)
        }.getOrDefault(emptyList())
    }

    @Volatile
    private var importedRules: List<AppRule> = emptyList()
    private var importedModifiedAt = -1L

    val rules: List<AppRule>
        get() {
            refreshImportedRules()
            return builtInRules + importedRules
        }

    fun refreshImportedRules() =
        synchronized(lock) {
            val modifiedAt = importedFile.lastModified()
            if (modifiedAt == importedModifiedAt) return@synchronized
            importedModifiedAt = modifiedAt
            importedRules =
                if (importedFile.exists()) {
                    runCatching { RulePackageCodec.parse(importedFile.readText()) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
        }
}
