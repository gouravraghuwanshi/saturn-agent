package io.github.pimobile.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import io.github.pimobile.MainActivity
import io.github.pimobile.R
import io.github.pimobile.runtime.TaskManager

/** Ways into Saturn from outside the app: the home-screen widget and the quick-settings tile. */
object Shortcuts {
    fun askIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_FOCUS, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Opens Saturn already listening (the widget's mic). */
    fun voiceIntent(ctx: Context) = askIntent(ctx).putExtra(MainActivity.EXTRA_VOICE, true)

    private fun running() = TaskManager.tasks.count { it.status == TaskManager.Status.RUNNING || it.status == TaskManager.Status.STARTING }
    private fun waiting() = TaskManager.tasks.count { it.status == TaskManager.Status.NEEDS_INPUT }

    fun status(): String = when {
        waiting() > 0 -> "${waiting()} need you"
        running() > 0 -> "${running()} running"
        else -> ""
    }

    /** Called when task states change. */
    fun refresh(ctx: Context) {
        runCatching { SaturnWidget.update(ctx) }
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, AskTile::class.java)) }
    }
}

class SaturnWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = update(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = update(ctx)

    companion object {
        private val rows = listOf(
            intArrayOf(R.id.w_row0, R.id.w_dot0, R.id.w_title0, R.id.w_state0),
            intArrayOf(R.id.w_row1, R.id.w_dot1, R.id.w_title1, R.id.w_state1),
            intArrayOf(R.id.w_row2, R.id.w_dot2, R.id.w_title2, R.id.w_state2),
        )

        private fun pi(ctx: Context, code: Int, i: Intent) =
            PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** One row high: the Ask pill. */
        private fun small(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_saturn)
            val status = Shortcuts.status()
            views.setTextViewText(R.id.widget_status, status)
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi(ctx, 41, Shortcuts.askIntent(ctx)))
            views.setOnClickPendingIntent(R.id.widget_mic, pi(ctx, 43, Shortcuts.voiceIntent(ctx)))
            return views
        }

        /** Taller: what needs you or is running first, then the latest; tap one to open it. */
        private fun tall(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_saturn_tall)
            val all = TaskManager.tasks.filter { !it.archived }
            fun rank(t: TaskManager.Task) = when (t.status) {
                TaskManager.Status.NEEDS_INPUT -> 0; TaskManager.Status.RUNNING, TaskManager.Status.STARTING -> 1; else -> 2 }
            val shown = all.withIndex().sortedWith(compareBy<IndexedValue<TaskManager.Task>> { rank(it.value) }.thenBy { it.index })
                .map { it.value }.take(3)
            v.setTextViewText(R.id.w_summary, Shortcuts.status().ifEmpty { if (all.isEmpty()) "" else "all quiet" })
            rows.forEachIndexed { i, r ->
                val t = shown.getOrNull(i)
                v.setViewVisibility(r[0], if (t == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (t == null) return@forEachIndexed
                val (state, color) = when (t.status) {
                    TaskManager.Status.NEEDS_INPUT -> "needs you" to 0xFFF2B35B.toInt()
                    TaskManager.Status.RUNNING, TaskManager.Status.STARTING -> "working" to 0xFFF2B35B.toInt()
                    TaskManager.Status.FAILED -> "failed" to 0xFFF0857A.toInt()
                    else -> "#${t.number}" to 0xFF4A4F59.toInt()
                }
                v.setTextViewText(r[2], t.title)
                v.setTextViewText(r[3], state)
                v.setTextColor(r[1], color)
                v.setTextColor(r[3], if (rank(t) < 2 || t.status == TaskManager.Status.FAILED) color else 0xFF8E949E.toInt())
                v.setOnClickPendingIntent(r[0], pi(ctx, 50 + i, Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TASK, t.id).setData(android.net.Uri.parse("saturn://task/" + t.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            }
            v.setViewVisibility(R.id.w_empty, if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            v.setOnClickPendingIntent(R.id.w_ask, pi(ctx, 41, Shortcuts.askIntent(ctx)))
            v.setOnClickPendingIntent(R.id.w_mic, pi(ctx, 43, Shortcuts.voiceIntent(ctx)))
            v.setOnClickPendingIntent(R.id.w_card, pi(ctx, 44, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            return v
        }

        fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, SaturnWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    android.util.SizeF(180f, 40f) to small(ctx), android.util.SizeF(180f, 130f) to tall(ctx)))
                else {
                    val h = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    if (h >= 130) tall(ctx) else small(ctx)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}

class AskTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.label = getString(R.string.tile_label)
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat_saturn)
        t.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = Shortcuts.status().ifEmpty { null }
        t.updateTile()
    }

    override fun onClick() {
        val i = Shortcuts.askIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 42, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
