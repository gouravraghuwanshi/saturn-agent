package io.github.pimobile.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lists end with a spring instead of Android's stretch: past the top or bottom the content
 * follows your finger with growing resistance (it feels heavy), you get a tick the moment you
 * hit the edge, and it springs back with a small settle. A fling into the edge bounces too.
 */
class BounceFactory(private val scope: CoroutineScope, private val view: View, private val maxPx: Float) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = BounceOverscroll(scope, view, maxPx)
    override fun equals(other: Any?) = other is BounceFactory && other.scope == scope && other.view == view && other.maxPx == maxPx
    override fun hashCode() = 31 * (31 * scope.hashCode() + view.hashCode()) + maxPx.hashCode()
}

private class BounceOverscroll(private val scope: CoroutineScope, private val view: View, private val maxPx: Float) : OverscrollEffect {
    private val offset = Animatable(0f)
    private var vertical = true
    private var atEdge = false

    private fun main(o: Offset) = if (vertical) o.y else o.x
    private fun axis(v: Float) = if (vertical) Offset(0f, v) else Offset(v, 0f)

    private fun edgeTick() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override val isInProgress get() = offset.value != 0f || offset.isRunning

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (delta == Offset.Zero) return performScroll(delta)
        if (offset.value == 0f) vertical = abs(delta.y) >= abs(delta.x)
        val d = main(delta)
        // Pulling back while stretched: relax the spring before scrolling the content.
        var relaxed = 0f
        val cur = offset.value
        if (cur != 0f && d != 0f && sign(d) != sign(cur)) {
            val next = if (abs(d) >= abs(cur)) 0f else cur + d
            relaxed = next - cur
            scope.launch { offset.snapTo(next) }
            if (next == 0f) atEdge = false
        }
        val remaining = delta - axis(relaxed)
        val consumed = performScroll(remaining)
        val left = main(remaining - consumed)
        if (source == NestedScrollSource.UserInput && abs(left) > 0.5f) {
            if (!atEdge) { atEdge = true; edgeTick() }
            // Heavier the further it goes: resistance grows towards maxPx.
            val base = if (offset.value == 0f) 0f else offset.value
            val give = 0.5f * (1f - abs(base) / maxPx).coerceAtLeast(0.08f)
            val next = (base + left * give).coerceIn(-maxPx, maxPx)
            scope.launch { offset.snapTo(next) }
            return axis(relaxed) + consumed + axis(left)
        }
        return axis(relaxed) + consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val v0 = if (vertical) velocity.y else velocity.x
        // Released while stretched: spring home (keeping some of the throw).
        if (offset.value != 0f) {
            offset.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), initialVelocity = v0 * 0.25f)
            atEdge = false
            return
        }
        val leftover = performFling(velocity)
        val v = if (vertical) leftover.y else leftover.x
        if (abs(v) > 300f) {
            // The fling ran into the edge: a short bounce out and back.
            edgeTick()
            offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialVelocity = (v * 0.22f).coerceIn(-5000f, 5000f))
        }
        atEdge = false
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val p = measurable.measure(constraints)
            return layout(p.width, p.height) {
                p.placeWithLayer(0, 0) { if (vertical) translationY = offset.value else translationX = offset.value }
            }
        }
    }
}
