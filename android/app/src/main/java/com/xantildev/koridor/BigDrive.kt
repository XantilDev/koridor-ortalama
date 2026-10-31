package com.xantildev.koridor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val BBg = Color(0xFF0D1214)
private val BPanel = Color(0xFF151C1F)
private val BLine = Color(0xFF25302F)
private val BFg = Color(0xFFE9EEEC)
private val BMuted = Color(0xFF8C9A97)
private val BRed = Color(0xFFD62B2B)
private val BOk = Color(0xFF2FBF71)
private val BWarn = Color(0xFFF2B632)
private val BOver = Color(0xFFFF5A4F)

/** Telefon tutucuda uzaktan okunacak tam ekran sürüş görünümü. */
@Composable
fun BigDrive(s: UiState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val voice = rememberVoice()
    val c = s.corridor
    val inC = s.confirmed && c != null
    val lim = if (inC) c?.limit else null
    val avg = if (inC) s.avg else s.tripAvg
    val col = when {
        avg == null -> BMuted
        !inC || lim == null -> BFg
        avg > lim -> BOver
        avg > lim * 0.95 -> BWarn
        else -> BOk
    }
    val title = when {
        inC -> (c?.name ?: "") + if (s.partial) " · kısmi ölçüm" else ""
        c != null -> "Koridor girişi · yön doğrulanıyor"
        s.mode != "idle" -> "Serbest sürüş · koridor yok"
        else -> "Sürüş başlamadı"
    }
    Column(
        Modifier.fillMaxSize().background(BBg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = BFg, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg),
                modifier = Modifier.height(44.dp)
            ) { Text("Ayrıntılar", fontSize = 15.sp) }
        }
        // Büyük ortalama
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(BPanel)
                .border(2.dp, if (inC) col else BLine, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (inC) (if (s.partial) "ORTALAMA (BURADAN İTİBAREN)" else "KORİDOR ORTALAMASI") else "YOLCULUK ORTALAMASI",
                    color = BMuted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text(avg?.roundToInt()?.toString() ?: "—", color = col, fontSize = 150.sp, lineHeight = 150.sp, fontWeight = FontWeight.Bold)
                Text("km/s", color = BMuted, fontSize = 22.sp)
            }
        }
        // Limit tabelası + anlık hız
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(if (lim != null) Color.White else BPanel)
                    .border(15.dp, if (lim != null) BRed else BLine, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(lim?.toString() ?: "—", color = if (lim != null) Color(0xFF111111) else BMuted, fontSize = 50.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(18.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ANLIK HIZ", color = BMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val sp = s.speedKmh
                Text(sp?.toString() ?: "—", fontSize = 76.sp, lineHeight = 78.sp, fontWeight = FontWeight.Bold,
                    color = if (sp != null && lim != null && sp > lim) BOver else BFg)
            }
        }
        if (inC) {
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = BWarn, trackColor = BLine
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigCell("KALAN", Geo.fmtKm(s.remaining), null, Modifier.weight(1f))
                BigCell("ZAMAN PAYI", s.slack?.let { Geo.fmtSlack(it) } ?: "—", s.slack?.let { if (it >= 0) BOk else BOver }, Modifier.weight(1f))
            }
            BigCell("KALAN İÇİN EN FAZLA ORTALAMA", s.maxAvgLeft, null, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigCell("MESAFE", Geo.fmtKm(s.trip.dist), null, Modifier.weight(1f))
                BigCell("SÜRE", Geo.fmtT(s.trip.time), null, Modifier.weight(1f))
            }
        }
        (s.arrived?.let { "Hedefe vardın: $it" } ?: s.approach ?: s.note)?.let {
            Text(it, color = BWarn, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
        // Büyük düğmeler
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = voice,
                modifier = Modifier.weight(1f).height(76.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)
            ) { Text("🎤 Konuş", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            if (s.mode == "idle") {
                Button(
                    onClick = {
                        if (hasLocation(ctx)) {
                            TrackingService.start(ctx)
                            if (!locationOn(ctx)) ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    },
                    modifier = Modifier.weight(1.4f).height(76.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BOk, contentColor = Color(0xFF06140C))
                ) { Text("Sürüşü başlat", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = { if (s.mode == "gps") TrackingService.stop(ctx) else Engine.stop() },
                    modifier = Modifier.weight(1.4f).height(76.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BOver, contentColor = Color.White)
                ) { Text("Sürüşü bitir", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun BigCell(k: String, v: String, color: Color?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(k, color = BMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v, color = color ?: BFg, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
