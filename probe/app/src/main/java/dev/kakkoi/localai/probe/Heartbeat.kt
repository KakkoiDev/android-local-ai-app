package dev.kakkoi.localai.probe

import java.io.File

/**
 * The whole experiment is this file.
 *
 * A line every [INTERVAL_MS] with both clocks on it. Wall time says when, and
 * elapsedRealtime keeps counting while the device sleeps, so the difference
 * between the two tells you whether a hole in the record was the process dying
 * or Doze suspending the timer. Those are opposite answers and a single clock
 * cannot distinguish them.
 *
 * A kill is never reported by the thing being killed, so nothing here tries to
 * catch one. It is read afterwards, out of the gaps.
 */
object Heartbeat {
    const val INTERVAL_MS = 60_000L
    private const val FILE = "heartbeat.csv"

    // A gap is only interesting if it is longer than a missed beat plus slack.
    // Android batches alarms, so exactly one late beat is normal and is not a
    // kill; two is the device deciding something on our behalf.
    private const val GAP_THRESHOLD_MS = INTERVAL_MS * 2 + 15_000L

    fun file(context: android.content.Context): File = File(context.filesDir, FILE)

    fun append(context: android.content.Context, event: String, airplane: Boolean) {
        val line = listOf(
            event,
            System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime(),
            if (airplane) 1 else 0,
        ).joinToString(",")
        file(context).appendText(line + "\n")
    }

    data class Row(val event: String, val wall: Long, val elapsed: Long, val airplane: Boolean)

    data class Gap(val fromWall: Long, val toWall: Long, val wallMs: Long, val elapsedMs: Long) {
        /**
         * Both clocks advanced together, so the device was awake and we were
         * not running. That is a kill. If wall time had advanced while
         * elapsedRealtime did not, the device was powered off instead, which
         * is not a result.
         */
        val looksLikeKill: Boolean get() = elapsedMs > GAP_THRESHOLD_MS
    }

    fun rows(context: android.content.Context): List<Row> =
        file(context).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null
            val wall = parts[1].toLongOrNull() ?: return@mapNotNull null
            val elapsed = parts[2].toLongOrNull() ?: return@mapNotNull null
            Row(parts[0], wall, elapsed, parts[3] == "1")
        }

    fun gaps(rows: List<Row>): List<Gap> =
        rows.zipWithNext().mapNotNull { (previous, next) ->
            val gap = Gap(
                previous.wall,
                next.wall,
                next.wall - previous.wall,
                next.elapsed - previous.elapsed,
            )
            gap.takeIf { it.looksLikeKill }
        }

    /** Every START after the first is Android restarting us, which means something stopped us. */
    fun restarts(rows: List<Row>): Int = (rows.count { it.event == "START" } - 1).coerceAtLeast(0)

    fun beatsInAirplaneMode(rows: List<Row>): Int = rows.count { it.event == "BEAT" && it.airplane }
}
