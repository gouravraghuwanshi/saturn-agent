package io.github.pimobile.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pimobile.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Updates from Saturn's GitHub releases.
 * Checks at most once a day, downloads the new APK, makes sure it's Saturn signed with the
 * same key and newer than this one, then hands it to Android's installer.
 */
object Updater {
    private const val API = "https://api.github.com/repos/gouravraghuwanshi/saturn-agent/releases/latest"
    private const val DAY = 20 * 60 * 60 * 1000L

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    /** A newer release than this app, once a check found one. */
    var available by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** 0..1 while downloading, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var installing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** When the last check finished, and whether it found this app up to date. */
    var checkedAt by mutableStateOf(0L)
        private set

    val supported get() = true
    val current: String get() = BuildConfig.VERSION_NAME

    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { app.getSharedPreferences("updater", Context.MODE_PRIVATE) }

    private var autoState by mutableStateOf<Boolean?>(null)
    /** Settings → "Check for updates". */
    var auto: Boolean
        get() = autoState ?: prefs.getBoolean("auto", true).also { autoState = it }
        set(v) { autoState = v; prefs.edit().putBoolean("auto", v).apply(); if (v) check(force = true) }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!supported) return
        checkedAt = prefs.getLong("checked_at", 0L)
        // A release found earlier is shown right away, until a newer check says otherwise.
        prefs.getString("found", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            ?.takeIf { newer(it.version, current) }?.let { available = it }
            ?: prefs.edit().remove("found").apply() // that release is this app now
        cleanup()
    }

    /** Called when the app comes to the front: checks if it's been a while (or when asked). */
    fun check(force: Boolean = false) {
        if (!supported || checking || installing || progress != null) return
        if (!force && (!auto || System.currentTimeMillis() - checkedAt < DAY)) return
        checking = true; if (force) error = null
        io.execute {
            val result = runCatching {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Saturn-updater")
                }
                if (conn.responseCode != 200) throw IOException("GitHub answered ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                parse(json)?.takeIf { newer(it.version, current) } to json
            }
            main.post {
                checking = false
                result.onSuccess { (rel, json) ->
                    checkedAt = System.currentTimeMillis()
                    available = rel
                    prefs.edit().putLong("checked_at", checkedAt).putString("found", rel?.let { json.toString() }).apply()
                }.onFailure { if (force) error = "Couldn't check: ${it.message ?: it}" }
            }
        }
    }

    private fun parse(json: JSONObject): Release? {
        val tag = json.optString("tag_name").removePrefix("v").takeIf { Regex("""\d+(\.\d+)*""").matches(it) } ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return Release(tag, json.optString("body"), apk.optString("browser_download_url"), apk.optLong("size"), json.optString("html_url"))
    }

    /** "0.5.10" > "0.5.9". */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private val dir get() = File(app.cacheDir, "updates")
    private fun cleanup() { dir.listFiles()?.forEach { it.delete() } }

    /** Android asks once whether Saturn may install apps. */
    fun canInstall() = app.packageManager.canRequestPackageInstalls()
    fun askPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Download, verify, install. */
    fun update(ctx: Context) {
        val rel = available?.takeIf { newer(it.version, current) } ?: run { available = null; return }
        if (progress != null || installing) return
        if (!canInstall()) { askPermission(ctx); return }
        error = null; progress = 0f
        io.execute {
            try {
                dir.mkdirs()
                val file = File(dir, "saturn-${rel.version}.apk")
                val tmp = File(dir, "download.part")
                val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Saturn-updater")
                }
                if (conn.responseCode != 200) throw IOException("download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
                var got = 0L; var last = 0L
                conn.inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0 && got - last > 512 * 1024) { last = got; val p = got.toFloat() / total; main.post { progress = p } }
                    }
                } }
                if (!tmp.renameTo(file)) throw IOException("couldn't save the download")
                verify(file)
                main.post { progress = null; installing = true }
                install(file)
            } catch (e: Exception) {
                main.post { progress = null; installing = false; error = e.message ?: e.toString() }
                cleanup()
            }
        }
    }

    /** The APK must be Saturn, newer than this app, and signed with the same key. */
    private fun verify(apk: File) {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("the download isn't a valid app")
        if (theirs.packageName != app.packageName) throw IOException("the download isn't Saturn")
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (theirs.longVersionCode <= mine.longVersionCode) throw IOException("the download isn't newer than this Saturn")
        fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.let { s ->
            (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        }.orEmpty().toSet()
        val a = certs(theirs); val b = certs(mine)
        if (a.isEmpty() || a.intersect(b).isEmpty()) throw IOException("the download isn't signed by Saturn's key, so it wasn't installed")
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Once Saturn installed itself, Android 12+ can update it without asking again.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("saturn.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val intent = Intent(app, Receiver::class.java)
            val pi = PendingIntent.getBroadcast(app, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(pi.intentSender)
        }
    }

    /** Android reports back here: show its confirm screen, or what went wrong. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> { installing = false; available = null; prefs.edit().remove("found").apply(); cleanup() }
                else -> {
                    installing = false
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    error = if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_FAILURE_ABORTED)
                        "Update cancelled" else "Couldn't install: ${msg ?: "unknown error"}"
                    cleanup()
                }
            }
        }
    }
}
