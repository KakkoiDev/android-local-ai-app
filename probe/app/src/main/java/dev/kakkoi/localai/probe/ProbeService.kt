package dev.kakkoi.localai.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ProbeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var server: LoopbackServer? = null

    private val beat = object : Runnable {
        override fun run() {
            Heartbeat.append(this@ProbeService, "BEAT", Airplane.isOn(this@ProbeService))
            notifyProgress()
            handler.postDelayed(this, Heartbeat.INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        // START before the socket: if binding fails, the log still shows we ran.
        Heartbeat.append(this, "START", Airplane.isOn(this))
        server = LoopbackServer(this).also { it.start() }
        handler.post(beat)
    }

    // START_STICKY on purpose. The question is not whether Android can bring us
    // back — it is how long we were gone, and a restart writes a second START
    // with a measurable hole in front of it.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Heartbeat.append(this, "STOP", Airplane.isOn(this))
        handler.removeCallbacks(beat)
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL,
            "Survival probe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Running so you can find out whether it keeps running." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification())
        }
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun notification(): Notification {
        val rows = Heartbeat.rows(this)
        val beats = rows.count { it.event == "BEAT" }
        val restarts = Heartbeat.restarts(rows)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Survival probe running")
            .setContentText("$beats beats · $restarts restarts · :8080")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "probe"
        private const val ID = 1
    }
}
