package dev.kakkoi.localai.probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A loopback HTTP server small enough to have no dependencies, because the
 * question it answers is whether a socket held by a foreground service is
 * still reachable from Chrome hours later, and a framework would only add
 * things that could fail for reasons unrelated to that.
 *
 * Bound to 127.0.0.1 explicitly. The real app makes that a deliberate setting;
 * here it is not a setting at all.
 */
class LoopbackServer(private val context: Context, private val port: Int = 8080) {

    private var socket: ServerSocket? = null
    @Volatile private var running = false

    val startedAtWall = System.currentTimeMillis()
    private val startedAtElapsed = android.os.SystemClock.elapsedRealtime()

    fun start() {
        if (running) return
        running = true
        thread(name = "loopback-probe", isDaemon = true) {
            try {
                // A backlog, and only the loopback interface. Airplane mode
                // takes the radios down; lo is not a radio and stays up, which
                // is the thing being confirmed.
                ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                    socket = server
                    while (running) {
                        val client = try {
                            server.accept()
                        } catch (closed: Exception) {
                            break
                        }
                        // One request at a time. A probe has one caller.
                        try {
                            client.use(::serve)
                        } catch (ignored: Exception) {
                        }
                    }
                }
            } catch (bindFailed: Exception) {
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (ignored: Exception) {
        }
    }

    private fun serve(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
        }
        val parts = request.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }.substringBefore("?")

        if (method == "OPTIONS") return respond(client, 204, "text/plain", "")

        when (path) {
            "/ping" -> respond(client, 200, "application/json", status().toString())
            "/log" -> respond(client, 200, "text/csv", Heartbeat.file(context).takeIf { it.exists() }?.readText().orEmpty())
            else -> respond(client, 404, "application/json", """{"error":"try /ping or /log"}""")
        }
    }

    fun status(): JSONObject {
        val rows = Heartbeat.rows(context)
        val gaps = Heartbeat.gaps(rows)
        return JSONObject().apply {
            put("ok", true)
            put("startedAtWall", startedAtWall)
            put("uptimeSeconds", (android.os.SystemClock.elapsedRealtime() - startedAtElapsed) / 1000)
            put("beats", rows.count { it.event == "BEAT" })
            put("beatsInAirplaneMode", Heartbeat.beatsInAirplaneMode(rows))
            put("restarts", Heartbeat.restarts(rows))
            put("airplaneModeNow", Airplane.isOn(context))
            put("ignoringBatteryOptimizations", Battery.isExempt(context))
            put("gaps", JSONArray().apply {
                gaps.forEach { gap ->
                    put(JSONObject().apply {
                        put("fromWall", gap.fromWall)
                        put("toWall", gap.toWall)
                        put("seconds", gap.elapsedMs / 1000)
                    })
                }
            })
        }
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "No Content"}\r\n")
            append("Content-Type: $type; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // Wide open on purpose: this is a throwaway whose whole job is to
            // be reachable from whatever page you point at it. The real app
            // names its origins — see docs/decisions.md D9.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: content-type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }
}
