package dev.popcat19.thorutil

import java.util.concurrent.Executors

// Serial background executor for ordered UI toggles, QS tiles, and config sync so rapid
// on/off actions cannot overtake each other.
object Bg {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "thorutil-bg").apply { isDaemon = true }
    }

    fun post(block: () -> Unit) {
        exec.execute(block)
    }
}
