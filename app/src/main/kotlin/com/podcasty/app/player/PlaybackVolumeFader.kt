package com.podcasty.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One cancellable volume envelope. Sleep-timer attenuation is composed by the service. */
class PlaybackVolumeFader(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val onVolume: (Float) -> Unit,
) {
    private var job: Job? = null
    var gain: Float = 1f
        private set

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun setGain(value: Float) {
        cancel()
        gain = value.coerceIn(0f, 1f)
        onVolume(gain)
    }

    fun fadeTo(target: Float, durationMs: Int, onComplete: () -> Unit = {}) {
        cancel()
        val end = target.coerceIn(0f, 1f)
        if (durationMs <= 0 || gain == end) {
            setGain(end)
            onComplete()
            return
        }
        val initial = gain
        val start = nowMs()
        job = scope.launch {
            while (isActive) {
                val fraction = ((nowMs() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                // Smoothstep has zero slope at each end and never overshoots.
                val eased = fraction * fraction * (3f - 2f * fraction)
                gain = initial + (end - initial) * eased
                onVolume(gain)
                if (fraction >= 1f) {
                    onComplete()
                    break
                }
                delay(16L)
            }
        }
    }
}
