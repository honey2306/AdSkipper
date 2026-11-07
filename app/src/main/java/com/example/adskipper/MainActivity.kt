package com.example.adskipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.example.adskipper.ui.AdSkipperApp

class MainActivity : ComponentActivity() {
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdSkipperApp(activity = this, refreshTick = refreshTick.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue += 1
    }
}
