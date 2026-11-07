package com.example.adskipper.data

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class SkipStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    INFO,
}

data class SkipLog(
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    val status: SkipStatus,
    val method: String,
    val detail: String,
    val reasonCode: String = "NONE",
    val diagnostics: String = "",
)

class SkipLogRepository(
    context: Context,
) {
    private val file = File(context.applicationContext.filesDir, "skip_logs.jsonl")
    private val lock = Any()

    fun append(log: SkipLog) {
        synchronized(lock) {
            val json =
                JSONObject()
                    .put("timestamp", log.timestamp)
                    .put("packageName", log.packageName)
                    .put("appName", log.appName)
                    .put("status", log.status.name)
                    .put("method", log.method)
                    .put("detail", log.detail)
                    .put("reasonCode", log.reasonCode)
                    .put("diagnostics", log.diagnostics)
            file.appendText(json.toString() + "\n")
            trimIfNeeded()
        }
    }

    fun recent(limit: Int = 100): List<SkipLog> =
        synchronized(lock) {
            if (!file.exists()) return@synchronized emptyList()
            file
                .readLines()
                .takeLast(limit)
                .asReversed()
                .mapNotNull(::parse)
        }

    fun clear() =
        synchronized(lock) {
            file.delete()
            Unit
        }

    private fun parse(line: String): SkipLog? =
        runCatching {
            val json = JSONObject(line)
            SkipLog(
                timestamp = json.getLong("timestamp"),
                packageName = json.getString("packageName"),
                appName = json.optString("appName", json.getString("packageName")),
                status = SkipStatus.valueOf(json.getString("status")),
                method = json.getString("method"),
                detail = json.optString("detail"),
                reasonCode = json.optString("reasonCode", "UNKNOWN"),
                diagnostics = json.optString("diagnostics"),
            )
        }.getOrNull()

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size > MAX_LOGS) file.writeText(lines.takeLast(MAX_LOGS).joinToString("\n") + "\n")
    }

    private companion object {
        const val MAX_LOGS = 300
    }
}
