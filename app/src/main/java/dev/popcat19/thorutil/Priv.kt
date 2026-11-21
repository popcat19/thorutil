package dev.popcat19.thorutil

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

// Runs shell commands as shell (Shizuku) or root (su). Writes to ime_fixed need one of them,
// because SettingsProvider rejects non-public System settings from third-party apps.
object Priv {
    private const val TAG = "ThorUtil/Priv"

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

    fun sh(cmd: String): Result = try {
        run(cmd)
    } catch (e: Throwable) {
        Log.w(TAG, "'$cmd' threw", e)
        Result(-3, e.toString())
    }

    // For operations (like signaling a root-started daemon) that shell Shizuku cannot do.
    fun shRoot(cmd: String): Result {
        if (!rootReady()) return Result(-1, "no root")
        return try {
            val r = exec(ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start())
            if (r.code != 0) Log.w(TAG, "root '$cmd' -> ${r.code}: ${r.out}")
            r
        } catch (e: Throwable) {
            Log.w(TAG, "root '$cmd' threw", e)
            Result(-3, e.toString())
        }
    }

    private fun run(cmd: String): Result {
        val procs = mutableListOf<Pair<Backend, () -> Process>>()
        if (shizukuReady()) procs.add(Backend.SHIZUKU to { newShizukuProcess(arrayOf("sh", "-c", cmd)) })
        if (rootReady()) procs.add(Backend.ROOT to { ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start() })
        if (procs.isEmpty()) return Result(-1, "no privilege backend")

        var last: Result? = null
        for ((be, launcher) in procs) {
            val proc = try {
                launcher()
            } catch (e: Throwable) {
                last = Result(-2, "$be: ${e.message}")
                // Only a dead backend falls through; a nonzero exit is the command's own answer.
                continue
            }
            val r = exec(proc)
            if (r.code != 0) Log.w(TAG, "'$cmd' -> ${r.code}: ${r.out}")
            return r
        }
        Log.w(TAG, "all backends failed to launch: ${last?.out}")
        return last!!
    }

    private fun exec(proc: Process): Result {
        val watchdog = Thread {
            try {
                Thread.sleep(10_000)
                proc.destroy()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        watchdog.interrupt()
        return Result(code, out.trim())
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
