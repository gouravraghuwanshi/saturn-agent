package io.github.pimobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opening links and the clipboard on behalf of CLI tools (see PiRuntime.writeShims). */
object AppLinks {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) { app = context.applicationContext }

    /** Opens [url] in the user's default browser (sign-in pages refuse embedded WebViews). */
    fun open(url: String) {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun setClipboard(text: String) = main.post {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Saturn", text))
        android.widget.Toast.makeText(app, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Android only lets the focused app read the clipboard; read it on the main thread. */
    fun getClipboard(): String {
        var text = ""
        val done = CountDownLatch(1)
        main.post {
            text = runCatching {
                app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
            }.getOrNull().orEmpty()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return text
    }
}
