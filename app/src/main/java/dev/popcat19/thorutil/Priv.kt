package dev.popcat19.thorutil

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Runs shell commands as shell (Shizuku) or root (su). Writes to ime_fixed need one of them,
// because SettingsProvider rejects non-public System settings from third-party apps.
// Keeps one persistent shell per backend so root does not re-invoke su (spamming Magisk
// superuser toasts) or pay process-spawn latency on every command.
object Priv {
    private const val TAG = "ThorUtil/Priv"
    private const val TIMEOUT_MS = 10_000L
    private const val INIT_TIMEOUT_MS = 5_000L
    private const val ROOT_RETRY_MS = 60_000L

    enum class Backend { SHIZUKU, ROOT, NONE }

    data class Result(val code: Int, val out: String)

    private val watchdogPool = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "priv-watchdog").apply { isDaemon = true }
    }

    private class PersistentShell(
        private val name: String,
        private val readyCheck: () -> Boolean,
        private val verifyUidZero: Boolean,
        private val launcher: () -> Process,
    ) {
        private val lock = Any()
        private val seq = AtomicLong(0L)
        private var proc: Process? = null
        private var writer: BufferedWriter? = null
        private var reader: BufferedReader? = null

        val isOpen: Boolean
            get() = synchronized(lock) { alive() }

        private fun alive(): Boolean {
            val p = proc ?: return false
            if (!readyCheck()) {
                closeLocked()
                return false
            }
            val ok = try {
                p.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
            if (!ok) closeLocked()
            return ok
        }

        private fun closeLocked() {
            val p = proc
            runCatching { writer?.close() }
            runCatching { reader?.close() }
            runCatching { p?.errorStream?.close() }
            runCatching { p?.destroy() }
            writer = null
            reader = null
            proc = null
        }

        fun ensureOpen(): Boolean = synchronized(lock) {
            if (alive()) return true
            if (!readyCheck()) return false
            openLocked()
        }

        private fun openLocked(): Boolean {
            closeLocked()
            val p = try {
                launcher()
            } catch (e: Throwable) {
                Log.w(TAG, "$name launch failed: ${e.message}")
                return false
            }
            proc = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))

            // Merge stderr into stdout inside the shell (Shizuku newProcess has separate pipes)
            // and verify the shell is responsive (and uid 0 for root).
            val probe = if (verifyUidZero) "exec 2>&1\nid -u" else "exec 2>&1\necho ok"
            val (res, _) = runRawLocked(probe, INIT_TIMEOUT_MS)
            val ok = if (verifyUidZero) {
                res != null && res.code == 0 && res.out.lines().lastOrNull { it.isNotBlank() }?.trim() == "0"
            } else {
                res != null && res.code == 0
            }
            if (!ok) {
                Log.w(TAG, "$name init failed: $res")
                closeLocked()
                return false
            }
            return true
        }

        fun exec(cmd: String, timeoutMs: Long = TIMEOUT_MS): Result? = synchronized(lock) {
            if (cmd.isBlank()) return Result(0, "")
            if (!readyCheck()) {
                closeLocked()
                return null
            }
            val wasOpen = alive()
            if (!wasOpen && !openLocked()) return null
            val wrapped = "{\n$cmd\n} </dev/null"
            val (first, timedOut) = runRawLocked(wrapped, timeoutMs)
            if (first != null) return first
            closeLocked()
            if (timedOut) return Result(-4, "$name timeout")
            // Existing shell died between commands (e.g. Shizuku restart); reopen and retry once.
            if (!wasOpen || !readyCheck() || !openLocked()) return null
            val (retry, retryTimedOut) = runRawLocked(wrapped, timeoutMs)
            if (retry != null) return retry
            closeLocked()
            if (retryTimedOut) Result(-4, "$name timeout") else null
        }

        private fun runRawLocked(script: String, timeoutMs: Long): Pair<Result?, Boolean> {
            val p = proc ?: return null to false
            val w = writer ?: return null to false
            val r = reader ?: return null to false
            val marker = "__THORUTIL_END_${seq.incrementAndGet()}_${System.nanoTime()}__"
            val done = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            val future = watchdogPool.schedule({
                if (done.compareAndSet(false, true)) {
                    timedOut.set(true)
                    runCatching { p.destroy() }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)

            return try {
                w.write(script)
                w.write("\n__rc=\$?\necho \"\"\necho \"")
                w.write(marker)
                w.write(" \$__rc\"\n")
                w.flush()

                val sb = StringBuilder()
                var exitCode: Int? = null
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith(marker)) {
                        val tail = line.substring(marker.length).trim()
                        exitCode = tail.toIntOrNull() ?: 0
                        break
                    }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
                if (exitCode == null) {
                    if (timedOut.get()) Log.w(TAG, "$name timed out after ${timeoutMs}ms")
                    null to timedOut.get()
                } else {
                    Result(exitCode, sb.toString().trim()) to false
                }
            } catch (e: Throwable) {
                if (!timedOut.get()) Log.w(TAG, "$name io failed: ${e.message}")
                null to timedOut.get()
            } finally {
                done.set(true)
                future.cancel(false)
            }
        }
    }

    private val shizukuShell = PersistentShell(
        name = "shizuku",
        readyCheck = ::shizukuReady,
        verifyUidZero = false,
        launcher = { newShizukuProcess(arrayOf("sh")) },
    )

    private val rootShell = PersistentShell(
        name = "root",
        readyCheck = { true },
        verifyUidZero = true,
        launcher = { ProcessBuilder("su").redirectErrorStream(true).start() },
    )

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    @Volatile
    private var rootFailedAt = Long.MIN_VALUE

    fun rootReady(): Boolean {
        if (rootShell.isOpen) return true
        val failedAt = rootFailedAt
        if (failedAt != Long.MIN_VALUE && SystemClock.elapsedRealtime() - failedAt < ROOT_RETRY_MS) {
            return false
        }
        val ok = rootShell.ensureOpen()
        rootFailedAt = if (ok) Long.MIN_VALUE else SystemClock.elapsedRealtime()
        return ok
    }

    fun recheck() {
        rootFailedAt = Long.MIN_VALUE
    }

    fun backend(): Backend = when {
        shizukuReady() -> Backend.SHIZUKU
        rootReady() -> Backend.ROOT
        else -> Backend.NONE
    }

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
            val r = rootShell.exec(cmd)
            if (r == null) {
                rootFailedAt = SystemClock.elapsedRealtime()
                return Result(-2, "root shell closed")
            }
            if (r.code != 0) Log.w(TAG, "root '$cmd' -> ${r.code}: ${r.out}")
            r
        } catch (e: Throwable) {
            Log.w(TAG, "root '$cmd' threw", e)
            Result(-3, e.toString())
        }
    }

    private fun run(cmd: String): Result {
        // Only fall back to root if Shizuku is unavailable or fails to launch;
        // never probe su while Shizuku is active.
        if (shizukuReady()) {
            val r = shizukuShell.exec(cmd)
            if (r != null) {
                if (r.code != 0) Log.w(TAG, "'$cmd' -> ${r.code}: ${r.out}")
                return r
            }
        }
        if (rootReady()) {
            val r = rootShell.exec(cmd)
            if (r != null) {
                if (r.code != 0) Log.w(TAG, "'$cmd' -> ${r.code}: ${r.out}")
                return r
            }
            rootFailedAt = SystemClock.elapsedRealtime()
        }
        return Result(-1, "no privilege backend")
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
