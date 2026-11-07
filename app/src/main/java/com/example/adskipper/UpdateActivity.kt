package com.example.adskipper

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.adskipper.update.AppUpdateInfo
import com.example.adskipper.update.AppUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateActivity : ComponentActivity() {
    private lateinit var updates: AppUpdateManager
    private val state = mutableStateOf<UpdateUiState>(UpdateUiState.Checking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = AppUpdateManager(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFF286B59), background = Color(0xFFF4F7F5)),
            ) {
                UpdateScreen(
                    state = state.value,
                    currentVersion = updates.currentVersionName(),
                    onBack = ::finish,
                    onCheck = ::check,
                    onDownload = ::download,
                    onInstall = ::install,
                    onOpenReleases = { startActivity(updates.releasesIntent()) },
                )
            }
        }
        check()
    }

    private fun check() {
        state.value = UpdateUiState.Checking
        lifecycleScope.launch {
            try {
                val result = updates.check()
                state.value = result.update?.let(UpdateUiState::Available)
                    ?: UpdateUiState.UpToDate(result.currentVersionName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = UpdateUiState.Failure(error.message ?: "检查更新失败")
            }
        }
    }

    private fun download(update: AppUpdateInfo) {
        state.value = UpdateUiState.Downloading(update, 0L, update.size)
        lifecycleScope.launch {
            try {
                val file =
                    updates.download(update) { downloaded, total ->
                        withContext(Dispatchers.Main.immediate) {
                            state.value = UpdateUiState.Downloading(update, downloaded, total)
                        }
                    }
                state.value = UpdateUiState.Ready(update, file)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = UpdateUiState.Failure(error.message ?: "下载或校验失败")
            }
        }
    }

    private fun install(
        update: AppUpdateInfo,
        file: File,
    ) {
        if (!updates.canInstall()) {
            startActivity(updates.openInstallPermissionSettings())
            return
        }
        runCatching { startActivity(updates.installIntent(file)) }
            .onFailure {
                state.value = UpdateUiState.Failure("无法打开系统安装器")
            }
    }
}

private sealed interface UpdateUiState {
    data object Checking : UpdateUiState

    data class UpToDate(
        val version: String,
    ) : UpdateUiState

    data class Available(
        val update: AppUpdateInfo,
    ) : UpdateUiState

    data class Downloading(
        val update: AppUpdateInfo,
        val downloaded: Long,
        val total: Long,
    ) : UpdateUiState

    data class Ready(
        val update: AppUpdateInfo,
        val file: File,
    ) : UpdateUiState

    data class Failure(
        val message: String,
    ) : UpdateUiState
}

@Composable
private fun UpdateScreen(
    state: UpdateUiState,
    currentVersion: String,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (AppUpdateInfo, File) -> Unit,
    onOpenReleases: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                Text("‹ 返回")
            }
            Text("应用更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "当前版本 $currentVersion",
            color = Color(0xFF6B7872),
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    UpdateUiState.Checking -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("正在检查 GitHub Releases…")
                    }

                    is UpdateUiState.UpToDate -> {
                        Text("已是最新版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("当前版本 ${state.version}，无需更新。", color = Color(0xFF6B7872))
                        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
                    }

                    is UpdateUiState.Available -> {
                        Text(
                            "发现新版本 ${state.update.versionName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("安装包 ${Formatter.formatShortFileSize(context, state.update.size)}")
                        if (state.update.notes.isNotBlank()) {
                            Text("更新内容", fontWeight = FontWeight.SemiBold)
                            Text(state.update.notes)
                        }
                        Text("下载后将校验 SHA-256 和应用签名，再打开系统安装确认页。", color = Color(0xFF6B7872))
                        Button(onClick = { onDownload(state.update) }, modifier = Modifier.fillMaxWidth()) { Text("下载更新") }
                    }

                    is UpdateUiState.Downloading -> {
                        Text("正在下载 ${state.update.versionName}", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${Formatter.formatShortFileSize(context, state.downloaded)} / " +
                                Formatter.formatShortFileSize(context, state.total),
                        )
                    }

                    is UpdateUiState.Ready -> {
                        Text("下载和签名校验完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("点击安装后，请在系统页面确认更新。")
                        Button(onClick = { onInstall(state.update, state.file) }, modifier = Modifier.fillMaxWidth()) { Text("安装更新") }
                        Text("如果系统提示，请先允许“开屏跳过”安装未知应用，再返回点击安装。", color = Color(0xFF6B7872))
                    }

                    is UpdateUiState.Failure -> {
                        Text("暂时无法更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(state.message, color = Color(0xFF9A5B35))
                        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenReleases, modifier = Modifier.fillMaxWidth()) {
            Text("打开 GitHub 发布页")
        }
    }
}
