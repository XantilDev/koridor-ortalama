package com.xantildev.koridor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val XBg = Color(0xFF0D1214)
private val XPanel = Color(0xFF151C1F)
private val XLine = Color(0xFF25302F)
private val XFg = Color(0xFFE9EEEC)
private val XMuted = Color(0xFF8C9A97)
private val XOk = Color(0xFF2FBF71)
private val XWarn = Color(0xFFF2B632)
private val XOver = Color(0xFFFF5A4F)
private val XBlue = Color(0xFF4C8DFF)

/** Seçenek düğmeleri (segment). */
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, height: Int = 52, fontSize: Int = 17, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(14.dp)).background(XPanel).border(1.dp, XLine, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (k, label) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) XFg else Color.Transparent)
                    .clickable { onPick(k) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) XBg else XFg, fontSize = fontSize.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 0.dp).align(Alignment.Center))
            }
        }
    }
}

/** Araç tipi: limitler hemen bu tipe göre uyarlanır. */
@Composable
fun VehicleTypeSwitch(height: Int = 52, fontSize: Int = 17) {
    val v = VehicleStore.active
    Segments(VehicleType.values().map { it.code to it.label.substringAfter("· ") }, v.type.code, height, fontSize) { code ->
        val t = VehicleType.of(code)
        if (t != v.type) VehicleStore.upsert(v.copy(type = t))
    }
}

/** Firma sürümü: Şahsi / İş seçimi ve sürücü. Store sürümünde hiçbir şey göstermez. */
@Composable
fun TripTypeSwitch(height: Int = 52, fontSize: Int = 17) {
    if (!Firma.enabled) return
    if (!VehicleStore.active.company) {
        Text("🔒 Şahsi araç · yolculukların yalnız bu cihazda kalır, firmaya hiçbir bilgi gitmez.", color = XMuted, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segments(listOf("sahsi" to "🔒 Şahsi", "is" to "💼 İş"), Firma.tripType, height, fontSize) { Firma.chooseTripType(it) }
        if (Firma.accounts.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sürücü:", color = XMuted, fontSize = 14.sp)
                Firma.accounts.forEach { a ->
                    val on = a.name == Firma.driver
                    Text(a.name, color = if (on) XBg else XFg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) XFg else XPanel)
                            .border(1.dp, XLine, RoundedCornerShape(99.dp)).clickable { Firma.selectDriver(a.name) }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Text(
            when {
                Firma.tripType == "sahsi" -> "Şahsi: yolculuk ve konum sadece bu cihazda kalır, firmaya gönderilmez."
                !Firma.connected -> "İş: firmaya bağlı değilsin; “Ayrıntılar”daki Firma bölümünden bağlan."
                else -> "İş · ${Firma.driver}: konum ve yolculuk yöneticiyle paylaşılır."
            },
            color = if (Firma.tripType == "is" && Firma.connected) XWarn else XMuted, fontSize = 13.sp
        )
    }
}

/** Konum paylaşılıyorsa küçük işaret. */
@Composable
fun SharingBadge(s: UiState) {
    if (!Firma.active || s.mode == "idle") return
    Text("📡 Konum paylaşılıyor · ${Firma.driver}", color = XWarn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(XWarn.copy(alpha = 0.12f)).border(1.dp, XWarn.copy(alpha = 0.4f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Koridora yaklaşırken büyük şerit. */
@Composable
fun ApproachBanner(s: UiState, big: Boolean = true) {
    val c = s.approachC ?: return
    val d = s.approachD ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(XWarn.copy(alpha = 0.16f)).border(2.dp, XWarn, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = if (big) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(Geo.fmtKm(d), color = XWarn, fontSize = if (big) 34.sp else 24.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text("sonra ortalama hız koridoru", color = XFg, fontSize = if (big) 18.sp else 15.sp, fontWeight = FontWeight.SemiBold)
            Text(c.name, color = XMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(if (big) 60.dp else 46.dp).clip(CircleShape).background(if (c.limit != null) Color.White else XPanel)
                .border(if (big) 8.dp else 6.dp, Color(0xFFD62B2B), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(c.limit?.toString() ?: "?", color = Color(0xFF111111), fontSize = if (big) 22.sp else 17.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Koridor dışında yolun harita limiti (küçük, gri çerçeveli: resmi değil). */
@Composable
fun RoadLimitSign(s: UiState, sizeDp: Int = 72) {
    val v = s.roadLimit ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(sizeDp.dp).clip(CircleShape).background(Color.White).border((sizeDp / 9).dp, Color(0xFFD62B2B).copy(alpha = 0.75f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(v.toString(), color = Color(0xFF111111), fontSize = (sizeDp / 2.6).sp, fontWeight = FontWeight.Bold) }
        Text("yol limiti (harita)", color = XMuted, fontSize = 11.sp)
    }
}

// ---------- Yolculuk özeti ----------

fun tripSummaryText(r: TripRecord): String {
    val df = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("tr", "TR"))
    val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr", "TR"))
    val sb = StringBuilder()
    sb.append("Yolculuk özeti · ").append(df.format(java.util.Date(r.start))).append("–").append(tf.format(java.util.Date(r.end))).append('\n')
    if (r.plate.isNotBlank() || r.vehicleName.isNotBlank()) sb.append("Araç: ").append(r.plate.ifBlank { r.vehicleName }).append(" (").append(r.vehicleType).append(")\n")
    r.target?.let { sb.append("Hedef: ").append(it).append('\n') }
    sb.append("Mesafe: ").append(Geo.fmtKm(r.distM)).append(" · Süre: ").append(Geo.fmtT(r.timeS)).append('\n')
    r.avg?.let { sb.append("Ortalama: ").append(it.roundToInt()).append(" km/s") }
    r.moveAvg?.let { sb.append(" · Hareket ort.: ").append(it.roundToInt()).append(" km/s") }
    sb.append('\n')
    if (r.passes.isNotEmpty()) {
        sb.append("Koridorlar:\n")
        r.passes.forEach { p ->
            val ok = p.limit == null || p.avg <= p.limit
            sb.append(if (ok) "✓ " else "⚠ ").append(p.name).append(": ").append(p.avg.roundToInt()).append(p.limit?.let { " / $it" } ?: "").append(" km/s\n")
        }
    } else sb.append("Ortalama hız koridoru geçilmedi.\n")
    return sb.toString().trim()
}

/** Sürüş bitince çıkan özet; İş yolculuğunda personel saha kaydına ekleme. */
@Composable
fun TripSummaryDialog() {
    val ctx = LocalContext.current
    val r by Engine.finished.collectAsStateWithLifecycle()
    val t = r ?: return
    var field by remember(t.id) { mutableStateOf(false) }
    if (field) {
        FieldRecordDialog(t) { field = false; Engine.finished.value = null }
        return
    }
    AlertDialog(
        onDismissRequest = { Engine.finished.value = null },
        title = { Text("Yolculuk özeti") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SumBox("MESAFE", Geo.fmtKm(t.distM), Modifier.weight(1f))
                    SumBox("SÜRE", Geo.fmtT(t.timeS), Modifier.weight(1f))
                    SumBox("ORTALAMA", t.avg?.let { "${it.roundToInt()} km/s" } ?: "—", Modifier.weight(1f))
                }
                if (t.passes.isEmpty()) Text("Ortalama hız koridoru geçilmedi.", color = XMuted, fontSize = 14.sp)
                t.passes.forEach { p ->
                    val ok = p.limit == null || p.avg <= p.limit
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ok) "✓" else "⚠", color = if (ok) XOk else XOver, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Text(p.name, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.avg.roundToInt()}" + (p.limit?.let { " / $it" } ?: ""), fontWeight = FontWeight.Bold, color = if (ok) XOk else XOver)
                    }
                }
                if (Firma.enabled) Text(
                    if (t.purpose == "is") "💼 İş yolculuğu · ${t.driver.ifBlank { "sürücü yok" }} — km raporuna işlendi." else "🔒 Şahsi yolculuk — firmaya gönderilmedi.",
                    color = XMuted, fontSize = 13.sp
                )
                if (Firma.enabled && t.purpose == "is" && Firma.connected) {
                    OutlinedButton(onClick = { field = true }, modifier = Modifier.fillMaxWidth()) { Text("Personel saha kaydına ekle") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tripSummaryText(t))
                ctx.startActivity(Intent.createChooser(i, "Özeti paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Paylaş", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { Engine.finished.value = null }) { Text("Kapat") } }
    )
}

@Composable
private fun SumBox(k: String, v: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(XBg).border(1.dp, XLine, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Text(k, color = XMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(v, color = XFg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** İş yolculuğundan AYSOFT Personel saha kaydı (tarih yolculuktan, firma hedeften). */
@Composable
private fun FieldRecordDialog(t: TripRecord, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var firma by remember { mutableStateOf(t.target ?: "") }
    var il by remember { mutableStateOf("") }
    var ilce by remember { mutableStateOf("") }
    var not by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) {
        val end = t.track.lastOrNull()
        if (end != null) il = runCatching { Places.provinceAt(end) }.getOrNull() ?: ""
    }
    val df = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("tr", "TR"))
    val d0 = df.format(java.util.Date(t.start)); val d1 = df.format(java.util.Date(t.end))
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Personel saha kaydı") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tarih: " + if (d0 == d1) d0 else "$d0–$d1", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = firma, onValueChange = { firma = it }, label = { Text("Firma / iş yeri") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = il, onValueChange = { il = it }, label = { Text("İl") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = ilce, onValueChange = { ilce = it }, label = { Text("İlçe") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = not, onValueChange = { not = it }, label = { Text("Açıklama / ne iş yapıldı") }, minLines = 2)
                Text("Kayıt ${t.driver} adına girilir; yönetici onayına düşer.", color = XMuted, fontSize = 12.sp)
                msg?.let { Text(it, color = if (ok) XOk else XOver, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !ok, onClick = {
                busy = true
                scope.launch {
                    val err = Firma.addFieldRecord(t, firma, il, ilce, not)
                    busy = false
                    ok = err == null
                    msg = err ?: "✓ Saha kaydı eklendi."
                    if (ok) Engine.say("Saha kaydı eklendi.")
                }
            }) { Text(if (busy) "Gönderiliyor…" else "Kaydet", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(if (ok) "Kapat" else "Vazgeç") } }
    )
}

// ---------- Ayarlar ve firma bağlantısı ----------

@Composable
fun SettingsCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(XPanel).border(1.dp, XLine, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Sesli bildirim", color = XFg, fontWeight = FontWeight.SemiBold)
        Segments(listOf("0" to "Her şey", "1" to "Sadece koridor", "2" to "Kapalı"), AppSettings.voiceLevel.toString(), 46, 15) { AppSettings.setVoice(it.toInt()) }
        Text("Gece modu (ekranı karartır)", color = XFg, fontWeight = FontWeight.SemiBold)
        Segments(listOf("oto" to "Otomatik", "acik" to "Açık", "kapali" to "Kapalı"), AppSettings.night, 46, 15) { AppSettings.chooseNight(it) }
        Text("Otomatik: 20:00–07:00 arası.", color = XMuted, fontSize = 12.sp)
    }
}

@Composable
fun FirmaCard() {
    if (!Firma.enabled) return
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var pick by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var agree by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(Firma.accounts.isEmpty()) }
    LaunchedEffect(adding) { if (adding && names.isEmpty()) names = runCatching { Firma.names() }.getOrDefault(emptyList()).also { if (it.isEmpty()) msg = "Çalışan listesi alınamadı (internet?)." } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(XPanel).border(1.dp, XLine, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Firma · AYSOFT Personel", color = XFg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "Bağlanınca “İş” seçili yolculuklarda konumun, hızın ve yolculuğun (km, süre, hedef) yöneticiyle paylaşılır. “Şahsi” yolculuklarda hiçbir bilgi gönderilmez. Paylaşım yalnız uygulama açıkken/sürüşte yapılır.",
            color = XMuted, fontSize = 13.sp
        )
        Firma.accounts.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (a.name == Firma.driver) "● " else "○ ") + a.name, color = XFg, fontSize = 16.sp, modifier = Modifier.weight(1f).clickable { Firma.selectDriver(a.name) })
                TextButton(onClick = { Firma.remove(a.name) }) { Text("Çıkar", color = XOver) }
            }
        }
        Firma.status?.let { Text(it, color = XWarn, fontSize = 13.sp) }
        if (Firma.active && Firma.lastSent > 0) {
            Text("Son gönderim: " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("tr", "TR")).format(java.util.Date(Firma.lastSent)), color = XMuted, fontSize = 12.sp)
        }
        if (!adding) {
            OutlinedButton(onClick = { adding = true }) { Text("+ Çalışan ekle (ortak araç için)") }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { n ->
                    val on = n == pick
                    Text(n, color = if (on) XBg else XFg, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(99.dp))
                        .background(if (on) XFg else XBg).border(1.dp, XLine, RoundedCornerShape(99.dp)).clickable { pick = n }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) }, label = { Text("Şifre (personel sitesindeki)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agree, onCheckedChange = { agree = it })
                Text("İş yolculuklarımda konumumun yöneticiyle paylaşılmasını kabul ediyorum.", fontSize = 13.sp, color = XFg)
            }
            Button(enabled = pick != null && pin.isNotBlank() && agree && !busy, onClick = {
                busy = true; msg = null
                scope.launch {
                    val err = Firma.login(pick!!, pin)
                    busy = false
                    if (err == null) { msg = "✓ ${pick} bağlandı."; pin = ""; pick = null; agree = false; adding = false } else msg = err
                }
            }) { Text(if (busy) "Bağlanıyor…" else "Bağlan") }
            if (Firma.accounts.isNotEmpty()) TextButton(onClick = { adding = false }) { Text("Vazgeç") }
        }
        msg?.let { Text(it, color = if (it.startsWith("✓")) XOk else XOver, fontSize = 14.sp) }
    }
}

// ---------- Haftalık özet ----------

@Composable
fun WeeklySummary(trips: List<TripRecord>) {
    val cal = java.util.Calendar.getInstance().apply {
        firstDayOfWeek = java.util.Calendar.MONDAY
        set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val since = cal.timeInMillis.let { if (it > System.currentTimeMillis()) it - 7L * 86400_000 else it }
    val w = trips.filter { !it.sample && it.start >= since }
    val passes = w.flatMap { it.passes }
    val over = passes.count { it.limit != null && it.avg > it.limit }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(XPanel).border(1.dp, XLine, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("BU HAFTA", color = XMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SumBox("MESAFE", Geo.fmtKm(w.sumOf { it.distM }), Modifier.weight(1f))
            SumBox("YOLCULUK", w.size.toString(), Modifier.weight(1f))
            SumBox("KORİDOR", passes.size.toString(), Modifier.weight(1f))
        }
        if (passes.isNotEmpty()) Text(
            if (over == 0) "✓ Bu hafta geçtiğin bütün koridorlarda limitin altındaydın." else "⚠ $over koridorda ortalaman limitin üstündeydi.",
            color = if (over == 0) XOk else XOver, fontSize = 14.sp
        )
    }
}
