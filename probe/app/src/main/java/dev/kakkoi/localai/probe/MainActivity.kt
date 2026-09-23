package dev.kakkoi.localai.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliberately one file and no layout XML. This is a measuring instrument, not
 * a product, and every piece of it that could be wrong is a piece that could
 * explain away a result.
 */
class MainActivity : Activity() {

    private lateinit var readout: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readout = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(button("Start probe") { startProbe() })
            addView(button("Stop probe") { stopService(Intent(this@MainActivity, ProbeService::class.java)) })
            addView(button("Exempt from battery optimisation") { requestExemption() })
            addView(button("Share heartbeat log") { shareLog() })
            addView(readout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun startProbe() {
        startForegroundService(Intent(this, ProbeService::class.java))
    }

    private fun requestExemption() {
        // Android's own optimiser only. The OEM layer has no such intent and is
        // turned off by hand, per device, in a menu that moves every release.
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun shareLog() {
        val file = Heartbeat.file(this)
        if (!file.exists()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_TEXT, file.readText()),
                "Heartbeat log",
            )
        )
    }

    private fun render() {
        val rows = Heartbeat.rows(this)
        val gaps = Heartbeat.gaps(rows)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val first = rows.firstOrNull()
        readout.text = buildString {
            appendLine("beats               ${rows.count { it.event == "BEAT" }}")
            appendLine("beats in airplane   ${Heartbeat.beatsInAirplaneMode(rows)}")
            appendLine("restarts            ${Heartbeat.restarts(rows)}")
            appendLine("gaps over threshold ${gaps.size}")
            appendLine("longest gap         ${(gaps.maxOfOrNull { it.elapsedMs } ?: 0) / 1000}s")
            appendLine("running since       ${first?.let { stamp.format(Date(it.wall)) } ?: "not started"}")
            appendLine("airplane mode now   ${Airplane.isOn(this@MainActivity)}")
            appendLine("battery exempt      ${Battery.isExempt(this@MainActivity)}")
            appendLine()
            appendLine("verdict")
            appendLine(verdict(rows.count { it.event == "BEAT" }, gaps.size))
            if (gaps.isNotEmpty()) {
                appendLine()
                appendLine("gaps")
                gaps.takeLast(10).forEach {
                    appendLine("  ${stamp.format(Date(it.fromWall))} → ${it.elapsedMs / 1000}s")
                }
            }
        }
    }

    private fun verdict(beats: Int, gaps: Int) = when {
        beats < 60 -> "  inconclusive — needs an hour, ideally a night"
        gaps == 0 -> "  survived. A foreground service on this device is viable."
        gaps <= 2 -> "  killed occasionally. Usable only if the client retries a start."
        else -> "  killed repeatedly. This device will not host the real app."
    }
}
