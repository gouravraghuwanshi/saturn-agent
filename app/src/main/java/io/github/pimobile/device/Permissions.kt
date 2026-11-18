package io.github.pimobile.device

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks for a runtime permission the first time a phone feature needs it.
 * Android shows its own dialog, so Saturn must be open on screen; otherwise
 * the command fails with a message telling the user to open the app.
 */
object Permissions {
    private val main = Handler(Looper.getMainLooper())
    private var activity: WeakReference<ComponentActivity>? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CountDownLatch? = null
    private val lock = Any()

    /** Call from the activity's onCreate (before it starts). */
    fun attach(a: ComponentActivity) {
        activity = WeakReference(a)
        launcher = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pending?.countDown() }
    }

    /** Location asks for precise and approximate together (Android 12+ requires it). */
    private fun group(p: String) = if (p == Manifest.permission.ACCESS_FINE_LOCATION)
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else arrayOf(p)

    private fun ok(p: String) = Device.granted(p) ||
        p == Manifest.permission.ACCESS_FINE_LOCATION && Device.granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Blocks (off the main thread) until the user answers. True if granted. */
    fun ensure(permission: String, what: String): Boolean {
        if (ok(permission)) return true
        synchronized(lock) {
            if (ok(permission)) return true
            val a = activity?.get()
            if (a == null || !a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                throw Device.Fail("Saturn needs permission to use $what. Ask the user to open Saturn and try again — Android will ask them.")
            val latch = CountDownLatch(1)
            pending = latch
            main.post { runCatching { launcher?.launch(group(permission)) }.onFailure { latch.countDown() } }
            latch.await(2, TimeUnit.MINUTES)
            pending = null
        }
        return ok(permission)
    }

    /** For the Settings screen: asks without blocking. */
    fun request(permission: String) { launcher?.launch(group(permission)) }
}
