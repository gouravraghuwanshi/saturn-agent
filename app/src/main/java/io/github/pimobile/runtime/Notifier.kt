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

    private fun id(taskId: String) = 30_000 + (taskId.hashCode() and 0xFFFF)

    private fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When a task finishes or needs you while you're elsewhere" })

    /** A run ended. Notify unless the user is looking at this very task. */
    fun settled(ctx: Context, ui: TaskUi) {
        if (UiStore.watching(ui.task.id)) return
        val last = (ui.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } as? Item.Assistant)?.text
        val failed = ui.lastError != null
        show(ctx, ui, if (failed) "Task #${ui.task.number} hit a problem" else "Task #${ui.task.number} is done",
            ui.lastError ?: plain(last).ifBlank { ui.title })
    }

    fun show(ctx: Context, ui: TaskUi, title: String, text: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, id(ui.task.id),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TASK, ui.task.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // RemoteInput needs a mutable PendingIntent so Android can add the typed text.
        val reply = PendingIntent.getBroadcast(ctx, id(ui.task.id),
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra("task", ui.task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replyAction = Notification.Action.Builder(null, "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Saturn").build())
            .setAllowGeneratedReplies(false).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_saturn)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1200)).setSummaryText(ui.title.take(60)))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(replyAction)
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id(ui.task.id), n) }
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

    /** Reply typed into the notification: send it to the task. */
    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task") ?: return
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim() ?: return
            if (text.isEmpty()) return
            UiStore.init(ctx)
            val task = TaskManager.tasks.firstOrNull { it.id == taskId } ?: return
            val ui = UiStore.ui(task)
            UiStore.send(ui, text, followUp = true)
            // Replace the reply spinner with a quiet "sent" state.
            channel(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(id(taskId), Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_saturn).setContentTitle("Task #${task.number}")
                .setContentText("Sent: ${text.take(120)}").setTimeoutAfter(4000).setOnlyAlertOnce(true).build())
        }
    }
}
