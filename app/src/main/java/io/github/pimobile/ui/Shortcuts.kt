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

    companion object {
        fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, SaturnWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(ctx.packageName, R.layout.widget_saturn)
            views.setTextViewText(R.id.widget_status, Shortcuts.status())
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 41, Shortcuts.askIntent(ctx),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(ids, views)
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
