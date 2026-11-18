package io.github.mzalmuzakki.gesitsmid

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Everything goes in the log: every command sent (TX), every line received (RX), every event (EV).
 * Kept in a file in the app's storage (log.txt, then log.1.txt; about 10 MB in all) so nothing is lost
 * when the app is closed, plus the last lines in memory for the screen.
 */
object Log {
    private const val MAX_BYTES = 5_000_000L
    private val mem = ArrayDeque<String>()
    private var file: File? = null
    private var writer: FileWriter? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) = synchronized(this) {
        if (file != null) return@synchronized
        val f = File(ctx.filesDir, "log.txt")
        file = f
        if (mem.isEmpty() && f.exists()) try { f.readLines().takeLast(300).forEach { mem.addLast(it) } } catch (_: Exception) {}
    }

    fun ev(msg: String) = add("EV", msg)
    fun tx(cmd: String) = add("TX", show(cmd))
    fun rx(line: String) = add("RX", show(line))

    /** Control characters made visible: \r \n and others as \xNN. */
    private fun show(s: String): String = buildString {
        for (c in s) when {
            c == '\r' -> append("\\r")
            c == '\n' -> append("\\n")
            c < ' ' || c.code > 126 -> append(String.format(Locale.US, "\\x%02X", c.code))
            else -> append(c)
        }
    }

    private fun add(kind: String, msg: String) = synchronized(this) {
        val line = fmt.format(Date()) + " " + kind + " " + msg
        mem.addLast(line)
        while (mem.size > 300) mem.removeFirst()
        val f = file ?: return@synchronized
        try {
            if (writer == null) writer = FileWriter(f, true)
            if (f.length() > MAX_BYTES) {
                writer?.close()
                f.renameTo(File(f.parentFile, "log.1.txt"))
                writer = FileWriter(f, true)
            }
            writer?.write(line + "\n")
            writer?.flush()
        } catch (_: Exception) { writer = null }
    }

    fun recent(): List<String> = synchronized(this) { mem.toList() }

    /** The last [lines] lines from the files (old file first), optionally without TX/RX traffic. */
    fun tail(ctx: Context, lines: Int, eventsOnly: Boolean): String = synchronized(this) {
        val all = ArrayList<String>()
        for (n in listOf("log.1.txt", "log.txt")) {
            val f = File(ctx.filesDir, n)
            if (f.exists()) try { f.forEachLine { l -> if (!eventsOnly || " EV " in l) all.add(l) } } catch (_: Exception) {}
        }
        all.takeLast(lines).joinToString("\n")
    }

    /** The whole log (both files) as one text. */
    fun all(ctx: Context): String = synchronized(this) {
        listOf("log.1.txt", "log.txt").map { File(ctx.filesDir, it) }.filter { it.exists() }
            .joinToString("") { try { it.readText() } catch (_: Exception) { "" } }
    }

    fun clear(ctx: Context) = synchronized(this) {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        mem.clear()
        File(ctx.filesDir, "log.txt").delete(); File(ctx.filesDir, "log.1.txt").delete()
    }
}
