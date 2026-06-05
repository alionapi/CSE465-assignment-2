package com.example.pa2.metrics

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager

/**
 * Reads instantaneous system measurements that are useful for the dashboard.
 * All getters are best-effort: if the platform doesn't expose a value, we
 * return [Long.MIN_VALUE] / -1 / "unknown" rather than throwing.
 */
class SystemStats(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** Instantaneous battery current in microamperes (raw). Negative = discharging. */
    fun batteryCurrentNow(): Long = try {
        batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    } catch (_: Throwable) { Long.MIN_VALUE }

    /** Battery level percentage, or -1 if unknown. */
    fun batteryLevelPct(): Int = try {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) { -1 }

    /** Process memory in bytes (Java + native + graphics). */
    fun appMemoryBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        // totalPss is in KB
        return info.totalPss.toLong() * 1024L
    }

    /** Thermal status string; only reliable on Android 10+. */
    fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}
