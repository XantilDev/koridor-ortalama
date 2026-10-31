package com.xantildev.koridor

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat

internal fun locationOn(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return LocationManagerCompat.isLocationEnabled(lm)
}

/**
 * Açılıştaki sorular ve hedefe varış:
 *  - yolculuk sürüyorsa: devam mı, bitir mi?
 *  - yarım kalmış yolculuk varsa: devam mı, kaydedip bitir mi?
 *  - hiçbiri yoksa: konumu açalım mı? (izin + telefonun konum ayarı)
 *  - hedefe varınca: sürüşü bitirelim mi?
 */
@Composable
fun StartupPrompts(firstOpen: Boolean, s: UiState) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ayar", Context.MODE_PRIVATE) }
    var ask by rememberSaveable { mutableStateOf<String?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var pendingResume by rememberSaveable { mutableStateOf<String?>(null) }

    fun begin(resumeId: String?) {
        TrackingService.start(ctx, resumeId)
        if (!locationOn(ctx)) ask = "ayar"
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || res[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            begin(pendingResume)
        } else {
            Engine.gpsStatus("Konum izni verilmedi")
            ask = "izin_yok"
        }
        pendingResume = null
    }

    fun startWithPermission(resumeId: String?) {
        if (hasLocation(ctx)) begin(resumeId) else {
            pendingResume = resumeId
            launcher.launch(perms())
        }
    }

    LaunchedEffect(firstOpen) {
        if (!firstOpen || asked) return@LaunchedEffect
        asked = true
        val half = TripStore.get(TripStore.activeId())
        when {
            TrackingService.running -> ask = "suruyor"
            Engine.tracking -> {}
            half != null -> ask = "yarim"
            // Konum izni yoksa izni iste; izin varsa ve konum açıksa sormadan başla; kapalıysa sor
            !hasLocation(ctx) -> startWithPermission(null)
            locationOn(ctx) -> begin(null)
            else -> ask = "konum"
        }
        if (half == null && !TrackingService.running) TripStore.setActive(null)
    }

    when (ask) {
        "suruyor" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Yolculuğun sürüyor") },
            text = { Text("Takip arka planda devam ediyor (${Engine.summary()}). Devam etmek istiyor musun?") },
            confirmButton = { TextButton(onClick = { ask = null }) { Text("Devam et", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { ask = null; TrackingService.stop(ctx) }) { Text("Sürüşü bitir") } }
        )
        "yarim" -> {
            val rec = TripStore.get(TripStore.activeId())
            if (rec == null) { ask = null; return }
            val mins = (rec.timeS / 60).toInt()
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Son yolculuğun yarım kaldı") },
                text = {
                    Text(
                        "Uygulama kapandığında ${Geo.fmtKm(rec.distM)} · $mins dk'lık bir yolculuk sürüyordu" +
                            (rec.target?.let { " (hedef: $it)" } ?: "") + ". Kaldığın yerden devam etmek istiyor musun?"
                    )
                },
                confirmButton = { TextButton(onClick = { ask = null; startWithPermission(rec.id) }) { Text("Devam et", fontWeight = FontWeight.Bold) } },
                dismissButton = {
                    TextButton(onClick = {
                        ask = null
                        TripStore.setActive(null)
                        if (!rec.sample) VehicleStore.addKm(VehicleStore.active.id, rec.distM / 1000)
                    }) { Text("Bitir ve kaydet") }
                }
            )
        }
        "konum" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Konumu açalım mı?") },
            text = { Text("Telefonun konumu kapalı. Koridorları ve ortalamanı görmek için konumu açman gerekiyor; açınca sürüş takibi kendiliğinden başlar.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    TrackingService.start(ctx)
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Konumu aç", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Şimdi değil") } }
        )
        "ayar" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Telefonun konumu kapalı") },
            text = { Text("Takip başladı ama telefonun konum ayarı kapalı. Ayarlardan “Konum”u açınca ölçüm kendiliğinden başlar.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Ayarları aç", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Kapat") } }
        )
        "izin_yok" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Konum izni verilmedi") },
            text = { Text("İzin olmadan ölçüm yapılamaz. Fikrini değiştirirsen “Sürüşü başlat”a bas; izin sorusu çıkmazsa Ayarlar › Uygulamalar › Koridor Ortalama › İzinler › Konum'dan açabilirsin.") },
            confirmButton = { TextButton(onClick = { ask = null }) { Text("Tamam") } }
        )
    }

    // Hedefe varış
    val arrived = s.arrived
    if (ask == null && arrived != null && s.mode != "idle") {
        AlertDialog(
            onDismissRequest = { Engine.dismissArrival() },
            title = { Text("Hedefe vardın") },
            text = { Text("$arrived. Sürüşü bitirip yolculuğu kaydedelim mi?") },
            confirmButton = {
                TextButton(onClick = {
                    Engine.dismissArrival()
                    if (s.mode == "gps") TrackingService.stop(ctx) else Engine.stop()
                }) { Text("Sürüşü bitir", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { Engine.dismissArrival() }) { Text("Devam et") } }
        )
    }
}
