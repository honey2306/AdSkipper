package com.example.adskipper.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val LATEST_UPDATE_URL =
    "https://github.com/honey2306/AdSkipper/releases/latest/download/update.json"
private const val LATEST_APK_URL =
    "https://github.com/honey2306/AdSkipper/releases/latest/download/AdSkipper.apk"
private const val MAX_METADATA_BYTES = 1024 * 1024
private const val MAX_APK_BYTES = 300L * 1024L * 1024L

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

data class UpdateCheckResult(
    val currentVersionName: String,
    val update: AppUpdateInfo?,
)

class AppUpdateManager(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    fun currentVersionName(): String = currentPackageInfo().versionName ?: "unknown"

    suspend fun check(): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val current = currentPackageInfo()
            val metadata =
                open(LATEST_UPDATE_URL).inputStream.buffered().use { input ->
                    val bytes = input.readLimited(MAX_METADATA_BYTES)
                    if (bytes.size > MAX_METADATA_BYTES) throw IOException("更新信息过大")
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
            val update =
                AppUpdateInfo(
                    versionCode = metadata.getLong("versionCode"),
                    versionName = metadata.getString("versionName"),
                    apkUrl = metadata.optString("apkUrl", LATEST_APK_URL),
                    sha256 = metadata.getString("sha256").lowercase(),
                    size = metadata.getLong("size"),
                    notes = metadata.optString("notes"),
                )
            require(update.versionCode > 0) { "更新版本号无效" }
            require(update.apkUrl.startsWith("https://")) { "APK 地址必须使用 HTTPS" }
            require(update.sha256.matches(Regex("[0-9a-f]{64}"))) { "APK 校验值无效" }
            require(update.size in 1..MAX_APK_BYTES) { "APK 大小无效" }
            UpdateCheckResult(
                currentVersionName = current.versionName ?: "unknown",
                update = update.takeIf { it.versionCode > PackageInfoCompat.getLongVersionCode(current) },
            )
        }

    suspend fun download(
        update: AppUpdateInfo,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val baseDirectory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
            val directory = File(baseDirectory, "updates")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建更新目录")
            val target = File(directory, "AdSkipper-${update.versionName}.apk")
            if (target.exists()) {
                if (runCatching { verifyApk(target, update) }.isSuccess) return@withContext target
                target.delete()
            }
            val partial = File(directory, "${target.name}.part")
            partial.delete()
            try {
                val connection = open(update.apkUrl)
                if (connection.contentLengthLong > MAX_APK_BYTES) throw IOException("安装包超过大小限制")
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_APK_BYTES) throw IOException("安装包超过大小限制")
                            output.write(buffer, 0, count)
                            if (downloaded - lastReported >= 256 * 1024 || downloaded == update.size) {
                                onProgress(downloaded, update.size)
                                lastReported = downloaded
                            }
                        }
                    }
                }
                if (!partial.renameTo(target)) throw IOException("无法保存安装包")
                verifyApk(target, update)
                target
            } catch (error: Exception) {
                partial.delete()
                target.delete()
                throw error
            }
        }

    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        )

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun releasesIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/honey2306/AdSkipper/releases/latest"))

    private fun verifyApk(
        file: File,
        update: AppUpdateInfo,
    ) {
        require(file.length() == update.size) { "安装包大小校验失败" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(update.sha256, ignoreCase = true)) { "SHA-256 校验失败" }

        val archive = archivePackageInfo(file)
        require(archive.packageName == appContext.packageName) { "安装包包名不一致" }
        require(signatures(archive) == signatures(currentPackageInfo())) {
            "安装包签名与当前应用不一致，已拒绝安装"
        }
    }

    private fun signatures(info: PackageInfo): Set<String> {
        val values =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures.orEmpty()
            }
        return values
            .map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
            .also { require(it.isNotEmpty()) { "无法读取安装包签名" } }
    }

    private fun currentPackageInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }

    private fun archivePackageInfo(file: File): PackageInfo {
        val flags =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        return appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("无法解析安装包")
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (total <= limit) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            require(current.protocol == "https") { "更新地址必须使用 HTTPS" }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AdSkipper-Android/${currentVersionName()}")
            connection.setRequestProperty("Accept", "application/octet-stream")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    return connection
                }

                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw IOException("更新服务器重定向无效")
                    current = URL(current, location)
                    connection.disconnect()
                }

                else -> {
                    throw IOException("更新服务器返回 HTTP ${connection.responseCode}")
                }
            }
        }
        throw IOException("更新服务器重定向次数过多")
    }
}
