package io.github.pimobile.ui.state

import org.json.JSONObject

/** What a task left behind: files it saved to Artifacts ([made]) and other files it wrote or edited ([changed]). */
class Outcome(val made: List<String>, val changed: List<String>) {
    companion object {
        private val FILE_TOOLS = setOf("write", "edit")

        /** From the task's tool calls (name, arguments), in order; file names only, each once. */
        fun of(calls: List<Pair<String, JSONObject>>): Outcome {
            val made = LinkedHashSet<String>(); val changed = LinkedHashSet<String>()
            for ((name, args) in calls) {
                if (name !in FILE_TOOLS) continue
                val p = args.optString("path").ifBlank { args.optString("file_path") }.takeIf { it.isNotBlank() } ?: continue
                val file = p.trimEnd('/').substringAfterLast('/').ifBlank { continue }
                if (p.contains("/artifacts/") || p.startsWith("artifacts/")) made += file else changed += file
            }
            changed.removeAll(made)
            return Outcome(made.toList(), changed.toList())
        }
    }
}
