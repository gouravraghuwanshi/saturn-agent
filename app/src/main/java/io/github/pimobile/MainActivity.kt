package io.github.pimobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Runtime spike only: proves unmodified Pi runs inside our own APK.
 * This is deliberately plain — the real premium UI is built separately in Compose.
 */
class MainActivity : Activity() {

    private lateinit var pi: PiProcess
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private var nextId = 0
    private val prefs by lazy { getSharedPreferences("spike", MODE_PRIVATE) }

    private val bg = Color.parseColor("#0E0F13")
    private val fg = Color.parseColor("#D8DEE9")
    private val dim = Color.parseColor("#6B7280")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = field("OpenRouter API key", password = true).apply {
            setText(prefs.getString("key", ""))
        }
        val model = field("Model (optional, e.g. anthropic/claude-sonnet-4.5)").apply {
            setText(prefs.getString("model", ""))
        }
        val start = button("Start pi") {
            prefs.edit().putString("key", key.text.toString().trim())
                .putString("model", model.text.toString().trim()).apply()
            startPi()
        }
        val state = button("get_state") { send(JSONObject().put("type", "get_state")) }

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(fg)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
            setText("", TextView.BufferType.EDITABLE)
        }
        scroll = ScrollView(this).apply { addView(out) }

        val input = field("Message pi…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            maxLines = 4
        }
        val sendBtn = button("Send") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                append("\n› $text\n", fg)
                send(JSONObject().put("type", "prompt").put("message", text))
                input.setText("")
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            setPadding(16, 16, 16, 16)
            addView(key, MATCH_PARENT, WRAP_CONTENT)
            addView(model, MATCH_PARENT, WRAP_CONTENT)
            addView(row(start, state), MATCH_PARENT, WRAP_CONTENT)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(row(input, sendBtn, weightFirst = true), MATCH_PARENT, WRAP_CONTENT)
        })

        pi = PiProcess(
            this,
            onRecord = { r -> runOnUiThread { render(r) } },
            onLog = { s -> runOnUiThread { append("$s\n", dim) } },
            onExit = { code -> runOnUiThread { append("── pi exited ($code) ──\n", dim) } },
        )

        append("Pi runtime spike\nnative dir: ${applicationInfo.nativeLibraryDir}\n", dim)
        Thread {
            runCatching { pi.install() }
                .onFailure { e -> runOnUiThread { append("install failed: $e\n", Color.RED) } }
                .onSuccess { if (key.text.isNotBlank()) runOnUiThread { startPi() } }
        }.start()
    }

    private fun startPi() {
        val key = prefs.getString("key", "").orEmpty()
        val model = prefs.getString("model", "").orEmpty()
        val env = if (key.isNotEmpty()) mapOf("OPENROUTER_API_KEY" to key) else emptyMap()
        // Pi requires --model together with --provider; without a model it picks a
        // default from the providers that have credentials (here: OpenRouter).
        val args = if (model.isNotEmpty()) listOf("--provider", "openrouter", "--model", model) else emptyList()
        Thread {
            runCatching { pi.start(env, args) }
                .onSuccess { send(JSONObject().put("type", "get_state")) }
                .onFailure { e -> runOnUiThread { append("start failed: $e\n", Color.RED) } }
        }.start()
    }

    private fun send(cmd: JSONObject) {
        cmd.put("id", "c${nextId++}")
        pi.send(cmd)
    }

    private fun render(r: JSONObject) {
        when (r.optString("type")) {
            "message_update" -> {
                val ev = r.optJSONObject("assistantMessageEvent") ?: return
                when (ev.optString("type")) {
                    "text_delta" -> append(ev.optString("delta"), fg)
                    "thinking_delta" -> append(ev.optString("delta"), dim)
                }
            }
            "tool_execution_start" ->
                append("\n⚙ ${r.optString("toolName")} ${r.optJSONObject("args")?.toString()?.take(160)}\n", Color.parseColor("#88C0D0"))
            "tool_execution_end" ->
                append(if (r.optBoolean("isError")) "  ✗ failed\n" else "  ✓ done\n",
                    if (r.optBoolean("isError")) Color.RED else Color.parseColor("#A3BE8C"))
            "message_end" -> {
                val m = r.optJSONObject("message")
                val err = m?.optString("errorMessage").orEmpty()
                if (err.isNotEmpty()) append("\n✗ $err\n", Color.RED)
            }
            "agent_settled" -> append("\n── settled ──\n", dim)
            "auto_retry_start" -> append("\n↻ retry: ${r.optString("errorMessage")}\n", dim)
            "response" -> {
                if (!r.optBoolean("success")) {
                    append("✗ ${r.optString("command")}: ${r.optString("error")}\n", Color.RED)
                } else if (r.optString("command") == "get_state") {
                    val m = r.optJSONObject("data")?.optJSONObject("model")
                    append("● ready · model: ${m?.optString("provider")}/${m?.optString("id")}\n", Color.parseColor("#A3BE8C"))
                }
            }
            "agent_start", "turn_start", "turn_end", "agent_end",
            "message_start", "tool_execution_update" -> Unit
            else -> append("· ${r.optString("type")}\n", dim)
        }
    }

    private fun append(text: String, color: Int) {
        val buf = out.editableText
        val start = buf.length
        buf.append(text)
        buf.setSpan(
            android.text.style.ForegroundColorSpan(color), start, buf.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(dim)
        setTextColor(fg)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View, weightFirst: Boolean = false) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, v ->
                val w = if (weightFirst && i == 0) 1f else 0f
                addView(v, LinearLayout.LayoutParams(if (w > 0) 0 else WRAP_CONTENT, WRAP_CONTENT, w))
            }
        }

    override fun onDestroy() {
        pi.stop()
        super.onDestroy()
    }
}
