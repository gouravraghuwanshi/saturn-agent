package io.github.pimobile.ui.state

/** Every vibration in the app goes through these, so Settings → Haptics can turn them all off. */
fun android.view.View.buzz(constant: Int): Boolean = UiStore.haptics && performHapticFeedback(constant)

fun androidx.compose.ui.hapticfeedback.HapticFeedback.buzz(type: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
    if (UiStore.haptics) performHapticFeedback(type)
}
