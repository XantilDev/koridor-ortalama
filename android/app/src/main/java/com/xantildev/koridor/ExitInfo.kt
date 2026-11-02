package com.xantildev.koridor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Uygulama arka planda kapandıysa nedenini kaydeder (Android 11+); "Ayrıntılar"da gösterilir. */
object ExitInfo {
    var last by mutableStateOf<String?>(null)
        private set

    fun check(ctx: Context) {
        val prefs = ctx.getSharedPreferences("kapanma", Context.MODE_PRIVATE)
        last = prefs.getString("son", null)
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
            if (e.timestamp <= prefs.getLong("ts", 0)) return
            val why = when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "sistem belleği azaldığı için kapattı"
                ApplicationExitInfo.REASON_SIGNALED -> "sistem kapattı (sinyal ${e.status})"
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "uygulama çöktü"
                ApplicationExitInfo.REASON_ANR -> "uygulama yanıt vermedi"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "kullanıcı / ayarlar kapattı"
                ApplicationExitInfo.REASON_USER_STOPPED -> "kullanıcı durdurdu"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "fazla kaynak kullandığı için sistem kapattı"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "izin değiştiği için kapandı"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "bağlı bir hizmet kapandığı için kapandı"
                ApplicationExitInfo.REASON_OTHER -> "sistem kapattı (diğer)"
                else -> "bilinmeyen neden (${e.reason})"
            }
            val at = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("tr", "TR")).format(java.util.Date(e.timestamp))
            val text = "Son kapanma: $at · $why" + (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            prefs.edit().putLong("ts", e.timestamp).putString("son", text).apply()
            last = text
        } catch (_: Exception) {
        }
    }
}
