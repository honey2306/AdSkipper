package com.example.adskipper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.pm.PackageInfoCompat
import com.example.adskipper.DemoAdActivity
import com.example.adskipper.data.AppSettings
import com.example.adskipper.data.SkipLog
import com.example.adskipper.data.SkipLogRepository
import com.example.adskipper.data.SkipStatus
import com.example.adskipper.rules.NodeSnapshot
import com.example.adskipper.rules.RuleEngine
import com.example.adskipper.rules.RuleMatch
import com.example.adskipper.rules.RuleRepository
import com.example.adskipper.rules.RuleSubscriptionManager
import com.example.adskipper.rules.ScanWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SkipAdService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: AppSettings
    private lateinit var rules: RuleRepository
    private lateinit var logs: SkipLogRepository

    private var foregroundPackage = ""
    private var currentActivity: String? = null
    private var windowStartedAt = 0L
    private var attemptCount = 0
    private var lastAttemptAt = 0L
    private var clickInFlight = false
    private var scanScheduled = false
    private var windowHadTarget = false
    private var noTargetReported = false
    private var matchedButRejected = false
    private var lastNodeDiagnostics = "尚未读取到控件"
    private var ocrDiagnostic = "OCR 待扫描"
    private var ocrInFlight = false
    private var lastOcrAt = 0L
    private var ocrAttempts = 0
    private var ocrJob: Job? = null
    private var ocrRecognizer: LocalOcrRecognizer? = null
    private val ocrTargets = mutableMapOf<CandidateSignature, OcrTarget>()
    private val successfulTargets = mutableSetOf<CandidateSignature>()

    private val scanRunnable =
        object : Runnable {
            override fun run() {
                scanScheduled = false
                scanForSkipButton()
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        rules = RuleRepository(this)
        logs = SkipLogRepository(this)
        resetWindow(rootInActiveWindow?.packageName?.toString().orEmpty())
        scheduleScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::settings.isInitialized || !settings.masterEnabled) return
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        if (packageName != foregroundPackage) resetWindow(packageName)

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val activityName = event.className?.toString()?.takeIf { it.isActivityName() }
            if (activityName != null && activityName != currentActivity) {
                currentActivity = activityName
                if (activityName.isLaunchActivity()) resetWindow(packageName, preserveActivity = true)
            }
        }

        if (shouldHandle(packageName) && ScanWindow.isActive(windowStartedAt, now)) {
            scheduleScan()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(scanRunnable)
        ocrJob?.cancel()
        ocrRecognizer?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resetWindow(
        packageName: String,
        preserveActivity: Boolean = false,
    ) {
        foregroundPackage = packageName
        if (!preserveActivity) currentActivity = null
        windowStartedAt = SystemClock.elapsedRealtime()
        attemptCount = 0
        lastAttemptAt = 0L
        successfulTargets.clear()
        windowHadTarget = false
        noTargetReported = false
        matchedButRejected = false
        lastNodeDiagnostics = "尚未读取到控件"
        ocrJob?.cancel()
        ocrInFlight = false
        lastOcrAt = 0L
        ocrAttempts = 0
        ocrDiagnostic = "OCR 待扫描"
        ocrTargets.clear()
        handler.removeCallbacks(scanRunnable)
        scanScheduled = false
    }

    private fun shouldHandle(packageName: String): Boolean {
        if (!settings.masterEnabled) return false
        if (packageName in blockedPackages) return false
        if (packageName == this.packageName) {
            return currentActivity == DemoAdActivity::class.java.name
        }
        return packageName.isNotBlank()
    }

    private fun scheduleScan(delayMillis: Long = 30L) {
        if (scanScheduled || clickInFlight) return
        scanScheduled = true
        handler.postDelayed(scanRunnable, delayMillis)
    }

    private fun scanForSkipButton() {
        val now = SystemClock.elapsedRealtime()
        if (!shouldHandle(foregroundPackage)) return
        if (!ScanWindow.isActive(windowStartedAt, now)) {
            if (ocrInFlight) scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS) else reportUnmatchedWindow()
            return
        }
        if (attemptCount >= ScanWindow.MAX_ATTEMPTS) {
            if (ocrInFlight) scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS) else reportUnmatchedWindow()
            return
        }
        if (clickInFlight || now - lastAttemptAt < 700L) {
            scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            lastNodeDiagnostics = "rootInActiveWindow=null"
            scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS)
            return
        }
        if (root.packageName?.toString() != foregroundPackage) {
            resetWindow(root.packageName?.toString().orEmpty())
            scheduleScan()
            return
        }

        val target = findBestTarget(root)
        if (target == null) {
            maybeStartOcr()
            scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS)
            return
        }

        windowHadTarget = true
        attemptCount += 1
        lastAttemptAt = now
        clickInFlight = true
        serviceScope.launch {
            try {
                attemptClick(target)
            } finally {
                clickInFlight = false
                scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS)
            }
        }
    }

    private fun findBestTarget(root: AccessibilityNodeInfo): Target? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: Target? = null
        var visited = 0
        val candidates = mutableListOf<String>()
        matchedButRejected = false

        while (queue.isNotEmpty() && visited < MAX_NODES_PER_SCAN) {
            val node = queue.removeFirst()
            visited += 1
            val snapshot = node.toSnapshot()
            val match =
                RuleEngine.match(
                    snapshot,
                    foregroundPackage,
                    currentActivity,
                    rules.rules,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                )
            if (match != null || snapshot.isDiagnosticCandidate()) {
                candidates.add(snapshot.toDiagnosticString(match?.kind?.name ?: "NO_MATCH"))
            }
            if (match != null) {
                if (!snapshot.isSafeSize()) {
                    matchedButRejected = true
                } else if (snapshot.signature() !in successfulTargets) {
                    val candidate = Target(node, snapshot, match)
                    if (candidate.match.kind.priority > (best?.match?.kind?.priority ?: 0)) {
                        best = candidate
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        lastNodeDiagnostics =
            buildString {
                append("visitedNodes=").append(visited)
                if (candidates.isEmpty()) {
                    append("\n没有可见的短文本、资源 ID 或可点击候选控件")
                } else {
                    candidates.take(MAX_DIAGNOSTIC_NODES).forEach { append("\n").append(it) }
                    if (candidates.size > MAX_DIAGNOSTIC_NODES) {
                        append("\n其余候选控件=").append(candidates.size - MAX_DIAGNOSTIC_NODES)
                    }
                }
            }
        return best
    }

    private fun maybeStartOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ocrDiagnostic = "OCR 未启用：需要 Android 11 或更高版本"
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (ocrInFlight || ocrAttempts >= MAX_OCR_ATTEMPTS || now - lastOcrAt < OCR_INTERVAL_MILLIS) return
        val recognizer = ocrRecognizer ?: LocalOcrRecognizer(this).also { ocrRecognizer = it }
        val packageAtStart = foregroundPackage
        ocrInFlight = true
        ocrAttempts += 1
        lastOcrAt = now
        ocrJob =
            serviceScope.launch {
                try {
                    val result = recognizer.findTargets()
                    ocrDiagnostic = result.diagnostic
                    if (packageAtStart != foregroundPackage) return@launch
                    val ocrTarget =
                        result.targets.maxWithOrNull(
                            compareBy<OcrTarget> { it.match.kind.priority }
                                .thenBy {
                                    if (it.bounds.centerX() > resources.displayMetrics.widthPixels / 2 &&
                                        it.bounds.centerY() < resources.displayMetrics.heightPixels / 2
                                    ) {
                                        1
                                    } else {
                                        0
                                    }
                                },
                        ) ?: return@launch
                    val snapshot = ocrTarget.toNodeSnapshot()
                    val signature = snapshot.signature()
                    ocrTargets.clear()
                    ocrTargets[signature] = ocrTarget
                    windowHadTarget = true
                    attemptCount += 1
                    lastAttemptAt = SystemClock.elapsedRealtime()
                    clickInFlight = true
                    try {
                        attemptClick(Target(null, snapshot, ocrTarget.match))
                    } finally {
                        clickInFlight = false
                    }
                } finally {
                    ocrInFlight = false
                    scheduleScan(ScanWindow.SCAN_INTERVAL_MILLIS)
                }
            }
    }

    private fun OcrTarget.toNodeSnapshot(): NodeSnapshot =
        NodeSnapshot(
            text = text,
            className = "ocr.TextLine",
            visibleToUser = true,
            enabled = true,
            clickable = true,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )

    private fun reportUnmatchedWindow() {
        if (windowHadTarget || noTargetReported) return
        noTargetReported = true
        val reason =
            when {
                lastNodeDiagnostics == "rootInActiveWindow=null" -> "ROOT_UNAVAILABLE"
                matchedButRejected -> "MATCH_REJECTED_BY_SAFETY"
                else -> "NO_RULE_MATCH"
            }
        recordResult(
            packageName = foregroundPackage,
            status = SkipStatus.INFO,
            method = "SCAN",
            detail = "扫描结束，未识别到可跳过的按钮（也可能本次没有广告）",
            reasonCode = reason,
            diagnostics = lastNodeDiagnostics + "\n" + ocrDiagnostic,
        )
    }

    private suspend fun attemptClick(target: Target) {
        val packageAtStart = foregroundPackage
        val signature = target.snapshot.signature()
        val clickPoint =
            target.match.clickAction?.resolve(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
        val bounds =
            if (clickPoint != null) {
                Rect(
                    clickPoint.first.toInt() - 1,
                    clickPoint.second.toInt() - 1,
                    clickPoint.first.toInt() + 1,
                    clickPoint.second.toInt() + 1,
                )
            } else {
                Rect(
                    target.snapshot.left,
                    target.snapshot.top,
                    target.snapshot.right,
                    target.snapshot.bottom,
                )
            }
        val trace = mutableListOf<String>()
        trace.add("target=" + target.snapshot.toDiagnosticString(target.match.kind.name))
        val ocrTarget = ocrTargets[signature]
        val clickMethod = if (ocrTarget == null) "GESTURE" else "OCR"

        if (ocrTarget != null) {
            trace.add("OCR localRecognition=true text=${ocrTarget.text}")
        } else if (target.match.clickAction != null) {
            trace.add("RULE clickAction=${target.match.clickAction}; skip node click")
        } else {
            val nodeClick = dispatchNodeClick(requireNotNull(target.node))
            trace.addAll(nodeClick.trace)
            if (nodeClick.dispatched) {
                val verification = verifyTargetDisappeared(packageAtStart, signature)
                trace.add("NODE verification=${verification.outcome} detail=${verification.detail}")
                if (verification.disappeared) {
                    recordResult(
                        packageAtStart,
                        SkipStatus.SUCCESS,
                        "NODE",
                        "已确认原跳过目标消失",
                        "VERIFIED_TARGET_DISAPPEARED",
                        trace.joinToString("\n"),
                    )
                    successfulTargets.add(signature)
                    return
                }
            }
        }

        val gestureResult = dispatchTap(bounds)
        trace.add("GESTURE dispatch=$gestureResult center=${bounds.centerX()},${bounds.centerY()}")
        when (gestureResult) {
            GestureResult.COMPLETED -> {
                val verification =
                    if (ocrTarget != null) {
                        verifyOcrTargetDisappeared(packageAtStart, ocrTarget)
                    } else {
                        verifyTargetDisappeared(packageAtStart, signature)
                    }
                trace.add("$clickMethod verification=${verification.outcome} detail=${verification.detail}")
                if (verification.disappeared) {
                    recordResult(
                        packageAtStart,
                        SkipStatus.SUCCESS,
                        clickMethod,
                        "已确认原跳过目标消失",
                        "VERIFIED_TARGET_DISAPPEARED",
                        trace.joinToString("\n"),
                    )
                    successfulTargets.add(signature)
                } else {
                    recordResult(
                        packageAtStart,
                        SkipStatus.FAILED,
                        clickMethod,
                        "手势完成后目标仍存在或无法验证",
                        verification.outcome,
                        trace.joinToString("\n"),
                    )
                }
            }

            GestureResult.CANCELLED -> {
                recordResult(
                    packageAtStart,
                    SkipStatus.CANCELLED,
                    clickMethod,
                    "系统取消了点击手势",
                    "GESTURE_CANCELLED",
                    trace.joinToString("\n"),
                )
            }

            GestureResult.REJECTED -> {
                recordResult(
                    packageAtStart,
                    SkipStatus.FAILED,
                    clickMethod,
                    "系统未接受点击手势",
                    "GESTURE_REJECTED",
                    trace.joinToString("\n"),
                )
            }
        }
    }

    private fun dispatchNodeClick(node: AccessibilityNodeInfo): NodeClickResult {
        val trace = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = node
        repeat(5) { depth ->
            val candidate = current
            if (candidate == null) {
                trace.add("NODE depth=$depth parent=null")
                return NodeClickResult(false, trace)
            }
            if (candidate.isEnabled && candidate.isClickable) {
                val accepted = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                trace.add("NODE depth=$depth ACTION_CLICK accepted=$accepted")
                return NodeClickResult(accepted, trace)
            }
            trace.add(
                "NODE depth=$depth notClickable enabled=${candidate.isEnabled} " +
                    "clickable=${candidate.isClickable} class=${candidate.className}",
            )
            current = candidate.parent
        }
        trace.add("NODE no clickable ancestor within 5 levels")
        return NodeClickResult(false, trace)
    }

    private suspend fun verifyOcrTargetDisappeared(
        packageAtStart: String,
        original: OcrTarget,
    ): VerificationResult {
        delay(550L)
        if (foregroundPackage != packageAtStart) {
            return VerificationResult(true, "VERIFIED_PACKAGE_CHANGED", "foreground=$foregroundPackage")
        }
        val recognizer =
            ocrRecognizer
                ?: return VerificationResult(false, "VERIFY_OCR_UNAVAILABLE", "recognizer=null")
        val result = recognizer.findTargets()
        ocrDiagnostic = "verification: ${result.diagnostic}"
        if (!result.completed) {
            return VerificationResult(false, "VERIFY_OCR_UNAVAILABLE", result.diagnostic)
        }
        val stillPresent =
            result.targets.any { candidate ->
                kotlin.math.abs(candidate.bounds.centerX() - original.bounds.centerX()) <= 48 &&
                    kotlin.math.abs(candidate.bounds.centerY() - original.bounds.centerY()) <= 48
            }
        return if (stillPresent) {
            VerificationResult(false, "OCR_TARGET_STILL_PRESENT", "原文字区域仍识别到跳过目标")
        } else {
            VerificationResult(true, "VERIFIED_OCR_TARGET_DISAPPEARED", "原文字区域已不再识别到跳过目标")
        }
    }

    private suspend fun verifyTargetDisappeared(
        packageAtStart: String,
        signature: CandidateSignature,
    ): VerificationResult {
        delay(550L)
        if (foregroundPackage != packageAtStart) {
            return VerificationResult(
                true,
                "VERIFIED_PACKAGE_CHANGED",
                "foreground=$foregroundPackage expected=$packageAtStart",
            )
        }
        val root =
            rootInActiveWindow
                ?: return VerificationResult(false, "VERIFY_ROOT_UNAVAILABLE", "rootInActiveWindow=null")
        if (root.packageName?.toString() != packageAtStart) {
            return VerificationResult(
                true,
                "VERIFIED_PACKAGE_CHANGED",
                "rootPackage=${root.packageName} expected=$packageAtStart",
            )
        }
        return if (containsTarget(root, signature)) {
            VerificationResult(false, "TARGET_STILL_PRESENT", "相同资源 ID 和位置的匹配节点仍存在")
        } else {
            VerificationResult(true, "VERIFIED_TARGET_DISAPPEARED", "原匹配节点已不在当前控件树")
        }
    }

    private fun containsTarget(
        root: AccessibilityNodeInfo,
        signature: CandidateSignature,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES_PER_SCAN) {
            val node = queue.removeFirst()
            visited += 1
            val snapshot = node.toSnapshot()
            if (snapshot.signature() == signature &&
                RuleEngine.match(
                    snapshot,
                    foregroundPackage,
                    currentActivity,
                    rules.rules,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                ) != null
            ) {
                return true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return false
    }

    private suspend fun dispatchTap(bounds: Rect): GestureResult {
        if (bounds.isEmpty) return GestureResult.REJECTED
        val path =
            Path().apply {
                moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        val gesture =
            GestureDescription
                .Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
                .build()

        return suspendCancellableCoroutine { continuation ->
            val accepted =
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(GestureResult.COMPLETED)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(GestureResult.CANCELLED)
                        }
                    },
                    handler,
                )
            if (!accepted && continuation.isActive) continuation.resume(GestureResult.REJECTED)
        }
    }

    private fun recordResult(
        packageName: String,
        status: SkipStatus,
        method: String,
        detail: String,
        reasonCode: String = "UNKNOWN",
        diagnostics: String = "",
    ) {
        val appName =
            runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        val appVersion =
            runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
            }.getOrDefault("unknown")
        val display = resources.displayMetrics
        val metadata =
            buildString {
                append("package=").append(packageName)
                append("\nactivity=").append(currentActivity ?: "unknown")
                append("\nappVersion=").append(appVersion)
                append("\nandroid=").append(android.os.Build.VERSION.RELEASE)
                append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
                append("\nscreen=").append(display.widthPixels).append("x").append(display.heightPixels)
                append(" density=").append(display.density)
                append("\nactiveRules=").append(rules.rules.size)
                append(" importedRules=").append(RuleSubscriptionManager(this@SkipAdService).currentInfo().importedCount)
                append("\nscanElapsedMs=").append(SystemClock.elapsedRealtime() - windowStartedAt)
                append(" attempt=").append(attemptCount).append("/").append(ScanWindow.MAX_ATTEMPTS)
            }
        logs.append(
            SkipLog(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                appName = appName,
                status = status,
                method = method,
                detail = detail,
                reasonCode = reasonCode,
                diagnostics = metadata + if (diagnostics.isBlank()) "" else "\n$diagnostics",
            ),
        )
    }

    private fun AccessibilityNodeInfo.toSnapshot(): NodeSnapshot {
        val rect = Rect()
        getBoundsInScreen(rect)
        val childText =
            (0 until minOf(childCount, 8))
                .mapNotNull { index ->
                    getChild(index)?.let { child ->
                        child.text?.toString() ?: child.contentDescription?.toString()
                    }
                }.filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        return NodeSnapshot(
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            combinedChildText = childText,
            resourceId = viewIdResourceName,
            className = className?.toString(),
            visibleToUser = isVisibleToUser,
            clickable = isClickable,
            enabled = isEnabled,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }

    private fun NodeSnapshot.isSafeSize(): Boolean {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false
        val display = resources.displayMetrics
        return width <= display.widthPixels * MAX_BUTTON_WIDTH_RATIO &&
            height <= display.heightPixels * MAX_BUTTON_HEIGHT_RATIO
    }

    private fun NodeSnapshot.isDiagnosticCandidate(): Boolean {
        if (!visibleToUser || className == "android.widget.EditText") return false
        return !text.isNullOrBlank() ||
            !contentDescription.isNullOrBlank() ||
            !resourceId.isNullOrBlank() ||
            clickable
    }

    private fun NodeSnapshot.toDiagnosticString(matchKind: String): String {
        fun clean(value: String?): String =
            value
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(48)
                ?.ifBlank { null } ?: "-"

        return "rule=$matchKind text=${clean(text)} desc=${clean(contentDescription)} " +
            "id=${clean(resourceId)} class=${clean(className)} " +
            "bounds=[$left,$top,$right,$bottom] visible=$visibleToUser " +
            "enabled=$enabled clickable=$clickable"
    }

    private fun NodeSnapshot.signature(): CandidateSignature =
        CandidateSignature(
            resourceId = resourceId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )

    private fun String?.isActivityName(): Boolean {
        if (this.isNullOrBlank()) return false
        return frameworkClassPrefixes.none { startsWith(it) }
    }

    private fun String.isLaunchActivity(): Boolean = launchActivityPattern.containsMatchIn(this) || this == DemoAdActivity::class.java.name

    private data class Target(
        val node: AccessibilityNodeInfo?,
        val snapshot: NodeSnapshot,
        val match: RuleMatch,
    )

    private data class CandidateSignature(
        val resourceId: String?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class NodeClickResult(
        val dispatched: Boolean,
        val trace: List<String>,
    )

    private data class VerificationResult(
        val disappeared: Boolean,
        val outcome: String,
        val detail: String,
    )

    private enum class GestureResult {
        COMPLETED,
        CANCELLED,
        REJECTED,
    }

    private companion object {
        const val MAX_NODES_PER_SCAN = 2_000
        const val MAX_DIAGNOSTIC_NODES = 12
        const val MAX_OCR_ATTEMPTS = 4
        const val OCR_INTERVAL_MILLIS = 2_200L
        const val MAX_BUTTON_WIDTH_RATIO = 0.75f
        const val MAX_BUTTON_HEIGHT_RATIO = 0.30f

        val launchActivityPattern =
            Regex(
                "splash|launcher|welcome|advert|ads?activity",
                RegexOption.IGNORE_CASE,
            )
        val frameworkClassPrefixes =
            listOf(
                "android.widget.",
                "android.view.",
                "androidx.",
                "com.android.internal.",
            )
        val blockedPackages =
            setOf(
                "android",
                "com.android.systemui",
                "com.android.settings",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
            )
    }
}
