package com.foodorder.staff.poll

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Visibility-aware polling loop with capped-backoff-on-failure. [onTick]
 * returns whether that attempt succeeded; a run of failures backs off up
 * to [maxIntervalMs], and a single success snaps the interval straight
 * back to [baseIntervalMs] so recovery after connectivity returns is
 * immediate rather than gradual.
 */
class AdaptivePoller(
    private val baseIntervalMs: Long,
    private val maxIntervalMs: Long = baseIntervalMs * 4,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** No-op if already running — guards against a second call (e.g. two
     *  LaunchedEffect recompositions) starting a duplicate loop. */
    fun start(scope: CoroutineScope, onTick: suspend () -> Boolean) {
        if (isRunning) return
        var consecutiveFailures = 0
        job = scope.launch {
            while (isActive) {
                val success = onTick()
                consecutiveFailures = if (success) 0 else consecutiveFailures + 1
                val interval = if (success) baseIntervalMs
                    else (baseIntervalMs shl consecutiveFailures.coerceAtMost(4)).coerceAtMost(maxIntervalMs)
                delay(interval)
            }
        }
    }

    /** Pauses the loop entirely — used when the screen/app goes into the
     *  background, so a hidden screen doesn't keep polling. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Cancels any pending wait and ticks immediately — used when the
     *  screen/app returns to the foreground. */
    fun refreshNow(scope: CoroutineScope, onTick: suspend () -> Boolean) {
        stop()
        start(scope, onTick)
    }
}
