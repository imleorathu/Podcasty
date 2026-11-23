package com.podcasty.app.player

import kotlin.math.sqrt

/** Requires two distinct acceleration peaks and a cooldown; ordinary movement is ignored. */
class ShakeSkipDetector {
    private var firstPeakMs = Long.MIN_VALUE
    private var lastSkipMs = Long.MIN_VALUE
    private var aboveThreshold = false

    fun reset() { firstPeakMs = Long.MIN_VALUE; aboveThreshold = false }

    fun sample(x: Float, y: Float, z: Float, nowMs: Long, sensitivity: Int): Boolean {
        val thresholdG = when (sensitivity) { 1 -> 3.2f; 2 -> 2.7f; 3 -> 2.2f; else -> return false }
        val accelerationG = sqrt(x * x + y * y + z * z) / 9.80665f
        val peak = accelerationG >= thresholdG
        val rising = peak && !aboveThreshold
        aboveThreshold = peak
        if (!rising || (lastSkipMs != Long.MIN_VALUE && nowMs - lastSkipMs < 1800)) return false
        if (firstPeakMs != Long.MIN_VALUE && nowMs - firstPeakMs in 80L..600L) {
            firstPeakMs = Long.MIN_VALUE
            lastSkipMs = nowMs
            return true
        }
        firstPeakMs = nowMs
        return false
    }
}
