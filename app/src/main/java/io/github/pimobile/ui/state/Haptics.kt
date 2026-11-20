package io.github.pimobile.ui.state

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Saturn's "feel" for work happening. Uses the activity's view, so it follows the phone's
 * touch-feedback setting, and only plays while Saturn is on screen (in the background the
 * notification already vibrates).
 */
object Haptics {
    /** Set by MainActivity while it's created. */
    @Volatile var view: View? = null
    private var lastTick = 0L

    private fun play(constant: Int) {
        if (!UiStore.foreground) return
        view?.performHapticFeedback(constant)
    }

    /** A tool call started: a light tick, at most ~6 a second. */
    fun tool() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 160) return
        lastTick = now
        play(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A tool call failed: a short "no". */
    fun toolFailed() = play(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** A run finished (or a task needs you): firm confirm for success, reject for failed/stopped. */
    fun finished(ok: Boolean) = play(when {
        Build.VERSION.SDK_INT < 30 -> HapticFeedbackConstants.LONG_PRESS
        ok -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.REJECT
    })
}
