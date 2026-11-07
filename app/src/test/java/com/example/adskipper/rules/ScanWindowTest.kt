package com.example.adskipper.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanWindowTest {
    @Test
    fun `scan window is active only within configured duration`() {
        val start = 10_000L
        assertTrue(ScanWindow.isActive(start, start))
        assertTrue(ScanWindow.isActive(start, start + ScanWindow.DURATION_MILLIS))
        assertFalse(ScanWindow.isActive(start, start + ScanWindow.DURATION_MILLIS + 1))
    }

    @Test
    fun `uninitialized or invalid windows do not scan`() {
        assertFalse(ScanWindow.isActive(0L, 1_000L))
        assertFalse(ScanWindow.isActive(10_000L, 9_000L))
    }
}
