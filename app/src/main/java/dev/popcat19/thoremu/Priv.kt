package dev.popcat19.thoremu

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

// Runs shell commands as shell (Shizuku) or root (su). Writes to ime_fixed need one of them,
// because SettingsProvider rejects non-public System settings from third-party apps.
object Priv {
    private const val TAG = "ThorEmu/Priv"

    enum class Backend { SHIZUKU, ROOT, NONE }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    @Volatile
    private var rootOk: Boolean? = null

    fun rootReady(): Boolean {
        rootOk?.let { return it }
        val ok = try {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS) && out == "0"
        } catch (e: Throwable) {
            false
        }
        if (ok) rootOk = true
        return ok
    }

    fun backend(): Backend = when {
        shizukuReady() -> Backend.SHIZUKU
        rootReady() -> Backend.ROOT
        else -> Backend.NONE
    }

    data class Result(val code: Int, val out: String)

    fun sh(cmd: String): Result {
        val proc: Process = when (backend()) {
            Backend.SHIZUKU -> newShizukuProcess(arrayOf("sh", "-c", cmd))
            Backend.ROOT -> ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            Backend.NONE -> return Result(-1, "no privilege backend")
        }
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroy()
            return Result(-2, out)
        }
        val r = Result(proc.exitValue(), out.trim())
        if (r.code != 0) Log.w(TAG, "'$cmd' -> ${r.code}: ${r.out}")
        return r
    }

    // Shizuku 13 keeps newProcess private; reflection is the documented workaround.
    private fun newShizukuProcess(argv: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, argv, null, null) as Process
    }
}
