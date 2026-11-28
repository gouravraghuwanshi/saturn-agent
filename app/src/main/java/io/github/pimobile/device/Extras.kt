package io.github.pimobile.device

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.pimobile.device.Device.Fail
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone features that only make sense outside the Play Store: the phone's files, other apps, installing
 * APKs, wallpaper, brightness, downloads and a music player. Each one either needs nothing, or asks the user
 * through Android's own screen; none of them reads messages, notifications or other apps' screens.
 */
internal object Extras {
    private val app get() = Device.appContext

    // ------------------------------------------------------------------ storage

    /** Like Termux: asks for "All files access", then links the phone's folders into ~/storage. */
    fun setupStorage(): String {
        val granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else Device.granted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (!granted) {
            if (Build.VERSION.SDK_INT >= 30) Device.startActivityPublic(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + app.packageName)))
            else Device.needPublic(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, "the phone's files")
            if (Build.VERSION.SDK_INT >= 30) return "Opened Android's \"All files access\" screen for Saturn. The user has to switch it on; " +
                "then run termux-setup-storage again to link the folders into ~/storage."
        }
        return "Phone storage is linked: " + linkStorage().joinToString(" ")
    }

    /** ~/storage/shared, downloads, dcim, pictures, music, movies, documents → the phone's own folders. */
    fun linkStorage(): List<String> {
        val root = Environment.getExternalStorageDirectory()
        val dir = File(app.filesDir, "home/storage").apply { mkdirs() }
        val links = mapOf("shared" to root, "downloads" to File(root, Environment.DIRECTORY_DOWNLOADS),
            "dcim" to File(root, Environment.DIRECTORY_DCIM), "pictures" to File(root, Environment.DIRECTORY_PICTURES),
            "music" to File(root, Environment.DIRECTORY_MUSIC), "movies" to File(root, Environment.DIRECTORY_MOVIES),
            "documents" to File(root, Environment.DIRECTORY_DOCUMENTS))
        return links.map { (name, target) ->
            val link = File(dir, name)
            runCatching { android.system.Os.remove(link.path) }
            runCatching { android.system.Os.symlink(target.path, link.path) }
            "~/storage/$name"
        }
    }

    fun storageGranted() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else Device.granted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

    // ------------------------------------------------------------------ apps

    private fun launchable() = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(app.packageManager).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }.sortedBy { it.first.lowercase() }

    /** The apps on the home screen: name and package, optionally filtered. */
    fun apps(query: String): String {
        val q = query.trim().lowercase()
        val list = launchable().filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.contains(q) }
        return JSONArray(list.map { JSONObject().put("name", it.first).put("package", it.second) }).toString(2)
    }

    /**
     * Opens another app by name or package. Android only lets an app open others while you're looking at it,
     * so when Saturn is in the background this posts a notification to tap instead.
     */
    fun openApp(what: String): String {
        val all = launchable()
        val q = what.trim().lowercase()
        val hit = all.firstOrNull { it.second == what.trim() } ?: all.firstOrNull { it.first.lowercase() == q }
            ?: all.filter { it.first.lowercase().contains(q) }.minByOrNull { it.first.length }
            ?: throw Fail("No app called \"$what\" on this phone. saturn-apps lists them.")
        val launch = app.packageManager.getLaunchIntentForPackage(hit.second) ?: throw Fail("${hit.first} can't be opened")
        if (io.github.pimobile.ui.state.UiStore.foreground) {
            Device.startActivityPublic(launch)
            return "Opened ${hit.first}."
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("saturn-agent", "From Saturn", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(app, hit.second.hashCode(), launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(20_000 + (hit.second.hashCode() and 0xFFFF), android.app.Notification.Builder(app, "saturn-agent")
            .setSmallIcon(io.github.pimobile.R.drawable.ic_saturn_glyph).setContentTitle("Open ${hit.first}")
            .setContentText("Saturn wants to open ${hit.first}. Tap to open it.").setContentIntent(pi).setAutoCancel(true).build())
        return "Saturn is in the background, so Android won't let it open ${hit.first} directly. Posted a notification; the user taps it to open the app."
    }

    /** Hands an APK to Android's installer; the user confirms there. */
    fun install(apk: File): String {
        if (!apk.isFile) throw Fail("No such file: $apk")
        if (!apk.name.endsWith(".apk", ignoreCase = true)) throw Fail("Not an .apk file: $apk")
        if (!app.packageManager.canRequestPackageInstalls()) {
            Device.startActivityPublic(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)))
            return "Android needs the user to allow Saturn to install apps first: the settings screen is open. Run saturn-install again after."
        }
        val uri = uriFor(apk)
        Device.startActivityPublic(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "Android's installer is open for ${apk.name}. The user confirms the install there."
    }

    private fun uriFor(f: File): Uri = runCatching { FileProvider.getUriForFile(app, app.packageName + ".files", f) }
        .getOrElse { throw Fail("Saturn can only use files in its home folder, or on the phone's storage after termux-setup-storage") }

    // ------------------------------------------------------------------ look and feel

    fun wallpaper(image: File, lock: Boolean): String {
        if (!image.isFile) throw Fail("No such file: $image")
        val wm = WallpaperManager.getInstance(app)
        image.inputStream().use { wm.setStream(it, null, true, if (lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM) }
        return "Wallpaper set" + if (lock) " (lock screen)." else " (home screen)."
    }

    /** 0–255, or "auto". Needs "Modify system settings", which only the user can switch on. */
    fun brightness(v: String): String {
        if (!Settings.System.canWrite(app)) {
            Device.startActivityPublic(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + app.packageName)))
            return "Changing brightness needs \"Modify system settings\" for Saturn; that screen is open for the user. Run it again after."
        }
        val cr = app.contentResolver
        if (v.equals("auto", true)) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Brightness: automatic."
        }
        val n = v.toIntOrNull()?.coerceIn(0, 255) ?: throw Fail("usage: termux-brightness <0-255|auto>")
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, n)
        return "Brightness: $n/255."
    }

    // ------------------------------------------------------------------ media

    /** Makes new photos, music and videos show up in Gallery and music apps. */
    fun mediaScan(files: List<File>): String {
        val latch = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(app, files.map { it.path }.toTypedArray(), null) { _, _ -> latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        return "Scanned ${files.size} file(s)."
    }

    /** Android's download manager: shows progress in the notification shade and saves to Downloads. */
    fun download(url: String, title: String?, description: String?): String {
        if (!Regex("^https?://.+", RegexOption.IGNORE_CASE).matches(url)) throw Fail("Only http(s) links can be downloaded")
        val name = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
        val req = DownloadManager.Request(Uri.parse(url)).setTitle(title ?: name).setDescription(description ?: url)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val id = app.getSystemService(DownloadManager::class.java).enqueue(req)
        return "Downloading to Downloads/$name (id $id)."
    }

    private var player: MediaPlayer? = null
    private var playing: File? = null

    /** play <file> · pause · resume · stop · info. */
    @Synchronized fun player(cmd: String, file: File?): String = when (cmd) {
        "play" -> {
            val f = file ?: throw Fail("usage: termux-media-player play <file>")
            if (!f.isFile) throw Fail("No such file: $f")
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.path); prepare(); start()
                setOnCompletionListener { synchronized(Extras) { it.release(); if (player === it) { player = null; playing = null } } }
            }
            playing = f
            "Playing ${f.name}."
        }
        "pause" -> { player?.takeIf { it.isPlaying }?.pause() ?: throw Fail("Nothing is playing"); "Paused." }
        "resume" -> { player?.start() ?: throw Fail("Nothing to resume"); "Playing." }
        "stop" -> { player?.release(); player = null; playing = null; "Stopped." }
        "info" -> player?.let { p ->
            JSONObject().put("status", if (p.isPlaying) "playing" else "paused").put("track", playing?.path)
                .put("position_ms", p.currentPosition).put("duration_ms", p.duration).toString(2)
        } ?: "No track is loaded."
        else -> throw Fail("usage: termux-media-player play <file> | pause | resume | stop | info")
    }
}
