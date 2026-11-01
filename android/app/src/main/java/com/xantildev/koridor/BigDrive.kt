package com.xantildev.koridor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Telefon tutucuda uzaktan okunacak tam ekran sürüş görünümü. Yatay ekranda (araç tableti) haritalı düzen. */
@Composable
fun BigDrive(s: UiState, onClose: () -> Unit) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        BigDriveWide(s, onClose)
        return
    }
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
        s.driving -> "Serbest sürüş · koridor yok"
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
        SharingBadge(s)
        ApproachBanner(s)
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
            if (lim == null && s.roadLimit != null) RoadLimitSign(s, 120) else Box(
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
            if (!s.driving) {
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
                    onClick = { TrackingService.end(ctx) },
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


private val BBlue = Color(0xFF4C8DFF)

/** Yatay düzen: solda harita, sağda ortalama, limit, anlık hız ve tahmini ortalama kartı. */
@Composable
private fun BigDriveWide(s: UiState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val voice = rememberVoice()
    val ver by CorridorStore.version.collectAsStateWithLifecycle()
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
    val route = RouteSession.info
    val pos = s.pos
    val remTot = if (route != null && pos != null) route.remainingFrom(pos) else route?.totalM
    val cruise = s.moveAvg?.takeIf { it > 20 } ?: route?.let { it.totalM / it.durationS * 3.6 } ?: 80.0
    // Ekran açıkken konum kapalıysa hazır durumda aç (araç hareket edince sürüş başlar)
    androidx.compose.runtime.LaunchedEffect(s.mode == "idle") {
        if (s.mode == "idle" && hasLocation(ctx) && locationOn(ctx) && !TrackingService.running) TrackingService.start(ctx, standby = true)
    }
    val next = if (pos == null || inC) null else (route?.corridors ?: emptyList())
        .filter { Geo.dist(pos, it.from) > 50 }.minByOrNull { Geo.dist(pos, it.from) }
        ?.let { CorridorStore.withOverride(it) }

    Row(Modifier.fillMaxSize().background(BBg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Harita ----
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).border(1.dp, BLine, RoundedCornerShape(22.dp))) {
            LiveMap(s, ver, controls = true)
            val topChip = when {
                inC -> "● Koridor içindesin · ${Geo.fmtKm(s.remaining)} kaldı" + if (s.partial) " · kısmi" else ""
                c != null -> "● Koridor girişi · yön doğrulanıyor"
                next != null -> "Sıradaki koridor ${Geo.fmtKm(Geo.dist(pos!!, next.from))} · ${next.limit?.let { "limit $it" } ?: "limit ?"}"
                else -> null
            }
            topChip?.let { MapChip(it, Modifier.align(Alignment.TopStart).padding(10.dp), if (inC || c != null) BWarn else BFg) }
            if (route != null && remTot != null) {
                val eta = clock(remTot / (cruise / 3.6))
                MapChip("Hedef ${route.target.name} · ${Geo.fmtKm(remTot)} · varış $eta", Modifier.align(Alignment.BottomStart).padding(10.dp), BFg)
            }
            s.arrived?.let { MapChip("Hedefe vardın: $it", Modifier.align(Alignment.Center), BOk) }
        }
        // ---- Sağ panel ----
        if (!s.driving) {
            StandbyPanel(s, voice, onClose, Modifier.weight(1f).fillMaxHeight())
            return@Row
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            inC -> c?.name ?: ""
                            c != null -> c.name
                            s.driving -> "Serbest sürüş"
                            else -> "Sürüş başlamadı"
                        },
                        color = BFg, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            inC && c != null -> "Ortalama hız koridoru · ${Geo.fmtKm(c.lengthM)}" + if (s.partial) " · kısmi ölçüm" else ""
                            s.driving -> "Koridor yok · yolculuk ortalaman gösteriliyor"
                            else -> ""
                        },
                        color = BMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (Firma.active) SharingBadge(s) else Text(s.gpsText, color = BMuted, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            ApproachBanner(s)
            // Ortalama + tabela + anlık
            Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(BPanel)
                        .border(3.dp, if (inC) col else BLine, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val big = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight * 0.5f).toSp() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (inC) (if (s.partial) "ORTALAMA (BURADAN)" else "KORİDOR ORTALAMASI") else "YOLCULUK ORTALAMASI",
                            color = BMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        Text(avg?.roundToInt()?.toString() ?: "—", color = col, fontSize = big, lineHeight = big, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("km/s", color = BMuted, fontSize = 16.sp)
                    }
                    if (inC) LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = BWarn, trackColor = BLine
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lim == null && s.roadLimit != null) RoadLimitSign(s, 84) else Box(
                            Modifier.padding(8.dp).size(96.dp).clip(CircleShape).background(if (lim != null) Color.White else BPanel)
                                .border(12.dp, if (lim != null) BRed else BLine, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text(lim?.toString() ?: "—", color = if (lim != null) Color(0xFF111111) else BMuted, fontSize = 38.sp, fontWeight = FontWeight.Bold) }
                    }
                    Column(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(20.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text("ANLIK HIZ", color = BMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val sp = s.speedKmh
                        Text(sp?.toString() ?: "—", fontSize = 58.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold,
                            color = if (sp != null && lim != null && sp > lim) BOver else BFg)
                    }
                }
            }
            // Küçük kutular
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (inC) {
                    WideTile("KALAN", Geo.fmtKm(s.remaining), null, Modifier.weight(1f))
                    WideTile("ZAMAN PAYI", s.slack?.let { Geo.fmtSlack(it) } ?: "—", s.slack?.let { if (it >= 0) BOk else BOver }, Modifier.weight(1f))
                    WideTile("EDS İÇİ BEKLEME", Geo.fmtT(s.corridorStop), if (s.corridorStop > 0) BWarn else null, Modifier.weight(1f))
                } else {
                    WideTile("MESAFE", Geo.fmtKm(s.trip.dist), null, Modifier.weight(1f))
                    WideTile("SÜRE", Geo.fmtT(s.trip.time), null, Modifier.weight(1f))
                    WideTile("BEKLEME", Geo.fmtT(s.trip.stop), null, Modifier.weight(1f))
                }
            }
            // Tahmini ortalama
            Estimate(s, inC, c, remTot, cruise, Modifier.fillMaxWidth().weight(1f))
            // Düğmeler
            Row(Modifier.height(70.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = voice, modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) { Text("🎤 Konuş", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) { Text("Ayrıntılar", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (!s.driving) {
                    Button(
                        onClick = { if (hasLocation(ctx)) TrackingService.start(ctx) },
                        modifier = Modifier.weight(1.3f).fillMaxHeight(),
                        colors = ButtonDefaults.buttonColors(containerColor = BOk, contentColor = Color(0xFF06140C))
                    ) { Text("Sürüşü başlat", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Button(
                        onClick = { TrackingService.end(ctx) },
                        modifier = Modifier.weight(1.3f).fillMaxHeight(),
                        colors = ButtonDefaults.buttonColors(containerColor = BOver, contentColor = Color.White)
                    ) { Text("Sürüşü bitir", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun clock(secFromNow: Double): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr", "TR"))
        .format(java.util.Date(System.currentTimeMillis() + (secFromNow * 1000).toLong()))

private fun dur(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk ${(sec % 60).roundToInt()} sn"
}

/**
 * Çubukla hız seç: koridordaysa koridor sonu ortalaması, çıkış süresi ve varış;
 * koridor dışındaysa varış saati ve yolculuk ortalaması tahmini. Çubuk en fazla limit +%10.
 */
@Composable
private fun Estimate(s: UiState, inC: Boolean, c: Corridor?, remTot: Double?, cruise: Double, modifier: Modifier) {
    val lim = if (inC) c?.limit else null
    val maxV = if (lim != null) kotlin.math.floor(lim * 1.1).toInt() else 140
    val minV = 40
    var v by androidx.compose.runtime.saveable.rememberSaveable(c?.key ?: "serbest") {
        androidx.compose.runtime.mutableFloatStateOf((lim ?: cruise.roundToInt().coerceIn(minV, maxV)).toFloat())
    }
    val vi = v.roundToInt().coerceIn(minV, maxV)
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BBlue.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("TAHMİNİ ORTALAMA", color = BBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (inC && lim == null) {
            Text("Bu koridorun limiti bilinmiyor. “Ayrıntılar”dan tabelaya dokunup levhadaki limiti girersen tahmin burada çıkar.", color = BFg, fontSize = 15.sp)
            return@Column
        }
        if (!inC && remTot == null) {
            Text("Hedef seçilmedi. Varış tahmini için “Konuş”a basıp gideceğin yeri söyle ya da “Nereye?”den seç.", color = BFg, fontSize = 15.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (inC) "Kalan ${Geo.fmtKm(s.remaining)}'yi " else "Kalan yolu ", color = BMuted, fontSize = 16.sp)
            Text("$vi km/s", color = if (lim != null && vi > lim) BWarn else BFg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(" ile gidersem", color = BMuted, fontSize = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$minV", color = BMuted, fontSize = 13.sp)
            androidx.compose.material3.Slider(
                value = v, onValueChange = { v = it }, valueRange = minV.toFloat()..maxV.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BBlue, inactiveTrackColor = BLine)
            )
            Text(if (lim != null) "$maxV\nlimit +%10" else "$maxV", color = BMuted, fontSize = 12.sp, lineHeight = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (inC && lim != null) {
                val tRem = s.remaining / (vi / 3.6)
                val fin = (s.traveled + s.remaining) / (s.elapsed + tRem) * 3.6
                val fc = when { fin > lim * 1.1 -> BOver; fin > lim -> BWarn; else -> BOk }
                ResBox("KORİDOR SONU ORT.", "${fin.roundToInt()} km/s", fc, Modifier.weight(1f))
                ResBox("KORİDORDAN ÇIKIŞ", dur(tRem), null, Modifier.weight(1f))
                val after = if (remTot != null) (remTot - s.remaining).coerceAtLeast(0.0) / (cruise / 3.6) else null
                ResBox("HEDEFE VARIŞ", if (after != null) clock(tRem + after) else "—", null, Modifier.weight(1f))
            } else if (remTot != null) {
                val tRem = remTot / (vi / 3.6)
                val tripAvg = (s.trip.dist + remTot) / (s.trip.time + tRem).coerceAtLeast(1.0) * 3.6
                ResBox("HEDEFE VARIŞ", clock(tRem), null, Modifier.weight(1f))
                ResBox("KALAN SÜRE", dur(tRem), null, Modifier.weight(1f))
                ResBox("YOLCULUK ORT.", "${tripAvg.roundToInt()} km/s", null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResBox(k: String, v: String, color: Color?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(BBg).border(1.dp, BLine, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(k, color = BMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v, color = color ?: BFg, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun WideTile(k: String, v: String, color: Color?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(k, color = BMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v, color = color ?: BFg, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MapChip(text: String, modifier: Modifier, color: Color) {
    Text(
        text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE60D1214)).border(1.dp, BLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}


/** Sürüş yokken sağ panel: nereye gidiyoruz, hızlı hedefler, başlat. */
@Composable
private fun StandbyPanel(s: UiState, voice: () -> Unit, onDetails: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val rs = RouteSession
    val home = Places.loadSlot(ctx, "ev")
    val work = Places.loadSlot(ctx, "is")
    val saved = Places.savedList(ctx)
    val pos = s.pos
    val near = remember(pos?.lat?.times(100)?.toInt(), pos?.lon?.times(100)?.toInt(), CorridorStore.version.value) {
        if (pos == null) 0 else CorridorStore.all().count { Geo.dist(pos, it.from) < 30_000 }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nereye gidiyoruz?", color = BFg, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SharingBadge(s)
            Text(if (s.mode == "idle") "Konum kapalı" else s.gpsText.substringBefore(" ·").let { if (s.pos != null) "Konum hazır" else it },
                color = BMuted, fontSize = 14.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Row(Modifier.height(130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text("ANLIK HIZ", color = BMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(s.speedKmh?.toString() ?: "—", color = BFg, fontSize = 58.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.weight(1.6f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(if (s.mode == "bekle") "Hazır" else "Takip kapalı", color = if (s.mode == "bekle") BOk else BMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (s.mode == "bekle") "Hedef seçince ya da araç hareket edince sürüş kendiliğinden başlar."
                    else "“Sürüşü başlat”a dokun ya da bir hedef seç.",
                    color = BFg, fontSize = 15.sp
                )
                if (near > 0) Text("Yakınında (30 km) $near kayıtlı koridor var.", color = BWarn, fontSize = 14.sp)
            }
        }
        // Hızlı hedefler
        val tiles = buildList {
            add(Triple("Ev", home, "ev"))
            add(Triple("İş", work, "is"))
            saved.take(4).forEach { add(Triple(it.name, it, "yer")) }
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).take(if (Firma.enabled) 2 else 3).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, p, kind) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                                .background(if (p != null) BPanel else BBg).border(1.dp, if (kind == "yer") BBlue.copy(alpha = 0.5f) else BLine, RoundedCornerShape(18.dp))
                                .clickable(enabled = !rs.busy) {
                                    if (p != null) rs.go(ctx, p) else { Nav.big = false; Nav.tab = 1 }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text((if (kind == "ev") "🏠 " else if (kind == "is") "🏢 " else "📍 ") + label, color = BFg, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p?.let { if (kind == "yer") it.detail.ifBlank { "Kayıtlı yer" } else it.name } ?: "Kayıtlı değil · kaydetmek için dokun",
                                color = BMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        (rs.retryText ?: rs.msg)?.let {
            Text(it, color = if (rs.msgBad) BOver else BWarn, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        VehicleTypeSwitch(48, 16)
        TripTypeSwitch(48, 16)
        Row(Modifier.height(70.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = voice, modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) { Text("🎤 Konuş", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Button(onClick = { Nav.big = false; Nav.tab = 1 }, modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) { Text("🔎 Ara", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { if (hasLocation(ctx)) TrackingService.start(ctx) },
                modifier = Modifier.weight(1.3f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = BOk, contentColor = Color(0xFF06140C))
            ) { Text("Sürüşü başlat", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
