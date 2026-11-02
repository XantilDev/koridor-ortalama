package com.xantildev.koridor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Pil optimizasyonu ve ekran üstü izni yardımcıları. */
object Power {
    fun ignoringBattery(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Firma sürümünde doğrudan "izin ver" penceresi; store sürümünde pil ayarları listesi. */
    fun requestBattery(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val i = if (Firma.enabled) direct else list
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            runCatching { ctx.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun requestOverlay(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Aynı soruyu her açılışta sormamak için: reddedildiyse 3 gün sonra tekrar. */
    fun shouldAsk(ctx: Context, key: String): Boolean {
        val p = ctx.getSharedPreferences("izin_sorulari", Context.MODE_PRIVATE)
        return System.currentTimeMillis() - p.getLong(key, 0) > 3L * 24 * 3600_000
    }

    fun markAsked(ctx: Context, key: String) {
        ctx.getSharedPreferences("izin_sorulari", Context.MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
    }
}
