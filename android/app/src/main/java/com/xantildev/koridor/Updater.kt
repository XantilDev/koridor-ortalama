package com.xantildev.koridor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Güncelleme kontrolü (firma sürümü; mağaza sürümünü Play günceller): açılışta son sürüm bilgisine bakılır,
 * daha yenisi varsa indirme sayfasına yönlendirilir. Aynı sürüm için günde en fazla bir kez sorulur.
 */
object Updater {
    private const val INFO = "https://github.com/XantilDev/koridor-ortalama/releases/download/son-surum/surum.json"
    const val APK = "https://github.com/XantilDev/koridor-ortalama/releases/download/son-surum/KoridorOrtalama.apk"

    class Info(val code: Int, val notes: String)
    var available by mutableStateOf<Info?>(null)

    suspend fun check(ctx: Context) {
        if (!BuildConfig.FIRMA) return
        val prefs = ctx.getSharedPreferences("guncelleme", Context.MODE_PRIVATE)
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(INFO).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.instanceFollowRedirects = true
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                Info(o.getInt("versionCode"), o.optString("notes"))
            }.getOrNull()
        } ?: return
        if (info.code <= BuildConfig.VERSION_CODE) return
        val last = prefs.getLong("soruldu_${info.code}", 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        prefs.edit().putLong("soruldu_${info.code}", System.currentTimeMillis()).apply()
        available = info
    }
}

@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Updater.check(ctx) }
    val u = Updater.available ?: return
    AlertDialog(
        onDismissRequest = { Updater.available = null },
        title = { Text("Yeni sürüm var (#${u.code})") },
        text = {
            Text(
                "Şu an #${BuildConfig.VERSION_CODE} kullanıyorsun." +
                    (if (u.notes.isNotBlank()) "\n\nYenilikler: ${u.notes}" else "") +
                    "\n\n“Güncelle”ye basınca yeni APK iner; indirilen dosyayı açıp “Güncelle”yi seç. Kayıtların silinmez."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                Updater.available = null
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Güncelle", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { Updater.available = null }) { Text("Sonra") } }
    )
}
