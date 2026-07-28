package com.foodorder.staff.kitchen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 4-second client-side hold-before-send pattern used by the verified
 * Kitchen Web UI, ported as-is: the transition is held for [holdMillis]
 * before actually being sent; tapping Undo just cancels the pending send.
 * Nothing is applied optimistically in the meantime — the order's status
 * on screen doesn't change until the server confirms it.
 *
 * Pulled out of KitchenHomeScreen as its own class (rather than inline
 * Compose state + LaunchedEffect) specifically so the token-invalidation
 * logic — "a second schedule() cancels the first; undo() before the delay
 * elapses means send never fires" — is unit-testable with
 * kotlinx-coroutines-test's virtual time, without any Compose test
 * infrastructure.
 */
class KitchenUndoScheduler(private val holdMillis: Long = 4_000L) {
    private var token = 0
    private var job: Job? = null

    /** True while a hold is pending (i.e. the Undo bar should be visible). */
    var isPending: Boolean = false
        private set

    fun schedule(scope: CoroutineScope, onFire: suspend () -> Unit) {
        token += 1
        val myToken = token
        isPending = true
        job?.cancel()
        job = scope.launch {
            delay(holdMillis)
            if (token == myToken) {
                isPending = false
                onFire()
            }
        }
    }

    /** Cancels the pending send. If called after the hold has already
     *  fired, this is a no-op — a real transition, once sent and accepted
     *  by the server, is never faked as reversed by this class. */
    fun undo() {
        token += 1
        isPending = false
        job?.cancel()
        job = null
    }
}
