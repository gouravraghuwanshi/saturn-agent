package io.github.pimobile.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.pimobile.MainActivity
import io.github.pimobile.R
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore

/**
 * "Task #12 is done" / "Saturn needs you" when you're not looking at that
 * task, with Reply (answer right from the notification) and Open.
 */
object Notifier {
    private const val CHANNEL = "saturn-done"
    const val KEY_REPLY = "reply"
    private const val ACTION_REPLY = "saturn.reply"
    private const val ACTION_ANSWER = "saturn.answer"
    private const val ACTION_RETRY = "saturn.retry"

    private fun id(taskId: String) = 30_000 + (taskId.hashCode() and 0xFFFF)

    private fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When a task finishes or needs you while you're elsewhere" })

    /** The last few lines of each task's notification thread (you ↔ Saturn), so a reply reads like a conversation. */
    private class Line(val you: Boolean, val text: String, val at: Long = System.currentTimeMillis())
    private val threads = java.util.concurrent.ConcurrentHashMap<String, MutableList<Line>>()
    private fun thread(taskId: String) = threads.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf()) }
    private fun add(taskId: String, line: Line) = thread(taskId).apply { add(line); while (size > 5) removeAt(0) }

    /** A run ended. Notify unless the user is looking at this very task. */
    fun settled(ctx: Context, ui: TaskUi) {
        if (UiStore.watching(ui.task.id)) return
        val last = (ui.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } as? Item.Assistant)?.text
        val failed = ui.lastError != null
        val text = ui.lastError ?: plain(last).ifBlank { ui.title }
        add(ui.task.id, Line(false, text.take(1200)))
        post(ctx, ui, if (failed) "hit a problem" else "done", failed = failed)
    }

    /** Generic "needs you" (the question itself follows in [ask] when it arrives). */
    fun show(ctx: Context, ui: TaskUi, title: String, text: String) {
        add(ui.task.id, Line(false, text))
        post(ctx, ui, "needs you")
    }

    /** Saturn asked something: show the question, its choices as buttons, and a reply box for typed answers. */
    fun ask(ctx: Context, ui: TaskUi, d: Item.Dialog) {
        if (UiStore.watching(ui.task.id)) return
        val q = listOf(d.title, d.message).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Saturn has a question" }
        val t = thread(ui.task.id)
        if (t.lastOrNull()?.text?.startsWith("Saturn is waiting") == true) t.removeAt(t.lastIndex)
        add(ui.task.id, Line(false, q + if (d.options.isNotEmpty() && d.options.size > 3) "\n\n" + d.options.joinToString(" · ") else ""))
        post(ctx, ui, "asks you", dialog = d)
    }

    private fun post(ctx: Context, ui: TaskUi, state: String, failed: Boolean = false, dialog: Item.Dialog? = null, working: Boolean = false) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, id(ui.task.id),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TASK, ui.task.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // RemoteInput needs a mutable PendingIntent so Android can add the typed text.
        val reply = PendingIntent.getBroadcast(ctx, id(ui.task.id),
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra("task", ui.task.id)
                .putExtra("request", dialog?.takeIf { it.method == "input" || it.method == "editor" || it.method == "select" }?.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replyAction = Notification.Action.Builder(null, if (dialog != null) "Answer" else "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(if (dialog != null) "Your answer" else "Reply to Saturn").build())
            .setAllowGeneratedReplies(false).build()

        val you = android.app.Person.Builder().setName("You").build()
        val saturn = android.app.Person.Builder().setName("Saturn").setKey("saturn")
            .setIcon(android.graphics.drawable.Icon.createWithResource(ctx, R.mipmap.ic_launcher)).build()
        val style = Notification.MessagingStyle(you).setConversationTitle("#${ui.task.number} · ${ui.title.take(50)} · $state")
        thread(ui.task.id).toList().forEach { l -> style.addMessage(l.text, l.at, if (l.you) null else saturn) }

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_saturn)
            .setStyle(style)
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (working) b.setOnlyAlertOnce(true).setSubText("Saturn is working…").setProgress(0, 0, true)
        else b.setSubText(state)
        // One-tap answers for a short list of choices; longer lists are typed (or opened).
        val choices = dialog?.options?.takeIf { it.isNotEmpty() && it.size <= 3 && dialog.method != "input" }
        choices?.forEachIndexed { i, opt ->
            val pi = PendingIntent.getBroadcast(ctx, id(ui.task.id) * 8 + i,
                Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_ANSWER).putExtra("task", ui.task.id)
                    .putExtra("request", dialog.requestId).putExtra("value", opt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, opt.take(24), pi).build())
        }
        if (choices == null && !working) b.addAction(replyAction)
        if (failed) b.addAction(Notification.Action.Builder(null, "Retry", PendingIntent.getBroadcast(ctx, id(ui.task.id) * 8 + 7,
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_RETRY).putExtra("task", ui.task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        if (choices == null || choices.size < 3) b.addAction(Notification.Action.Builder(null, "Open", open).build())
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id(ui.task.id), b.build()) }
    }

    fun clear(ctx: Context, taskId: String) = ctx.getSystemService(NotificationManager::class.java).cancel(id(taskId))

    /** Markdown → plain text for notifications and speech. */
    fun plain(md: String?): String = (md ?: "").lines()
        .filterNot { it.trimStart().startsWith("```") }
        .joinToString("\n") { l ->
            l.trim().replace(Regex("^(#{1,6}\\s+|[-*+]\\s+|>\\s*)"), "")
                .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_`~]{1,3}"), "")
        }.replace(Regex("\n{3,}"), "\n\n").trim()

    /** Reply typed into the notification, a choice tapped, or Retry: act on the task and keep the thread going. */
    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task") ?: return
            UiStore.init(ctx)
            val task = TaskManager.tasks.firstOrNull { it.id == taskId } ?: return
            val ui = UiStore.ui(task)
            val said: String = when (intent.action) {
                ACTION_RETRY -> { UiStore.retry(ui); "Try again" }
                ACTION_ANSWER -> {
                    val v = intent.getStringExtra("value") ?: return
                    val d = ui.pendingDialog?.takeIf { it.requestId == intent.getStringExtra("request") }
                    if (d != null) UiStore.answer(ui, d, v) else UiStore.send(ui, v, followUp = true)
                    v
                }
                else -> {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim() ?: return
                    if (text.isEmpty()) return
                    // A typed answer to an open question goes to the question; anything else is a message.
                    val d = ui.pendingDialog?.takeIf { it.method != "confirm" && (intent.getStringExtra("request") == null || it.requestId == intent.getStringExtra("request")) }
                    if (d != null) UiStore.answer(ui, d, text) else UiStore.send(ui, text, followUp = true)
                    text
                }
            }
            add(taskId, Line(true, said))
            post(ctx, ui, "working", working = true)
        }
    }
}
