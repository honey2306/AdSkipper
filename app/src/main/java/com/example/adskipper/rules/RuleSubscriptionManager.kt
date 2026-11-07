package com.example.adskipper.rules

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class SubscriptionInfo(
    val importedCount: Int,
    val updatedAt: Long,
    val sourceUrl: String,
)

class RuleSubscriptionManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("rule_subscription", Context.MODE_PRIVATE)

    fun currentInfo(): SubscriptionInfo =
        SubscriptionInfo(
            importedCount = preferences.getInt(KEY_COUNT, 0),
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
            sourceUrl = preferences.getString(KEY_URL, DEFAULT_SKIP_URL).orEmpty(),
        )

    fun importFromUrl(url: String): SubscriptionInfo {
        require(url.startsWith("https://")) { "只允许 HTTPS 订阅地址" }
        val text = download(url)
        val rules = SkipSubscriptionParser.parse(text)
        require(rules.isNotEmpty()) { "订阅中没有可执行的 SKIP v3 规则" }

        appContext.openFileOutput(IMPORTED_FILE, Context.MODE_PRIVATE).use {
            it.write(RulePackageCodec.encode(rules).toByteArray())
        }
        val info = SubscriptionInfo(rules.size, System.currentTimeMillis(), url)
        preferences
            .edit()
            .putInt(KEY_COUNT, rules.size)
            .putLong(KEY_UPDATED_AT, info.updatedAt)
            .putString(KEY_URL, url)
            .apply()
        return info
    }

    fun clearImportedRules() {
        appContext.deleteFile(IMPORTED_FILE)
        preferences
            .edit()
            .putInt(KEY_COUNT, 0)
            .putLong(KEY_UPDATED_AT, 0L)
            .apply()
    }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "AdSkipper/1.0")
            connection.setRequestProperty("Accept", "text/yaml,text/plain,application/json")
            connection.connect()
            check(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
            check(connection.url.protocol == "https") { "不允许跳转到非 HTTPS 地址" }
            check(connection.contentLength <= MAX_DOWNLOAD_BYTES) { "订阅文件过大" }
            val bytes =
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var remaining = MAX_DOWNLOAD_BYTES + 1
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.toByteArray()
                }
            check(bytes.size <= MAX_DOWNLOAD_BYTES) { "订阅文件超过 4 MB" }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val IMPORTED_FILE = "imported_rules.json"
        const val DEFAULT_SKIP_URL = "https://skip.guoxicheng.top/skip_config_v3.yaml"
        private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
        private const val KEY_COUNT = "count"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_URL = "url"
    }
}
