package io.github.pimobile.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phone features for the agent, reached through Termux:API-compatible
 * commands (termux-torch, termux-camera-photo, …) that PiRuntime writes to
 * usr/bin. Each command posts its arguments to the app's local bridge; this
 * object runs it with the app's own permissions, asking the user the first
 * time a feature needs one.
 */
object Device {
    class Fail(message: String) : Exception(message)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { app.getSharedPreferences("device", Context.MODE_PRIVATE) }

    /** Master switch in Settings. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    fun init(context: Context) { app = context.applicationContext }

    /** Commands, as the agent sees them (also listed in its system prompt). */
    val COMMANDS = listOf(
        "termux-torch", "termux-battery-status", "termux-vibrate", "termux-volume", "termux-tts-speak",
        "termux-notification", "termux-notification-remove", "termux-toast", "termux-location",
        "termux-camera-info", "termux-camera-photo", "termux-microphone-record", "termux-sensor",
        "termux-contact-list", "termux-wifi-connectioninfo", "termux-telephony-call", "termux-sms-send",
        "termux-share",
    )

    /**
     * Runs one command. [argv] is: the caller's working directory, its
     * artifacts folder, then the command's own arguments. Returns stdout.
     */
    fun run(cmd: String, argv: List<String>): String {
        if (!enabled) throw Fail("Phone features are turned off in Saturn's Settings (Phone features).")
        val cwd = File(argv.getOrElse(0) { "/" })
        val artifacts = argv.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
        val a = Args(argv.drop(2), FLAGS[cmd] ?: emptySet())
        fun path(p: String) = (if (p.startsWith("/")) File(p) else File(cwd, p)).absoluteFile
        return when (cmd) {
            "termux-torch" -> torch(a.positional.firstOrNull() ?: throw Fail("usage: termux-torch on|off"))
            "termux-battery-status" -> battery()
            "termux-vibrate" -> vibrate(a.opt("d")?.toLongOrNull() ?: 1000, a.flag("f"))
            "termux-volume" -> volume(a.positional.getOrNull(0), a.positional.getOrNull(1)?.toIntOrNull())
            "termux-tts-speak" -> speak(a.positional.joinToString(" "), a.opt("r")?.toFloatOrNull(), a.opt("p")?.toFloatOrNull())
            "termux-notification" -> notify(a.opt("i") ?: a.opt("id"), a.opt("t") ?: a.opt("title") ?: "Saturn",
                a.opt("c") ?: a.opt("content") ?: a.positional.joinToString(" "))
            "termux-notification-remove" -> unnotify(a.positional.firstOrNull() ?: throw Fail("usage: termux-notification-remove <id>"))
            "termux-toast" -> toast(a.positional.joinToString(" "), a.flag("s"))
            "termux-location" -> location(a.opt("p") ?: "gps", a.opt("r") ?: "once")
            "termux-camera-info" -> cameraInfo()
            "termux-camera-photo" -> photo(a.opt("c") ?: "0",
                a.positional.firstOrNull()?.let(::path) ?: File(artifacts ?: cwd, "photo-${stamp()}.jpg"))
            "termux-microphone-record" -> record(
                a.opt("f")?.let(::path) ?: File(artifacts ?: cwd, "recording-${stamp()}.m4a"),
                (a.opt("l")?.toIntOrNull() ?: 10).coerceIn(1, 600))
            "termux-sensor" -> sensors(a.flag("l"), a.opt("s"), (a.opt("n")?.toIntOrNull() ?: 1).coerceIn(1, 50), a.flag("a"))
            "termux-contact-list" -> contacts()
            "termux-wifi-connectioninfo" -> wifi()
            "termux-telephony-call" -> dial(a.positional.firstOrNull() ?: throw Fail("usage: termux-telephony-call <number>"))
            "termux-sms-send" -> sms(a.opt("n") ?: throw Fail("usage: termux-sms-send -n <number> <text>"), a.positional.joinToString(" "))
            "termux-share" -> a.opt("text")?.let(::shareText) ?: share(a.positional.firstOrNull()?.let(::path), a.opt("c"), a.opt("a") ?: "send")
            else -> throw Fail("unknown command $cmd")
        }
    }

    /** getopt-style parsing: -x value / --long value, boolean [flags], positionals. */
    private class Args(argv: List<String>, flags: Set<String>) {
        val opts = mutableMapOf<String, String>()
        val set = mutableSetOf<String>()
        val positional = mutableListOf<String>()
        init {
            var i = 0
            while (i < argv.size) {
                val s = argv[i]
                val name = s.trimStart('-')
                when {
                    s == "--" -> { positional += argv.drop(i + 1); break }
                    !s.startsWith("-") || s.length < 2 || s[1].isDigit() -> positional += s
                    name in flags -> set += name
                    else -> { opts[name] = argv.getOrElse(i + 1) { "" }; i++ }
                }
                i++
            }
        }
        fun opt(k: String) = opts[k]
        fun flag(k: String) = k in set
    }

    private val FLAGS = mapOf(
        "termux-vibrate" to setOf("f"), "termux-toast" to setOf("s"), "termux-sensor" to setOf("l", "a"),
    )

    private fun stamp() = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    private fun onMain(timeoutSec: Long = 10, block: (done: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        main.post { runCatching { block { latch.countDown() } }.onFailure { latch.countDown() } }
        latch.await(timeoutSec, TimeUnit.SECONDS)
    }

    private fun need(permission: String, what: String) {
        if (!Permissions.ensure(permission, what))
            throw Fail("Permission for $what was not granted. The user can allow it in Saturn → Settings → Phone features.")
    }

    // ------------------------------------------------------------ features

    private fun torch(state: String): String {
        val on = when (state.lowercase()) { "on", "1", "true" -> true; "off", "0", "false" -> false; else -> throw Fail("usage: termux-torch on|off") }
        val cm = app.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw Fail("This phone has no flashlight")
        cm.setTorchMode(id, on)
        return ""
    }

    private fun battery(): String {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: throw Fail("battery unavailable")
        val bm = app.getSystemService(BatteryManager::class.java)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return JSONObject()
            .put("health", when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"; BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"; else -> "UNKNOWN" })
            .put("percentage", level)
            .put("plugged", when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"; BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"; 0 -> "UNPLUGGED"; else -> "PLUGGED" })
            .put("status", when (i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"; else -> "UNKNOWN" })
            .put("temperature", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("current", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .toString(2)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long, force: Boolean): String {
        val v = app.getSystemService(Vibrator::class.java)
        val am = app.getSystemService(AudioManager::class.java)
        if (!force && am.ringerMode == AudioManager.RINGER_MODE_SILENT) return ""
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 10_000), VibrationEffect.DEFAULT_AMPLITUDE))
        return ""
    }

    private val STREAMS = linkedMapOf(
        "call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM, "ring" to AudioManager.STREAM_RING,
        "music" to AudioManager.STREAM_MUSIC, "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION,
    )

    private fun volume(stream: String?, value: Int?): String {
        val am = app.getSystemService(AudioManager::class.java)
        if (stream != null) {
            val s = STREAMS[stream] ?: throw Fail("stream must be one of ${STREAMS.keys.joinToString()}")
            value ?: throw Fail("usage: termux-volume <stream> <volume>")
            runCatching { am.setStreamVolume(s, value.coerceIn(0, am.getStreamMaxVolume(s)), 0) }
                .onFailure { throw Fail("Android refused to change $stream volume (Do Not Disturb?)") }
            return ""
        }
        return JSONArray(STREAMS.map { (n, s) ->
            JSONObject().put("stream", n).put("volume", am.getStreamVolume(s)).put("max_volume", am.getStreamMaxVolume(s))
        }).toString(2)
    }

    private var tts: TextToSpeech? = null
    private val ttsDone = mutableMapOf<String, CountDownLatch>()

    private fun speak(text: String, rate: Float?, pitch: Float?): String {
        if (text.isBlank()) throw Fail("usage: termux-tts-speak <text>")
        if (tts == null) {
            val ready = CountDownLatch(1)
            var ok = false
            onMain { done -> tts = TextToSpeech(app) { ok = it == TextToSpeech.SUCCESS; ready.countDown() }; done() }
            ready.await(10, TimeUnit.SECONDS)
            if (!ok) { tts = null; throw Fail("No text-to-speech engine available") }
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { synchronized(ttsDone) { ttsDone.remove(id) }?.countDown() }
                @Deprecated("") override fun onError(id: String) { synchronized(ttsDone) { ttsDone.remove(id) }?.countDown() }
            })
        }
        val t = tts!!
        val id = "u${SystemClock.uptimeMillis()}"
        val latch = CountDownLatch(1)
        synchronized(ttsDone) { ttsDone[id] = latch }
        t.setSpeechRate(rate ?: 1f); t.setPitch(pitch ?: 1f)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        latch.await((text.length / 8 + 15).toLong(), TimeUnit.SECONDS)
        return ""
    }

    private const val CHANNEL = "saturn-agent"

    private fun notify(id: String?, title: String, content: String): String {
        if (Build.VERSION.SDK_INT >= 33) need(Manifest.permission.POST_NOTIFICATIONS, "notifications")
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "From Saturn", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Notifications the agent posts for you" })
        val open = PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, CHANNEL).setSmallIcon(io.github.pimobile.R.drawable.ic_saturn_glyph)
            .setContentTitle(title).setContentText(content).setStyle(android.app.Notification.BigTextStyle().bigText(content))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(notificationId(id), n)
        return ""
    }

    private fun notificationId(id: String?) = 10_000 + ((id ?: SystemClock.uptimeMillis().toString()).hashCode() and 0xFFFFF)

    private fun unnotify(id: String): String { app.getSystemService(NotificationManager::class.java).cancel(notificationId(id)); return "" }

    private fun toast(text: String, short: Boolean): String {
        main.post { android.widget.Toast.makeText(app, text, if (short) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG).show() }
        return ""
    }

    @Suppress("MissingPermission")
    private fun location(provider: String, request: String): String {
        need(Manifest.permission.ACCESS_FINE_LOCATION, "location")
        val lm = app.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) throw Fail("Location is turned off on this phone")
        val wanted = when (provider) { "network" -> LocationManager.NETWORK_PROVIDER; "passive" -> LocationManager.PASSIVE_PROVIDER; else -> LocationManager.GPS_PROVIDER }
        val providers = buildList {
            if (provider == "gps" && Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            add(wanted); add(LocationManager.NETWORK_PROVIDER)
        }.distinct().filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw Fail("No location provider is available")
        var loc: Location? = null
        if (request != "last" && Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                val latch = CountDownLatch(1)
                lm.getCurrentLocation(p, null, Executors.newSingleThreadExecutor()) { loc = it; latch.countDown() }
                latch.await(30, TimeUnit.SECONDS)
                if (loc != null) break
            }
        }
        if (loc == null) loc = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        val l = loc ?: throw Fail("Couldn't get a location fix (try again outdoors)")
        return JSONObject().put("latitude", l.latitude).put("longitude", l.longitude).put("altitude", l.altitude)
            .put("accuracy", l.accuracy.toDouble()).put("bearing", l.bearing.toDouble()).put("speed", l.speed.toDouble())
            .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000).put("provider", l.provider)
            .toString(2)
    }

    private fun cameraInfo(): String {
        val cm = app.getSystemService(CameraManager::class.java)
        return JSONArray(cm.cameraIdList.map { id ->
            val c = cm.getCameraCharacteristics(id)
            JSONObject().put("id", id).put("facing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"; CameraCharacteristics.LENS_FACING_BACK -> "back"; else -> "external" })
                .put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                .put("focal_lengths", JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() } ?: emptyList<Double>()))
        }).toString(2)
    }

    /** A lifecycle that's "resumed" only while one photo is taken. */
    private class OneShot : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun photo(camera: String, out: File): String {
        need(Manifest.permission.CAMERA, "the camera")
        out.parentFile?.mkdirs()
        val selector = if (camera == "1" || camera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val provider = ProcessCameraProvider.getInstance(app).get(10, TimeUnit.SECONDS)
        val owner = OneShot()
        val latch = CountDownLatch(1)
        var error: String? = null
        val io = Executors.newSingleThreadExecutor()
        main.post {
            runCatching {
                owner.registry.currentState = Lifecycle.State.RESUMED
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                provider.bindToLifecycle(owner, selector, capture)
                // Give auto-exposure a moment to settle before the shot.
                main.postDelayed({
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), io, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { latch.countDown() }
                        override fun onError(e: ImageCaptureException) { error = e.message; latch.countDown() }
                    })
                }, 600)
            }.onFailure { error = it.message; latch.countDown() }
        }
        val finished = latch.await(30, TimeUnit.SECONDS)
        main.post { owner.registry.currentState = Lifecycle.State.DESTROYED }
        if (!finished) throw Fail("The camera didn't respond. Saturn must be open on screen to use the camera.")
        error?.let { throw Fail("Camera error: $it. Saturn must be open on screen to use the camera.") }
        return out.path
    }

    @Suppress("DEPRECATION")
    private fun record(out: File, seconds: Int): String {
        need(Manifest.permission.RECORD_AUDIO, "the microphone")
        out.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44_100)
            r.setOutputFile(out.path)
            r.prepare(); r.start()
            Thread.sleep(seconds * 1000L)
            r.stop()
        } catch (e: Exception) {
            throw Fail("Recording failed: ${e.message ?: e}. Saturn must be open on screen to use the microphone.")
        } finally { r.release() }
        return out.path
    }

    private fun sensors(list: Boolean, names: String?, count: Int, all: Boolean): String {
        val sm = app.getSystemService(SensorManager::class.java)
        val available = sm.getSensorList(Sensor.TYPE_ALL)
        if (list || names == null && !all) return JSONObject().put("sensors", JSONArray(available.map { it.name })).toString(2)
        val wanted = if (all) available else names!!.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .mapNotNull { n -> available.firstOrNull { it.name.lowercase().contains(n) || it.stringType.lowercase().endsWith(n) } }
        if (wanted.isEmpty()) throw Fail("No sensor matches \"$names\". Run termux-sensor -l to list them.")
        val readings = JSONArray()
        repeat(count) {
            val got = JSONObject()
            val latch = CountDownLatch(wanted.size)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    synchronized(got) {
                        if (got.has(e.sensor.name)) return
                        got.put(e.sensor.name, JSONObject().put("values", JSONArray(e.values.map { it.toDouble() })))
                    }
                    latch.countDown()
                }
                override fun onAccuracyChanged(s: Sensor, a: Int) {}
            }
            wanted.forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, main) }
            latch.await(3, TimeUnit.SECONDS)
            sm.unregisterListener(listener)
            readings.put(got)
        }
        return if (count == 1) readings.getJSONObject(0).toString(2) else readings.toString(2)
    }

    private fun contacts(): String {
        need(Manifest.permission.READ_CONTACTS, "contacts")
        val out = JSONArray()
        app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("name", c.getString(0)).put("number", c.getString(1)))
        }
        return out.toString(2)
    }

    @Suppress("DEPRECATION")
    private fun wifi(): String {
        val info = app.getSystemService(WifiManager::class.java).connectionInfo
        val ip = info.ipAddress.let { "%d.%d.%d.%d".format(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff) }
        return JSONObject().put("ssid", info.ssid.trim('"')).put("bssid", info.bssid).put("ip", ip)
            .put("link_speed_mbps", info.linkSpeed).put("rssi", info.rssi).put("frequency_mhz", info.frequency)
            .put("supplicant_state", info.supplicantState.toString()).toString(2)
    }

    private fun startActivity(i: Intent) {
        runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { throw Fail("Couldn't open it: ${it.message}") }
    }

    /** Opens the dialer with the number filled in — the user presses call. */
    private fun dial(number: String): String {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return "Opened the dialer with $number. The user has to press call."
    }

    /** Opens the messaging app with the text filled in — the user presses send. */
    private fun sms(numbers: String, text: String): String {
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.split(',').joinToString(";") { Uri.encode(it.trim()) }))
            .putExtra("sms_body", text))
        return "Opened Messages with the text for $numbers. The user has to press send."
    }

    private fun share(file: File?, type: String?, action: String): String {
        val i = if (file == null) {
            throw Fail("usage: termux-share <file>  (or pipe text: echo hi | termux-share)")
        } else {
            if (!file.isFile) throw Fail("No such file: $file")
            val uri = runCatching { FileProvider.getUriForFile(app, app.packageName + ".files", file) }
                .getOrElse { throw Fail("Only files inside Saturn's home folder can be shared") }
            val mime = type ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            if (action == "view") Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            else Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, null))
        return ""
    }

    /** termux-share with text on stdin. */
    private fun shareText(text: String): String {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        return ""
    }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
}
