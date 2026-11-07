package com.example.adskipper.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.example.adskipper.rules.NodeSnapshot
import com.example.adskipper.rules.RuleEngine
import com.example.adskipper.rules.RuleMatch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import kotlin.coroutines.resume

data class OcrTarget(
    val text: String,
    val bounds: Rect,
    val match: RuleMatch,
)

data class OcrScanResult(
    val targets: List<OcrTarget> = emptyList(),
    val diagnostic: String,
    val completed: Boolean = false,
)

/** Android 11+ local OCR fallback. Screenshots stay in memory and are recycled immediately. */
class LocalOcrRecognizer(
    private val service: AccessibilityService,
) : Closeable {
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun findTargets(): OcrScanResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return OcrScanResult(diagnostic = "OCR 需要 Android 11 或更高版本")
        }
        val bitmap =
            withTimeoutOrNull(3_000L) { captureScreenshot() }
                ?: return OcrScanResult(diagnostic = "OCR 未获得屏幕截图或截图超时，控件规则不受影响")
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text =
                withTimeoutOrNull(5_000L) { process(image) }
                    ?: return OcrScanResult(diagnostic = "OCR 识别超时")
            val width = service.resources.displayMetrics.widthPixels
            val height = service.resources.displayMetrics.heightPixels

            fun convert(
                value: String,
                rawBounds: Rect?,
            ): OcrTarget? {
                rawBounds ?: return null
                val bounds =
                    Rect(
                        rawBounds.left * width / bitmap.width,
                        rawBounds.top * height / bitmap.height,
                        rawBounds.right * width / bitmap.width,
                        rawBounds.bottom * height / bitmap.height,
                    )
                if (bounds.width() < 2 || bounds.height() < 2) return null
                val normalized = RuleEngine.normalize(value)
                val hasAdContext = normalized.contains("广告") || normalized.any(Char::isDigit)
                if (bounds.centerY() >= height * 0.45f && !hasAdContext) return null
                val snapshot =
                    NodeSnapshot(
                        text = value,
                        className = "ocr.TextLine",
                        visibleToUser = true,
                        enabled = true,
                        clickable = true,
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                    )
                val match = RuleEngine.match(snapshot, "", null, emptyList(), width, height) ?: return null
                return OcrTarget(value, bounds, match)
            }

            // Lines are preferred over individual words and large blocks to reduce accidental matches.
            val lines = text.textBlocks.flatMap { block -> block.lines }
            val lineTargets = lines.mapNotNull { line -> convert(line.text, line.boundingBox) }
            val targets =
                lineTargets.ifEmpty {
                    lines.flatMap { line -> line.elements }.mapNotNull { element ->
                        convert(element.text, element.boundingBox)
                    }
                }
            OcrScanResult(
                targets = targets.distinctBy { RuleEngine.normalize(it.text) to it.bounds },
                diagnostic = "OCR blocks=${text.textBlocks.size} lines=${lines.size} matches=${targets.size}",
                completed = true,
            )
        } catch (error: Exception) {
            OcrScanResult(diagnostic = "OCR 失败：${error.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun process(image: InputImage): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            recognizer
                .process(image)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.cancel() }
        }

    private suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hardware =
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                            result.hardwareBuffer.close()
                            continuation.resume(copy)
                        }

                        override fun onFailure(errorCode: Int) {
                            continuation.resume(null)
                        }
                    },
                )
            }.onFailure { continuation.resume(null) }
        }
    }

    override fun close() = recognizer.close()
}
