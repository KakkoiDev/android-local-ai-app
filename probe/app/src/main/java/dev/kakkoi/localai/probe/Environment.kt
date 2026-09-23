package dev.kakkoi.localai.probe

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

object Airplane {
    fun isOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}

object Battery {
    /**
     * Whether Android's own battery optimisation is off for us. Note the limit
     * of this signal: OEM layers from Samsung, Xiaomi and OnePlus kill
     * foreground services on their own terms and do not report through this
     * API, so `true` here is necessary and nowhere near sufficient. The
     * heartbeat log is the actual answer.
     */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
}
