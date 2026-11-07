package com.example.adskipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adskipper.rules.RuleSubscriptionManager
import com.example.adskipper.rules.SubscriptionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuleManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RuleManagerScreen(this) }
    }
}

@Composable
private fun RuleManagerScreen(activity: ComponentActivity) {
    val manager = remember { RuleSubscriptionManager(activity) }
    var info by remember { mutableStateOf(manager.currentInfo()) }
    var url by remember { mutableStateOf(info.sourceUrl) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF286B59))) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = activity::finish, modifier = Modifier.align(Alignment.Start)) {
                Text("‹ 返回")
            }
            Text("规则与订阅", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE0EEE8)),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("已导入 ${info.importedCount} 条专用规则", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (info.updatedAt == 0L) "尚未导入订阅" else "更新于 ${formatDate(info.updatedAt)}",
                        color = Color(0xFF53675E),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("SKIP v3 订阅地址") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !updating,
                minLines = 2,
            )

            Button(
                onClick = {
                    updating = true
                    message = null
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                runCatching { manager.importFromUrl(url.trim()) }
                            }
                        result
                            .onSuccess {
                                info = it
                                message = "导入成功，新规则已即时生效"
                            }.onFailure {
                                message = "导入失败：${it.message ?: "请检查网络和订阅格式"}"
                            }
                        updating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !updating && url.isNotBlank(),
            ) {
                if (updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("下载并更新规则")
                }
            }

            message?.let { Text(it, color = if (it.startsWith("导入成功")) Color(0xFF286B59) else Color(0xFF9A4C38)) }

            TextButton(
                onClick = {
                    manager.clearImportedRules()
                    info = manager.currentInfo()
                    message = "已移除导入规则，恢复使用内置规则"
                },
                enabled = !updating && info.importedCount > 0,
            ) {
                Text("移除导入规则")
            }

            Text(
                "支持 SKIP v3 的 skipIds、skipTexts、skipBounds、activityName 和 click。规则仅在手动更新时联网下载。\n\n" +
                    "SKIP 及其默认规则采用 GPL-3.0，导入表示使用相应第三方规则，不代表这些规则已经针对当前设备和 App 版本验证。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7872),
            )
        }
    }
}

private fun formatDate(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
