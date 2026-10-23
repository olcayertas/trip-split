package com.olcayertas.tripsplit.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowLooper

/**
 * Waits for [condition] while a background thread (Room, a coroutine) posts work to the main looper. Robolectric
 * runs the main looper in paused mode, so work posted from another thread runs only when the test idles the looper.
 * `waitUntil` alone can miss such a post.
 */
fun ComposeContentTestRule.awaitUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        ShadowLooper.idleMainLooper()
        waitForIdle()
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError("Condition still not satisfied after $timeoutMs ms")
}
