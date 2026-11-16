package io.github.pimobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pimobile.runtime.PiRuntime
import io.github.pimobile.runtime.TaskManager
import org.json.JSONObject
import java.io.File

/**
 * Runtime test bench for phase 2 (multi-task engine + toolchain).
 * Deliberately plain: the premium UI is built separately in Compose.
 *
 * Input: plain text starts a NEW task; "#2 text" continues task 2.
 */
class MainActivity : Activity(), TaskManager.Listener {

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var taskList: TextView
    private val prefs by lazy { getSharedPreferences("spike", MODE_PRIVATE) }

    private val bg = Color.parseColor("#0E0F13")
    private val fg = Color.parseColor("#D8DEE9")
    private val dim = Color.parseColor("#7D838E")
    private val amber = Color.parseColor("#F2B35B")
    private val cyan = Color.parseColor("#6FB7CC")
    private val green = Color.parseColor("#7CC79A")
    private val red = Color.parseColor("#F0857A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskManager.init(this)
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val key = field("OpenRouter API key", password = true).apply { setText(prefs.getString("key", "")) }
        applyKey(key.text.toString())
        val save = button("Save key") {
            prefs.edit().putString("key", key.text.toString().trim()).apply()
            applyKey(key.text.toString())
            append("key saved (applies to newly started task processes)\n", dim)
        }
        val check = button("Toolchain check") { toolchainCheck() }

        taskList = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(fg); setPadding(24, 12, 24, 12)
        }
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(fg)
            setTextIsSelectable(true); setPadding(24, 12, 24, 24)
            setText("", TextView.BufferType.EDITABLE)
        }
        scroll = ScrollView(this).apply { addView(out) }

        val input = field("New task… or  #2 continue task 2").apply { maxLines = 4 }
        val send = button("Send") {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@button
            input.setText("")
            val m = Regex("^#(\\d+)\\s+(.+)", RegexOption.DOT_MATCHES_ALL).find(text)
            if (m != null) {
                val task = TaskManager.tasks.find { it.number == m.groupValues[1].toInt() }
                if (task == null) append("no task ${m.groupValues[1]}\n", red)
                else { append("\n#${task.number} › ${m.groupValues[2]}\n", fg); TaskManager.send(task, m.groupValues[2]) }
            } else {
                val task = TaskManager.createTask(text)
                append("\n#${task.number} › $text\n", fg)
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            setPadding(16, 16, 16, 16)
            addView(row(key, save, weightFirst = true), MATCH_PARENT, WRAP_CONTENT)
            addView(row(check), MATCH_PARENT, WRAP_CONTENT)
            addView(taskList, MATCH_PARENT, WRAP_CONTENT)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(row(input, send, weightFirst = true), MATCH_PARENT, WRAP_CONTENT)
        })
        renderTasks()
        append("native dir: ${applicationInfo.nativeLibraryDir}\n", dim)
    }

    override fun onStart() { super.onStart(); TaskManager.addListener(this); renderTasks() }
    override fun onStop() { TaskManager.removeListener(this); super.onStop() }

    private fun applyKey(k: String) {
        TaskManager.sessionEnv = if (k.isBlank()) emptyMap() else mapOf("OPENROUTER_API_KEY" to k.trim())
    }

    override fun onTaskEvent(task: TaskManager.Task, record: JSONObject?, log: String?) {
        renderTasks()
        val tag = "#${task.number} "
        log?.let { append("$tag$it\n", dim) }
        val r = record ?: return
        when (r.optString("type")) {
            "message_update" -> {
                val ev = r.optJSONObject("assistantMessageEvent") ?: return
                if (ev.optString("type") == "text_delta") append(ev.optString("delta"), fg)
            }
            "tool_execution_start" -> append("\n$tag⚙ ${task.lastActivity}\n", cyan)
            "tool_execution_end" -> append(if (r.optBoolean("isError")) "  ✗ failed\n" else "  ✓\n",
                if (r.optBoolean("isError")) red else green)
            "message_end" -> r.optJSONObject("message")?.optString("errorMessage")?.takeIf { it.isNotEmpty() }
                ?.let { append("\n$tag✗ $it\n", red) }
            "agent_settled" -> append("\n$tag── settled ──\n", dim)
            "extension_ui_request" -> append("$tag? ${r.optString("method")}: ${r.optString("title")}\n", amber)
            "response" -> if (!r.optBoolean("success")) append("$tag✗ ${r.optString("command")}: ${r.optString("error")}\n", red)
        }
    }

    private fun renderTasks() {
        taskList.text = TaskManager.tasks.joinToString("\n") {
            "#${it.number} ${it.status.name.lowercase().padEnd(11)} ${it.title.take(34)}"
        }.ifEmpty { "no tasks yet" }
    }

    /** Runs the bundled tools directly through the runtime env (no LLM needed). */
    private fun toolchainCheck() {
        append("\n── toolchain check ──\n", amber)
        Thread {
            val rt = PiRuntime.get(this)
            runCatching { rt.prepare { s -> runOnUiThread { append("$s\n", dim) } } }
                .onFailure { e -> runOnUiThread { append("prepare failed: $e\n", red) }; return@Thread }
            val script = """
                echo "bash  ${'$'}BASH_VERSION"
                echo "node  ${'$'}(node --version)"
                git --version
                rg --version | head -1
                curl --version | head -1
                echo "npm   ${'$'}(npm --version)"
                node -e "require('child_process').exec('echo node shell:true ok', (e,o) => console.log(e ? 'FAIL ' + e.message : o.trim()))"
                curl -sS -o /dev/null -w "curl https %{http_code}\n" https://registry.npmjs.org/
                cd "${'$'}TMPDIR" && rm -rf hello && git clone -q --depth 1 https://github.com/octocat/Hello-World.git hello && echo "git clone ok: ${'$'}(ls hello)"
                rg -c Hello hello && echo "rg ok"
            """.trimIndent()
            val pb = ProcessBuilder(File(rt.bin, "bash").path, "-c", script)
                .directory(rt.home).redirectErrorStream(true)
            pb.environment().putAll(rt.env())
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine { line -> runOnUiThread { append("$line\n", fg) } }
            val code = p.waitFor()
            runOnUiThread { append("── exit $code ──\n", if (code == 0) green else red) }
        }.start()
    }

    private fun append(text: String, color: Int) {
        val buf = out.editableText
        val start = buf.length
        buf.append(text)
        buf.setSpan(ForegroundColorSpan(color), start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(dim); setTextColor(fg); textSize = 14f
        typeface = Typeface.MONOSPACE
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun row(vararg views: View, weightFirst: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val w = if (weightFirst && i == 0) 1f else 0f
            addView(v, LinearLayout.LayoutParams(if (w > 0) 0 else WRAP_CONTENT, WRAP_CONTENT, w))
        }
    }
}
