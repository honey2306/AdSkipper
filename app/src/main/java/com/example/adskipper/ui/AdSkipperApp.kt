package com.example.adskipper.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adskipper.MainActivity
import com.example.adskipper.RuleManagerActivity
import com.example.adskipper.UpdateActivity
import com.example.adskipper.data.AppSettings
import com.example.adskipper.data.SkipLog
import com.example.adskipper.data.SkipLogRepository
import com.example.adskipper.data.SkipStatus
import com.example.adskipper.util.AccessibilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Primary = Color(0xFF286B59)
private val PrimaryDark = Color(0xFF174C3E)
private val PageBackground = Color(0xFFF4F7F5)
private val SoftGreen = Color(0xFFE0EEE8)
private val PrimaryText = Color(0xFF17251F)
private val SecondaryText = Color(0xFF6B7872)
private val Warning = Color(0xFF9A5B35)

@Composable
fun AdSkipperApp(
    activity: MainActivity,
    refreshTick: Int,
) {
    val settings = remember { AppSettings(activity) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("状态", "概览")

    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Primary,
                onPrimary = Color.White,
                primaryContainer = SoftGreen,
                onPrimaryContainer = PrimaryDark,
                background = PageBackground,
                surface = Color.White,
                onSurface = PrimaryText,
            ),
    ) {
        Scaffold(containerColor = PageBackground) { contentPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(horizontal = 20.dp),
            ) {
                Text(
                    "开屏跳过",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Primary,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = FontWeight.SemiBold) },
                            selectedContentColor = Primary,
                            unselectedContentColor = SecondaryText,
                        )
                    }
                }
                when (selectedTab) {
                    0 -> StatusScreen(activity, settings, refreshTick)
                    else -> OverviewScreen(activity, refreshTick)
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    activity: MainActivity,
    settings: AppSettings,
    refreshTick: Int,
) {
    var serviceEnabled by remember { mutableStateOf(false) }
    var masterEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(refreshTick) {
        serviceEnabled = AccessibilityStatus.isServiceEnabled(activity)
        masterEnabled = settings.masterEnabled
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(if (serviceEnabled) Primary else Warning, CircleShape),
                    )
                    Text(
                        if (serviceEnabled) "服务运行中" else "服务未开启",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    Text(
                        if (serviceEnabled) "无障碍已开启" else "需要授权",
                        color = if (serviceEnabled) SecondaryText else Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!serviceEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    ) {
                        Text("开启无障碍服务")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "自动跳过",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = {
                        masterEnabled = it
                        settings.masterEnabled = it
                    },
                )
            }
        }

        Card(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    activity.startActivity(Intent(activity, RuleManagerActivity::class.java))
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("规则与订阅", modifier = Modifier.weight(1f))
                Text("›", color = SecondaryText, fontSize = 24.sp)
            }
        }

        Card(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    activity.startActivity(Intent(activity, UpdateActivity::class.java))
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("应用更新", modifier = Modifier.weight(1f))
                Text("›", color = SecondaryText, fontSize = 24.sp)
            }
        }

        Card(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("系统电池优化设置", modifier = Modifier.weight(1f))
                Text("›", color = SecondaryText, fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun OverviewScreen(
    activity: MainActivity,
    refreshTick: Int,
) {
    val repository = remember { SkipLogRepository(activity) }
    var logs by remember { mutableStateOf<List<SkipLog>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick, reload) {
        logs = withContext(Dispatchers.IO) { repository.recent() }
    }

    val successCount = logs.count { it.status == SkipStatus.SUCCESS }
    val attentionCount =
        logs.count {
            it.status == SkipStatus.FAILED || it.status == SkipStatus.CANCELLED
        }
    val noTargetCount = logs.count { it.status == SkipStatus.INFO }
    val clickAttempts = successCount + attentionCount
    val successRate = if (clickAttempts == 0) null else successCount * 100 / clickAttempts

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SoftGreen),
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text("累计确认跳过", color = PrimaryDark, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "$successCount",
                            color = PrimaryDark,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 54.sp,
                        )
                        Text(
                            "次",
                            color = PrimaryDark,
                            modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
                        )
                    }
                    Text(
                        if (successRate == null) "还没有点击记录" else "点击成功率 $successRate%",
                        color = Primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewMetric(
                    title = "需要关注",
                    value = "$attentionCount",
                    modifier = Modifier.weight(1f),
                )
                OverviewMetric(
                    title = "未找到目标",
                    value = "$noTargetCount",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (logs.isNotEmpty()) {
                    TextButton(onClick = {
                        repository.clear()
                        reload += 1
                    }) {
                        Text("清空")
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无记录", fontWeight = FontWeight.SemiBold)
                    Text(
                        "使用其他应用后，这里会显示跳过概览",
                        color = SecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            items(logs, key = { it.timestamp }) { log -> RecentLogRow(log) }
        }
    }
}

@Composable
private fun OverviewMetric(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, color = SecondaryText, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecentLogRow(log: SkipLog) {
    val (statusText, statusColor) =
        when (log.status) {
            SkipStatus.SUCCESS -> "已跳过" to Primary
            SkipStatus.FAILED -> "未跳过" to Warning
            SkipStatus.CANCELLED -> "点击取消" to Warning
            SkipStatus.INFO -> "未找到目标" to SecondaryText
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(8.dp).background(statusColor, CircleShape),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(log.appName, fontWeight = FontWeight.SemiBold)
                Text(
                    formatTime(log.timestamp),
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val pattern = if (DateUtils.isToday(timestamp)) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
