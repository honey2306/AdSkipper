package com.example.adskipper

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class DemoAdActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("ocr", false)) {
            setContentView(CanvasSkipView(this))
            return
        }
        setContent {
            MaterialTheme {
                var skipped by remember { mutableStateOf(false) }
                var countdown by remember { mutableIntStateOf(5) }

                LaunchedEffect(skipped) {
                    while (!skipped && countdown > 0) {
                        delay(1_000)
                        countdown -= 1
                    }
                    if (countdown == 0) skipped = true
                }

                if (skipped) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7F6)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("测试首页", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                if (countdown == 0) "倒计时结束" else "跳过按钮已被点击",
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF183D37))) {
                        Text(
                            "内置测试广告",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        Text(
                            "跳过 $countdown",
                            color = Color.White,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(24.dp)
                                    .background(Color(0xAA000000), RoundedCornerShape(20.dp))
                                    .clickable { skipped = true }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    private class CanvasSkipView(
        activity: ComponentActivity,
    ) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var countdown = 15
        private var skipped = false
        private val countdownTask =
            object : Runnable {
                override fun run() {
                    countdown -= 1
                    if (countdown <= 0) skipped = true
                    invalidate()
                    if (!skipped) postDelayed(this, 1_000L)
                }
            }

        init {
            isClickable = true
            setOnClickListener {
                skipped = true
                invalidate()
            }
            postDelayed(countdownTask, 1_000L)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(if (skipped) 0xFFF5F7F6.toInt() else 0xFF183D37.toInt())
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 54f
            paint.color = if (skipped) 0xFF172B26.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawText(if (skipped) "测试首页" else "内置 OCR 测试广告", width / 2f, height / 2f, paint)
            if (!skipped) {
                val label = "跳过 $countdown"
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = 52f
                canvas.drawText(label, width - 70f, 180f, paint)
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(countdownTask)
            super.onDetachedFromWindow()
        }
    }
}
