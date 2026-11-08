package com.xantildev.koridor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Renkli yolculuk kartları (Son yolculuklar) ve aylık özet. */

private val CInk = Color(0xFF0F172A)
private val CMuted = Color(0xFF475569)
private val CBlue = Color(0xFF2563EB)
private val CGreen = Color(0xFF16A34A)
private val CRed = Color(0xFFDC2626)
private val COrange = Color(0xFF9A3412)
private val COrangeBg = Color(0xFFFFEDD5)
private val MapTints = listOf(0xFFC7DDF7, 0xFFD7E9D2, 0xFFF3E3C3, 0xFFE3DAF5, 0xFFC9EEF2, 0xFFFBE0D3).map { Color(it) }

private val TR = Locale("tr", "TR")
private fun fmt(t: Long, p: String) = SimpleDateFormat(p, TR).format(t)

/** Yolculuğun başlığı: varılan kayıtlı yer, hedef ya da serbest sürüş. */
fun tripTitle(t: TripRecord): String = t.place.ifBlank { t.target ?: "" }.ifBlank { "Serbest sürüş" }

/** Kartta kırmızı çizilecek EDS parçaları: geçilen koridorların izdeki başı–sonu (indeks aralığı). */
fun edsRanges(t: TripRecord): List<Pair<IntRange, CorridorPass>> {
    if (t.track.size < 3 || t.passes.isEmpty()) return emptyList()
    val all = CorridorStore.all()
    return t.passes.mapNotNull { p ->
        val c = all.firstOrNull { it.name == p.name } ?: return@mapNotNull null
        val a = t.track.indices.minByOrNull { Geo.dist(t.track[it], c.from) } ?: return@mapNotNull null
        val b = t.track.indices.minByOrNull { Geo.dist(t.track[it], c.to) } ?: return@mapNotNull null
        if (Geo.dist(t.track[a], c.from) > 500 || Geo.dist(t.track[b], c.to) > 500 || b <= a) null else (a..b) to p
    }
}

@Composable
private fun RouteThumb(t: TripRecord, modifier: Modifier) {
    val pts = remember(t.id) { t.track.filterIndexed { i, _ -> i % maxOf(1, t.track.size / 300) == 0 } }
    val step = maxOf(1, t.track.size / 300)
    val eds = remember(t.id) { edsRanges(t).map { (r, _) -> (r.first / step)..(r.last / step) } }
    val tint = MapTints[(t.id.hashCode() and 0x7fffffff) % MapTints.size]
    Canvas(modifier.background(tint)) {
        // Kareli zemin (harita hissi)
        val g = 24.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Color.White.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1f); x += g }
        var y = 0f
        while (y < size.height) { drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f); y += g }
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val k = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * k).coerceAtLeast(1e-6)
        val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = 18.dp.toPx()
        val sc = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * sc) / 2
        val oy = (size.height - h * sc) / 2
        fun o(p: LatLng) = Offset((ox + (p.lon - minLon) * k * sc).toFloat(), (oy + (maxLat - p.lat) * sc).toFloat())
        fun path(r: IntRange): Path = Path().apply {
            r.forEachIndexed { i, idx -> val q = o(pts[idx.coerceIn(pts.indices)]); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
        }
        val full = path(pts.indices)
        drawPath(full, Color.White.copy(alpha = 0.6f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(full, CBlue, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        eds.forEach { r -> drawPath(path(r), CRed, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        drawCircle(Color.White, 8.dp.toPx(), o(pts.first())); drawCircle(CGreen, 5.5.dp.toPx(), o(pts.first()))
        drawCircle(Color.White, 8.dp.toPx(), o(pts.last())); drawCircle(CInk, 5.5.dp.toPx(), o(pts.last()))
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color, bold: Boolean = false) {
    Text(text, color = fg, fontSize = 13.sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun StatBox(value: String, label: String, fg: Color, bg: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = fg, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Bir yolculuk kartı: üstte rota çizimi ve rozetler, altta başlık, km/süre/ortalama, EDS ve ihlal. */
@Composable
fun TripCardView(t: TripRecord, onClick: () -> Unit) {
    val over = t.passes.count { p -> p.limit != null && p.avg > p.limit }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(110.dp)) {
            RouteThumb(t, Modifier.fillMaxSize())
            val badge = when (t.purpose) { "is" -> "İŞ" to CBlue; "sahsi" -> "ŞAHSİ" to CGreen; else -> null }
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badge?.let { (b, c) -> Pill(b, Color.White, c, bold = true) }
                if (t.sample) Pill("ÖRNEK", Color.White, Color(0xFF7C3AED), bold = true)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { Pill(fmt(t.start, "d MMM · HH:mm"), Color.White, Color(0xC70F172A)) }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((if (t.place.isNotBlank()) "📍 " else "") + tripTitle(t), color = CInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.plate.ifBlank { t.vehicleName }.ifBlank { null }, t.driver.ifBlank { null },
                "${fmt(t.start, "HH:mm")}–${fmt(t.end, "HH:mm")}").joinToString(" · "),
                color = CMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox((t.distM / 1000).let { if (it >= 10) it.roundToInt().toString() else String.format(TR, "%.1f", it) }, "KM",
                    Color(0xFF1D4ED8), Color(0xFFEFF6FF), Modifier.weight(1f))
                val m = (t.timeS / 60).roundToInt()
                StatBox("${m / 60}:${(m % 60).toString().padStart(2, '0')}", "SÜRE", Color(0xFF6D28D9), Color(0xFFF5F3FF), Modifier.weight(1f))
                StatBox(t.avg?.roundToInt()?.toString() ?: "—", "ORT km/s", Color(0xFF0E7490), Color(0xFFECFEFF), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (t.passes.isEmpty()) "EDS yok" else "${t.passes.size} EDS geçti", COrange, COrangeBg)
                if (over > 0) Pill("$over ihlal", Color.White, CRed, bold = true)
                else if (t.passes.isNotEmpty()) Pill("İhlal yok", Color(0xFF166534), Color(0xFFDCFCE7))
            }
            if (t.note.isNotBlank()) Text("“${t.note}”", color = CMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun tile(v: String, l: String, bg: Color, m: Modifier) {
    Column(m.clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp)) {
        Text(v, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(l, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Kendi aylık özetim: bu ay km, iş yolculuğu sayısı, ihlal. */
@Composable
fun MonthCard(all: List<TripRecord>) {
    val cal = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }
    val month = all.filter { !it.sample && it.start >= cal.timeInMillis }
    val km = month.sumOf { it.distM } / 1000
    val work = month.count { it.purpose == "is" }
    val over = month.sumOf { t -> t.passes.count { p -> p.limit != null && p.avg > p.limit } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fmt(System.currentTimeMillis(), "MMMM yyyy") + " · özetim", color = Color(0xFFE9EEEC), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tile(String.format(TR, "%,.0f", km), "km", CBlue, Modifier.weight(1f))
            if (Firma.enabled) tile("$work", "iş yolculuğu", Color(0xFF7C3AED), Modifier.weight(1f))
            else tile("${month.size}", "yolculuk", Color(0xFF7C3AED), Modifier.weight(1f))
            tile("$over", "ihlal", if (over > 0) CRed else CGreen, Modifier.weight(1f))
        }
    }
}
