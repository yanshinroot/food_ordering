package com.foodorder.staff.sound

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * New-order alert (vibration + tone). Deduping which order ids have
 * already been alerted for is the caller's responsibility (each screen
 * tracks its own "known ids" set, since Cashier and Kitchen watch
 * different status subsets) — this class only knows how to make noise,
 * once, when asked.
 */
object AlertPlayer {
    fun play(context: Context, scope: CoroutineScope, soundEnabled: Boolean) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 150, 450, 150, 650), -1))
        if (!soundEnabled) return
        scope.launch {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            try {
                repeat(3) {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 520)
                    delay(650)
                }
            } finally {
                tone.release()
            }
        }
    }
}

/** Tracks which order ids a screen has already alerted for, across polls,
 *  so a duplicate poll response never re-triggers the sound/vibration for
 *  the same order. */
class AlertDedupeGuard {
    private var knownIds: Set<Int>? = null

    /** Returns true exactly once per newly-seen id in [currentIds]; the very
     *  first call just primes the baseline without alerting (so app launch
     *  with 5 existing orders doesn't fire the alert 5 times). */
    fun hasNewArrivals(currentIds: Set<Int>): Boolean {
        val previous = knownIds
        knownIds = currentIds
        if (previous == null) return false
        return (currentIds - previous).isNotEmpty()
    }

    fun reset() {
        knownIds = null
    }
}
