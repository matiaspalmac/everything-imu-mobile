package cl.matiaspalma.everythingimu.core.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import androidx.core.content.getSystemService

/**
 * Helpers for the battery-optimization opt-out flow. OEMs (Xiaomi/MIUI,
 * OnePlus/OxygenOS, Samsung One UI, Huawei EMUI, etc.) aggressively kill
 * background apps even with a foreground service running. Without the user
 * granting "no restrictions" / "ignore optimizations", tracking dies a few
 * minutes after screen-off, the most common failure mode for phone trackers.
 */
object BatteryOptHelper {

    fun isIgnoringOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Use the direct REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent. Google Play
     * forbids this for most apps, but tracking apps that legitimately need
     * to run while screen-off (SlimeVR, fitness trackers) qualify.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreOptimizationsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Android Go / stripped OEM ROMs may ship neither battery-optimization
        // activity; launching an unresolvable intent throws
        // ActivityNotFoundException and crashes on tap. Fall back through the
        // settings list to the always-present top-level Settings.
        return firstResolvable(context, direct, settingsList)
    }

    /**
     * Returns the first candidate whose target activity actually exists, else a
     * plain ACTION_SETTINGS intent (always resolvable) so startActivity can never
     * throw ActivityNotFoundException.
     */
    private fun firstResolvable(context: Context, vararg candidates: Intent): Intent {
        val pm = context.packageManager
        for (intent in candidates) {
            if (intent.resolveActivity(pm) != null) return intent
        }
        return Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Falls back to the OS settings list when the direct intent is unavailable. */
    fun openOptimizationSettings(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
    }

    fun oemGuideUrl(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> "https://dontkillmyapp.com/xiaomi"
            "samsung" in m -> "https://dontkillmyapp.com/samsung"
            "oneplus" in m -> "https://dontkillmyapp.com/oneplus"
            "huawei" in m || "honor" in m -> "https://dontkillmyapp.com/huawei"
            "oppo" in m -> "https://dontkillmyapp.com/oppo"
            "vivo" in m -> "https://dontkillmyapp.com/vivo"
            "realme" in m -> "https://dontkillmyapp.com/realme"
            "asus" in m -> "https://dontkillmyapp.com/asus"
            "sony" in m -> "https://dontkillmyapp.com/sony"
            "lenovo" in m || "motorola" in m -> "https://dontkillmyapp.com/motorola"
            else -> "https://dontkillmyapp.com"
        }
    }
}
