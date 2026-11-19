package io.github.pimobile.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android's half of Phone Control. While the user has it on in Accessibility
 * settings it hosts the local MCP server; it never acts on its own, only on
 * tool calls, and shows a Stop pill on screen while it does.
 */
class PhoneControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneControlService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile var lastEvent = 0L
        private set
    private var pill: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private val hidePill = Runnable { removePill() }

    override fun onServiceConnected() {
        instance = this
        PhoneControl.init(this)
        PhoneControl.loadPrefs()
        McpServer.start(this)
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }
    private fun shutdown() { instance = null; McpServer.stop(); main.post { removePill() } }
    override fun onInterrupt() {}

    /** Only timestamps are kept, to know when the screen has settled after an action. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEvent = SystemClock.uptimeMillis() }

    private val wm get() = getSystemService(WindowManager::class.java)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(gravity: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun bg(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }

    private fun label(text: String, size: Float, color: Int, mono: Boolean = false) = TextView(this).apply {
        this.text = text; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (mono) typeface = android.graphics.Typeface.MONOSPACE
    }

    /** Shows (or keeps) the "An agent is using your phone · Stop" pill; it hides itself after a quiet spell. */
    fun showPill() = main.post {
        main.removeCallbacks(hidePill)
        main.postDelayed(hidePill, 12_000)
        if (pill != null) return@post
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(0xF0141519.toInt(), 22, 0xFF2A2C33.toInt())
            setPadding(dp(14), dp(8), dp(8), dp(8))
            addView(View(context).apply { background = bg(0xFF6FB7CC.toInt(), 5) }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
            addView(label("An agent is using your phone", 13f, 0xFFE9E6DF.toInt()))
            addView(label("Stop", 13f, 0xFF0E1A1E.toInt()).apply {
                background = bg(0xFF6FB7CC.toInt(), 16); setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { PhoneControl.stopped = true; removePill() }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }
        val p = overlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { y = dp(40) }
        runCatching { wm.addView(row, p); pill = row; pillParams = p }
    }

    fun removePill() { main.removeCallbacks(hidePill); pill?.let { runCatching { wm.removeView(it) } }; pill = null }

    /** Lets a gesture pass through the pill instead of hitting it. */
    private fun pillTouchable(on: Boolean) = main.post {
        val v = pill ?: return@post
        val p = pillParams ?: return@post
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun gesture(path: Path, durationMs: Long): Boolean {
        pillTouchable(false)
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))).build()
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        pillTouchable(true)
        return ok
    }

    /**
     * Asks the user, on top of whatever app is open, whether the agent may do
     * [action]. Blocks until they answer; no answer within 45 s is "no"
     * (MCP clients usually give up on a call after 60 s).
     */
    fun confirm(action: String, where: String): Boolean {
        val latch = CountDownLatch(1)
        var allowed = false
        var card: View? = null
        main.post {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bg(0xFA141519.toInt(), 20, 0xFF28414A.toInt())
                setPadding(dp(20), dp(18), dp(20), dp(16))
                addView(label("AN AGENT WANTS TO", 11f, 0xFF6FB7CC.toInt(), mono = true))
                addView(label(action, 17f, 0xFFE9E6DF.toInt()).apply { setPadding(0, dp(6), 0, dp(2)) })
                addView(label(where, 13f, 0xFF8C8A85.toInt()))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    setPadding(0, dp(16), 0, 0)
                    fun button(text: String, primary: Boolean, onClick: () -> Unit) = label(text, 14f,
                        if (primary) 0xFF0E1A1E.toInt() else 0xFFE9E6DF.toInt()).apply {
                        background = if (primary) bg(0xFF6FB7CC.toInt(), 12) else bg(Color.TRANSPARENT, 12, 0xFF3A3C44.toInt())
                        setPadding(dp(18), dp(10), dp(18), dp(10)); gravity = Gravity.CENTER
                        setOnClickListener { onClick() }
                    }
                    addView(button("Don't allow", false) { allowed = false; latch.countDown() })
                    addView(button("Allow", true) { allowed = true; latch.countDown() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
                })
            }
            val p = overlayParams(Gravity.BOTTOM).apply { width = WindowManager.LayoutParams.MATCH_PARENT; y = dp(32) }
            val wrap = FrameLayout(this).apply { setPadding(dp(14), 0, dp(14), 0); addView(box) }
            runCatching { wm.addView(wrap, p); card = wrap }.onFailure { latch.countDown() }
        }
        latch.await(45, TimeUnit.SECONDS)
        main.post { card?.let { runCatching { wm.removeView(it) } } }
        return allowed
    }
}
