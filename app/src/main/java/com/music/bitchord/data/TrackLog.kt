package com.music.bitchord.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The app's own record of how each track came to be playing, as text you can
 * paste somewhere.
 *
 * Diagnosing why a track played from the wrong source, at the wrong bitrate,
 * or not at all has meant plugging the phone in and reading `adb logcat` — and
 * the answer is usually in a stretch lasting a few seconds that has already
 * scrolled past by the time anyone notices something sounded wrong. This keeps
 * that stretch.
 *
 * ### Why not read logcat
 *
 * The obvious implementation shells out to `logcat`, and it works. But from
 * Android 13 an app that does so trips a system consent dialog — *"Allow
 * BitChord to access all device logs?"* — which appears whenever the process
 * happens to spawn, asks for far more than this needs, and puts every other
 * app's output within reach of a paste made from a music player. None of that
 * is a reasonable price for a debug button.
 *
 * So the lines are kept here on the way past instead. Nothing is read back
 * from the system, no permission is involved, no dialog can appear, and what
 * ends up on the clipboard is only ever what this app itself wrote.
 *
 * ### What gets kept
 *
 * Only the paths that decide how a track plays: the resolver, the module
 * sandbox, the source ladder, the cache and the player. Deliberately not the
 * feeds, the artwork, the lyrics or the library — a paste that includes
 * everything is one nobody reads to the end of, and none of it has ever been
 * the answer to "why did this song sound wrong".
 *
 * ### Which lines are whose
 *
 * Every line is filed against the track it is about, and reading the log back
 * is a question about a track rather than about a stretch of time — see
 * [about] and [forTrack]. This app does most of a track's work nowhere near
 * the moment that track is playing: it is resolved while the one before it
 * plays, and the first seconds of every track are spent resolving the *next*
 * one. So "the last thirty seconds of log" is never the same thing as "this
 * song's story", and asking for one by way of the other pastes the wrong
 * song's log almost every time.
 *
 * Call [d], [w] and [e] exactly where `Log.d`/`w`/`e` would go; they forward
 * to logcat as well, so `adb logcat -s BitChord` is unchanged.
 */
object TrackLog {

    // ── Writing ─────────────────────────────────────────────────────────────

    // logcat is only worth writing to in a debug build — nothing in prod ever
    // reads it (see the class doc), so a release build skips straight to
    // record(), which is what Copy Log actually depends on.

    fun d(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
        record('D', "[$tag] $message", about)
    }

    fun i(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
        record('I', "[$tag] $message", about)
    }

    fun w(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.w(tag, message)
        record('W', "[$tag] $message", about)
    }

    fun w(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.w(tag, message, error)
        record('W', "[$tag] $message\n${error.stackTraceToString()}", about)
    }

    fun e(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.e(tag, message)
        record('E', "[$tag] $message", about)
    }

    fun e(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.e(tag, message, error)
        record('E', "[$tag] $message\n${error.stackTraceToString()}", about)
    }

    // ── Whose line is it ────────────────────────────────────────────────────

    /** The track whose work this thread is doing, if it is doing any. */
    private val working = ThreadLocal<String?>()

    /**
     * A coroutine context that files everything logged inside it against [id].
     *
     *     scope.async(Dispatchers.IO + TrackLog.about(videoId)) { … }
     *
     * The alternative is passing an id down to every call that logs, and the
     * lines worth having are exactly the ones furthest from anyone who knows
     * which track they are for: a fetch inside a QuickJS export inside a module
     * search inside a source ladder. None of those layers has any other use for
     * a track id, and threading one through all of them to serve a debug button
     * would be a worse trade than the button is worth.
     *
     * Carried as a [kotlinx.coroutines.ThreadContextElement] rather than a bare
     * thread local because that work hops threads constantly —
     * `withContext(IO)` for a fetch, `Dispatchers.Default` for the JS engine —
     * and this follows it, including into every child coroutine.
     */
    fun about(id: String?): CoroutineContext = working.asContextElement(id)

    /**
     * Plain-thread twin of [about]: pins the calling thread's subsequent
     * lines to [id] until cleared with `setWorking(null)`. For worker
     * threads that never enter a coroutine — the analysis lanes set this to
     * the track under analysis so a track's story files itself.
     */
    fun setWorking(id: String?) {
        working.set(id)
    }

    private class Line(val at: Long, val level: Char, val text: String, val track: String?)

    private val lines = ArrayDeque<Line>()

    /** Total characters held, so the buffer is bounded by size rather than by count. */
    private var held = 0

    /**
     * Bounded by bytes rather than by line count: one `callExport result` line
     * carrying a search response is worth several hundred ordinary lines, and a
     * limit that counts them the same either wastes memory or throws away the
     * history that matters.
     */
    private fun record(level: Char, message: String, about: String?) {
        val text = if (message.length > MAX_LINE_CHARS) {
            message.take(MAX_LINE_CHARS) + "…(${message.length - MAX_LINE_CHARS} more)"
        } else {
            message
        }
        val at = System.currentTimeMillis()
        synchronized(lines) {
            lines.addLast(Line(at, level, text, about))
            held += text.length
            while (held > MAX_HELD_CHARS && lines.isNotEmpty()) {
                held -= lines.removeFirst().text.length
            }
            appendFile("${CLOCK.format(Date(at))} $level $text")
        }
    }

    // ── Session file: start-to-close on disk ───────────────────────────────

    /**
     * Continuous on-disk twin of the ring above: every recorded line is
     * appended to an internal session file from [init] (process start) until
     * [closeSessionFile], so a full start-to-close log survives to be
     * exported to Downloads — no adb, no repro-with-debugger dance.
     *
     * Bounded by rotation, not by hope: past [MAX_FILE_BYTES] the file rolls
     * to one spare and a fresh one opens with a continuation marker.
     */
    private var sessionFile: File? = null
    private var fileOut: BufferedWriter? = null
    private var fileBytes = 0L
    private var fileLinesSinceFlush = 0

    /** Must be called once from `Application.onCreate`, before anything logs. */
    fun init(context: Context) {
        synchronized(lines) {
            if (fileOut != null) return
            val dir = File(context.filesDir, SESSION_DIR).apply { mkdirs() }
            val name = "bitchord-session-${FILE_STAMP.format(Date())}.log"
            sessionFile = File(dir, name)
            fileOut = runCatching { sessionFile!!.bufferedWriter(Charsets.UTF_8, 8192) }.getOrNull()
            fileBytes = 0L
            fileLinesSinceFlush = 0
            fileOut?.let {
                val header = "BitChord session log — $name\n" +
                    "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})\n" +
                    "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                    "started: ${CLOCK.format(Date())}\n"
                runCatching {
                    it.write(header)
                    fileBytes += header.toByteArray(Charsets.UTF_8).size
                }
            }
        }
    }

    private fun appendFile(formatted: String) {
        val out = fileOut ?: return
        runCatching {
            val bytes = (formatted + "\n").toByteArray(Charsets.UTF_8)
            if (fileBytes + bytes.size > MAX_FILE_BYTES) rotateSessionFile()
            fileOut?.let {
                it.write(formatted)
                it.newLine()
                fileBytes += bytes.size
                if (++fileLinesSinceFlush >= FLUSH_EVERY_LINES) {
                    it.flush()
                    fileLinesSinceFlush = 0
                }
            }
        }
    }

    private fun rotateSessionFile() {
        runCatching {
            fileOut?.flush()
            fileOut?.close()
            val current = sessionFile ?: return
            File(current.parent, current.name + ".1").delete()
            current.renameTo(File(current.parent, current.name + ".1"))
            fileOut = current.bufferedWriter(Charsets.UTF_8, 8192)
            fileBytes = 0L
            fileLinesSinceFlush = 0
            fileOut?.write("…continued after rotation at ${CLOCK.format(Date())}\n")
        }
    }

    fun closeSessionFile() {
        synchronized(lines) {
            runCatching {
                fileOut?.flush()
                fileOut?.close()
            }
            fileOut = null
        }
    }

    /**
     * Copies the current session file (plus its rotated spare, if any) to
     * Downloads and returns the display path, or null when the export fails.
     * Safe to call mid-session: the writer is flushed first and left open.
     */
    fun exportSessionFile(context: Context): String? {
        synchronized(lines) {
            runCatching { fileOut?.flush() }
            val current = sessionFile?.takeIf { it.exists() } ?: return null
            val spare = File(current.parent, current.name + ".1").takeIf { it.exists() }
            val name = current.name
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                    ) ?: return null
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        spare?.inputStream()?.copyTo(out)
                        current.inputStream().copyTo(out)
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    "Downloads/$name"
                } else {
                    val target = File(
                        context.getExternalFilesDir(null),
                        "session-logs/$name",
                    ).apply { parentFile?.mkdirs() }
                    spare?.copyTo(target, overwrite = true)
                    current.copyTo(target, overwrite = true)
                    target.absolutePath
                }
            }.getOrNull()
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * Wall-clock times at which each track became the current one.
     *
     * Only a floor for tracks with no lines of their own, now that lines say
     * which track they are about: a track served whole from the disk cache is
     * resolved by nobody and would otherwise have no start at all.
     */
    private val startedAt = ConcurrentHashMap<String, Long>()

    fun onTrackStarted(videoId: String) {
        if (startedAt.size >= MAX_REMEMBERED) startedAt.clear()
        startedAt[videoId] = System.currentTimeMillis()
    }

    /**
     * The log for [song]: the lines about that track, plus the ones about
     * nothing in particular, from where its own story starts.
     *
     * Both halves of that are the fix for the same bug, and a plain time window
     * gets both of them wrong:
     *
     *  - **Where it starts.** A track is resolved while the track *before* it
     *    is still playing — that is what read-ahead is — so the resolve that
     *    decides its source, its bitrate and whether it plays at all sits
     *    minutes earlier than the moment the queue reached it. No window
     *    measured back from the selection reaches that.
     *  - **What is in it.** The first seconds of every track are spent
     *    resolving the next one, so a window running from the selection to now
     *    is largely the *following* song's story: its ladder, its client walk,
     *    its read-ahead. That is what a paste taken a few seconds into a track
     *    was almost entirely made of.
     *
     * Falling back to everything held is still the right way to be wrong for a
     * track nothing was ever filed against — one served whole from the disk
     * cache, or the track a cold start resumes on.
     */
    suspend fun forTrack(song: Song, stats: NerdStats.Snapshot?): String = withContext(Dispatchers.Default) {
        val held = synchronized(lines) { lines.toList() }
        val from = listOfNotNull(
            held.firstOrNull { it.track == song.videoId }?.at,
            startedAt[song.videoId]?.minus(LEAD_IN_MS),
        ).minOrNull()
        val since = held.filter { from == null || it.at >= from }
        val window = since.filter { it.track == null || it.track == song.videoId }
        header(song, stats, from, window.size, since.size - window.size) + "\n" +
            window.joinToString("\n") { "${CLOCK.format(Date(it.at))} ${it.level} ${it.text}" } +
            "\n"
    }

    // ── The part that isn't the log ─────────────────────────────────────────

    /**
     * What the lines alone can't say: which build produced them, on what, and
     * what the player believed it was playing when the log was taken.
     *
     * @param elsewhere how many lines in the same stretch belonged to another
     *   track and were left out. Stated rather than silently dropped: it is the
     *   difference between "nothing happened" and "nothing happened *to this
     *   track*", and the two send a reader looking in opposite places.
     */
    private fun header(
        song: Song,
        stats: NerdStats.Snapshot?,
        from: Long?,
        count: Int,
        elsewhere: Int,
    ) = buildString {
        appendLine("BitChord log — ${song.title} — ${song.artist}")
        appendLine("id=${song.videoId} duration=${song.durationText ?: "?"} album=${song.albumName ?: "?"}")
        appendLine("playing: ${stats.describe()}")
        appendLine(
            "sources: substitution=${SourceResolver.canSubstituteForYouTube()} " +
                "request=${SourceResolver.requestForNow()}",
        )
        appendLine(
            "window: ${from?.let { CLOCK.format(Date(it)) } ?: "everything held"} → " +
                "${CLOCK.format(Date())} ($count lines" +
                (if (elsewhere > 0) ", $elsewhere for other tracks left out)" else ")"),
        )
        appendLine("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    private fun NerdStats.Snapshot?.describe(): String {
        if (this == null) return "nothing reported"
        val measured = listOfNotNull(
            mimeType,
            bitDepth?.let { "$it-bit" },
            bitrateKbps?.let { "$it kbps" },
            sampleRateHz?.let { "$it Hz" },
            channels?.let { "${it}ch" },
        ).joinToString(" · ").ifEmpty { "nothing reported" }
        val promised = claimed?.summary?.let { " (source said: $it)" }.orEmpty()
        val tier = when {
            isHiRes -> " [Hi-Res Lossless]"
            isLossless -> " [Lossless]"
            isHiQuality -> " [Hi-Quality]"
            else -> ""
        }
        return measured + promised + tier
    }

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * How far back of a track's selection to reach when nothing was ever filed
     * against it — see [startedAt].
     *
     * Only a fallback now. It used to be the whole of the window, on the
     * reasoning that the resolve runs a moment before the player reports the
     * item as current; what it actually reaches back into is the *previous*
     * track's playback, and what the track being asked about spent it doing is
     * usually nothing.
     */
    private const val LEAD_IN_MS = 20_000L

    /** Roughly the last few tracks' worth, and small enough to hold without thinking about it. */
    private const val MAX_HELD_CHARS = 512_000

    private const val SESSION_DIR = "session-logs"

    /** One spare rotation: a session that logs past this keeps the last ~16 MB on disk, no more. */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** Flush cadence for the session file: crash-safe enough without fsyncing every line. */
    private const val FLUSH_EVERY_LINES = 100

    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Enough for a stack trace or a search response's opening; not a whole catalogue page. */
    private const val MAX_LINE_CHARS = 2_000

    private const val MAX_REMEMBERED = 32
}
