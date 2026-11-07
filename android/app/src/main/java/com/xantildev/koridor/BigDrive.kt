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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
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

private val BBlue = Color(0xFF4C8DFF)
private val BGlass = Color(0xF00D1214)

/**
 * Sürüş ekranı (dikey ve yatay): harita bütün ekranı kaplar, sağ kenarda ince bir bilgi paneli durur
 * (ortalama, limit, anlık hız, kalan). "Detay" ile panelin yanında tahmin ve ayrıntı kartı açılır.
 */
@Composable
fun BigDrive(s: UiState, onClose: () -> Unit) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val ctx = LocalContext.current
    val voice = rememberVoice()
    val ver by CorridorStore.version.collectAsStateWithLifecycle()
    val c = s.corridor
    val inC = s.confirmed && c != null
    val lim = if (inC) c?.limit else null
    val avg = if (inC) s.avg else s.tripAvg
    val col = when {
        avg == null -> BMuted
        !inC || lim == null -> BOk
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
    var detail by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var settings by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (settings) QuickSettings(onClose = { settings = false }, onAll = { settings = false; onClose() })

    val screenW = cfg.screenWidthDp.dp
    val screenH = cfg.screenHeightDp.dp
    val target = when {
        !s.driving -> if (wide) (screenW * 0.38f).coerceIn(300.dp, 470.dp) else (screenW - 20.dp)
        wide -> (screenW * 0.2f).coerceIn(180.dp, 280.dp)
        else -> (screenW * 0.25f).coerceIn(96.dp, 140.dp)
    }
    val panelW by androidx.compose.animation.core.animateDpAsState(target, label = "panel")

    Box(Modifier.fillMaxSize().background(BBg)) {
        // ---- Harita: bütün ekran ----
        LiveMap(s, ver, controls = true, controlsLeft = true, nav = true, rightInset = panelW)

        // ---- Haritanın üstü (sol): yaklaşma, koridor durumu, rota seçenekleri ----
        val freeW = screenW - panelW - 28.dp
        if (s.driving) Column(
            Modifier.align(Alignment.TopStart).padding(10.dp).width(freeW.coerceAtLeast(120.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (s.approachC != null) ApproachBanner(s, big = wide)
            else {
                val topChip = when {
                    inC -> "● Koridordasın · ${Geo.fmtKm(s.remaining)} kaldı" + if (s.partial) " · kısmi" else ""
                    c != null -> "● Koridor girişi · yön doğrulanıyor"
                    next != null && pos != null -> "Sıradaki koridor ${Geo.fmtKm(Geo.dist(pos, next.from))} · ${next.limit?.let { "limit $it" } ?: "limit ?"}"
                    else -> null
                }
                topChip?.let { MapChip(it, Modifier, if (inC || c != null) BWarn else BFg) }
            }
            if (s.trip.dist < 5000 && RouteSession.alts.size > 1) RouteChoices(compact = true)
        }
        if (s.driving && route != null && remTot != null && !detail) {
            val eta = clock(remTot / (cruise / 3.6))
            Column(
                Modifier.align(Alignment.BottomStart).padding(10.dp).widthIn(max = freeW.coerceAtLeast(120.dp))
                    .clip(RoundedCornerShape(18.dp)).background(BGlass).border(1.dp, BLine, RoundedCornerShape(18.dp))
                    .clickable { detail = true }.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(etaText(remTot / (cruise / 3.6)),
                    color = BOk, fontSize = if (wide) 26.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${Geo.fmtKm(remTot)} · varış $eta", color = BFg, fontSize = if (wide) 17.sp else 15.sp, maxLines = 1)
                Text(route.target.name, color = BMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        s.arrived?.let { MapChip("Hedefe vardın: $it", Modifier.align(Alignment.Center), BOk) }

        // ---- Sağ panel ----
        val panelMod = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(panelW).padding(top = 8.dp, bottom = 8.dp, end = 8.dp)
            .clip(RoundedCornerShape(22.dp)).background(BGlass).border(1.dp, BLine, RoundedCornerShape(22.dp))
        if (!s.driving) {
            StandbyPanel(s, voice, onClose, panelMod.padding(12.dp))
            GearButton(Modifier.align(Alignment.TopStart).padding(10.dp)) { settings = true }
            return@Box
        }
        SidePanel(s, inC, c, lim, avg, col, next, wide, panelW, voice, detail, { detail = !detail }, { settings = true }, panelMod)

        // ---- Detay kartı (panelin solunda) ----
        if (detail) {
            val dMod = if (wide) Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 8.dp, end = panelW + 6.dp)
                .width((screenW * 0.42f).coerceIn(300.dp, 520.dp)).fillMaxHeight()
            else Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp)
                .width(screenW - panelW - 14.dp).heightIn(max = screenH * 0.7f)
            DetailCard(s, inC, c, route, remTot, cruise, wide, onClose, { detail = false },
                dMod.clip(RoundedCornerShape(22.dp)).background(BGlass).border(1.dp, BBlue.copy(alpha = 0.5f), RoundedCornerShape(22.dp)))
        }
    }
}

/** Sağdaki ince panel: ortalama halkası, limit, anlık hız, kalan/pay, düğmeler. */
@Composable
private fun SidePanel(
    s: UiState, inC: Boolean, c: Corridor?, lim: Int?, avg: Double?, col: Color, next: Corridor?,
    wide: Boolean, panelW: androidx.compose.ui.unit.Dp, voice: () -> Unit, detail: Boolean, onDetail: () -> Unit, onSettings: () -> Unit, modifier: Modifier
) {
    val ctx = LocalContext.current
    val roomy = panelW >= 170.dp
    Column(
        modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (roomy) 10.dp else 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { GearButton(Modifier) { onSettings() } }
        Text(
            when {
                inC && s.partial -> "ORTALAMA\n(BURADAN)"
                inC -> "EDS\nORTALAMA"
                c != null -> "KORİDOR\nGİRİŞİ"
                else -> "GENEL\nORTALAMA"
            },
            color = if (inC) BWarn else BMuted, fontSize = if (roomy) 13.sp else 11.sp, lineHeight = if (roomy) 15.sp else 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center
        )
        // Ortalama halkası
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth(if (roomy) 0.86f else 1f).aspectRatio(1f).clip(CircleShape).background(BPanel)
                .border(if (roomy) 6.dp else 4.dp, if (avg == null) BLine else col, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val big = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * 0.38f).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(avg?.roundToInt()?.toString() ?: "—", color = if (avg == null) BMuted else if (inC) col else BFg,
                    fontSize = big, lineHeight = big, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("km/s ort", color = BMuted, fontSize = if (roomy) 13.sp else 10.sp, maxLines = 1)
            }
        }
        if (inC) LinearProgressIndicator(
            progress = { s.progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = BWarn, trackColor = BLine
        )
        // Anlık hız + limit
        val sp = s.speedKmh
        val signSize = if (roomy) 76 else 62
        if (roomy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeedBox(sp, lim, roomy, Modifier.weight(1f)); LimitSign(s, lim, signSize, roomy)
        } else { SpeedBox(sp, lim, roomy, Modifier.fillMaxWidth()); LimitSign(s, lim, signSize, roomy) }
        // Kalan / pay ya da sıradaki
        if (inC) {
            PanelLine("KALAN", Geo.fmtKm(s.remaining), null, roomy)
            PanelLine("PAY", s.slack?.let { Geo.fmtSlack(it) } ?: "—", s.slack?.let { if (it >= 0) BOk else BOver }, roomy)
        } else {
            val pos = s.pos
            if (next != null && pos != null) PanelLine("SIRADAKİ", Geo.fmtKm(Geo.dist(pos, next.from)), BWarn, roomy)
            PanelLine("YOL", Geo.fmtKm(s.trip.dist), null, roomy)
        }
        (s.arrived?.let { "Hedefe vardın" } ?: s.note)?.let {
            Text(it, color = BWarn, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(2.dp))
        // Düğmeler
        val bh = if (roomy) 52.dp else 46.dp
        val fs = if (roomy) 17.sp else 14.sp
        Button(onClick = onDetail, modifier = Modifier.fillMaxWidth().height(bh), contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (detail) BBlue else BPanel, contentColor = BFg)) {
            Text(if (detail) "Kapat ›" else "‹ Detay", fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Button(onClick = voice, modifier = Modifier.fillMaxWidth().height(bh), contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) {
            Text(if (roomy) "🎤 Konuş" else "🎤", fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Button(onClick = { TrackingService.end(ctx) }, modifier = Modifier.fillMaxWidth().height(bh), contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BOver, contentColor = Color.White)) {
            Text(if (roomy) "Sürüşü bitir" else "Bitir", fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun LimitSign(s: UiState, lim: Int?, signSize: Int, roomy: Boolean) {
    if (lim == null && s.roadLimit != null) RoadLimitSign(s, signSize) else Box(
        Modifier.size(signSize.dp).clip(CircleShape).background(if (lim != null) Color.White else BPanel)
            .border(if (roomy) 9.dp else 7.dp, if (lim != null) BRed else BLine, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(lim?.toString() ?: "—", color = if (lim != null) Color(0xFF111111) else BMuted, fontSize = if (roomy) 28.sp else 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SpeedBox(sp: Int?, lim: Int?, roomy: Boolean, m: Modifier) {
    Column(m.clip(RoundedCornerShape(14.dp)).background(BPanel).border(1.dp, BLine, RoundedCornerShape(14.dp)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ANLIK", color = BMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(sp?.toString() ?: "—", fontSize = if (roomy) 34.sp else 28.sp, lineHeight = if (roomy) 36.sp else 30.sp, fontWeight = FontWeight.Bold,
            color = if (sp != null && lim != null && sp > lim) BOver else BFg, maxLines = 1)
    }
}

private fun etaText(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk"
}

@Composable
private fun GearButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(46.dp).clip(CircleShape).background(BPanel).border(1.dp, BLine, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text("⚙", color = BFg, fontSize = 24.sp) }
}

@Composable
private fun PanelLine(k: String, v: String, color: Color?, roomy: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(k, color = BMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1)
        Text(v, color = color ?: BFg, fontSize = if (roomy) 24.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Detay kartı: koridor/yolculuk ayrıntısı, tahmini ortalama, rota, uygulamaya dönüş. */
@Composable
private fun DetailCard(
    s: UiState, inC: Boolean, c: Corridor?, route: RouteSession.Info?, remTot: Double?, cruise: Double,
    wide: Boolean, onApp: () -> Unit, onHide: () -> Unit, modifier: Modifier
) {
    Column(
        modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when { c != null -> c.name; s.driving -> "Serbest sürüş"; else -> "Sürüş başlamadı" },
                    color = BFg, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (inC && c != null) "Ortalama hız koridoru · ${Geo.fmtKm(c.lengthM)}" + (if (s.partial) " · kısmi ölçüm" else "")
                    else "Koridor dışında · genel ortalaman gösteriliyor",
                    color = BMuted, fontSize = 13.sp, maxLines = 2
                )
            }
            Text("✕", color = BFg, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { onHide() }.padding(10.dp))
        }
        if (Firma.enabled) TripTypeChip()
        RouteSession.enforcement?.text?.let { Text("🚓 $it", color = BWarn, fontSize = 14.sp) }
        val tiles = if (inC) listOf(
            Triple("KALAN", Geo.fmtKm(s.remaining), null),
            Triple("ZAMAN PAYI", s.slack?.let { Geo.fmtSlack(it) } ?: "—", s.slack?.let { if (it >= 0) BOk else BOver }),
            Triple("EN FAZLA ORT.", s.maxAvgLeft, null),
            Triple("EDS İÇİ BEKLEME", Geo.fmtT(s.corridorStop), if (s.corridorStop > 0) BWarn else null)
        ) else listOf(
            Triple("MESAFE", Geo.fmtKm(s.trip.dist), null),
            Triple("SÜRE", Geo.fmtT(s.trip.time), null),
            Triple("HAREKET ORT.", s.moveAvg?.let { "${it.roundToInt()} km/s" } ?: "—", null),
            Triple("BEKLEME", Geo.fmtT(s.trip.stop), null)
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v, cl) -> WideTile(k, v, cl, Modifier.weight(1f)) }
            }
        }
        Estimate(s, inC, c, remTot, cruise, false, Modifier.fillMaxWidth())
        if (route != null && remTot != null) {
            Text("Hedef: ${route.target.name} · kalan ${Geo.fmtKm(remTot)} · rotada ${route.corridors.size} koridor",
                color = BFg, fontSize = 14.sp)
        }
        Button(onClick = onApp, modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BPanel, contentColor = BFg)) {
            Text("Uygulama ekranı (yolculuklar, garaj, ayarlar)", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun Estimate(s: UiState, inC: Boolean, c: Corridor?, remTot: Double?, cruise: Double, compact: Boolean, modifier: Modifier) {
    val lim = if (inC) c?.limit else null
    val maxV = if (lim != null) kotlin.math.floor(lim * 1.1).toInt() else 140
    val minV = 40
    var v by androidx.compose.runtime.saveable.rememberSaveable(c?.key ?: "serbest") {
        androidx.compose.runtime.mutableFloatStateOf((lim ?: cruise.roundToInt().coerceIn(minV, maxV)).toFloat())
    }
    val vi = v.roundToInt().coerceIn(minV, maxV)
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(BPanel).border(1.dp, BBlue.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (!compact) Text("TAHMİNİ ORTALAMA", color = BBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (inC && lim == null) {
            Text("Bu koridorun limiti bilinmiyor. “Ayrıntılar”dan tabelaya dokunup levhadaki limiti girersen tahmin burada çıkar.", color = BFg, fontSize = 15.sp)
            return@Column
        }
        if (!inC && remTot == null) {
            Text("Hedef seçilmedi. Varış tahmini için “Konuş”a basıp gideceğin yeri söyle ya da “Nereye?”den seç.", color = BFg, fontSize = 15.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (compact) Text("TAHMİN  ", color = BBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(if (inC) "Kalan ${Geo.fmtKm(s.remaining)}'yi " else "Kalan ${remTot?.let { Geo.fmtKm(it) } ?: "yolu"} ", color = BMuted, fontSize = 16.sp)
            Text("$vi km/s", color = if (lim != null && vi > lim) BWarn else BFg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
        Text(v, color = color ?: BFg, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            if (Firma.enabled) TripTypeChip()
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
