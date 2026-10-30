package com.xantildev.koridor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

private val Bg = Color(0xFF0D1214)
private val Panel = Color(0xFF151C1F)
private val Line = Color(0xFF25302F)
private val Fg = Color(0xFFE9EEEC)
private val Muted = Color(0xFF8C9A97)
private val SignRed = Color(0xFFD62B2B)
private val Ok = Color(0xFF2FBF71)
private val Warn = Color(0xFFF2B632)
private val Over = Color(0xFFFF5A4F)
private val Accent = Warn
private val Card = RoundedCornerShape(14.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF1A1405),
                    background = Bg, onBackground = Fg,
                    surface = Panel, onSurface = Fg,
                    surfaceVariant = Panel, onSurfaceVariant = Muted,
                    outline = Line
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Root() }
            }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val body = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = if (!subject.isNullOrBlank() && !body.contains(subject)) subject + "\n" + body else body
        i.action = null
        RouteSession.fromShared(this, text)
    }
}

private fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun perms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

@Composable
private fun Root() {
    val tab = Nav.tab
    val s by Engine.state.collectAsStateWithLifecycle()
    val ver by CorridorStore.version.collectAsStateWithLifecycle()
    CrashReport()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        StatusLine(s, ver)
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Fg) {
            listOf("Sürüş", "Nereye?", "Yolculuk", "Koridor").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { Nav.tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) })
            }
        }
        when (tab) {
            0 -> DriveTab(s, ver) { Nav.tab = 1 }
            1 -> RouteTab()
            2 -> TripsTab(s)
            else -> ListTab(ver)
        }
    }
}

/** Önceki çalıştırmada çökme olduysa hatayı gösterir; kopyalayıp gönderilebilir. */
@Composable
private fun CrashReport() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hata", Context.MODE_PRIVATE) }
    var text by remember { mutableStateOf(prefs.getString("son", null)) }
    val t = text ?: return
    fun close() {
        prefs.edit().remove("son").apply()
        text = null
    }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Uygulama son açılışta kapandı") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“Kopyala”ya basıp bu metni sohbete yapıştırırsan sebebini bulup düzeltirim.", color = Fg, fontSize = 14.sp)
                Text(t.take(1500), color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Koridor hata", t))
                close()
            }) { Text("Kopyala") }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text("Kapat") } }
    )
}

@Composable
private fun StatusLine(s: UiState, ver: Int) {
    val count = remember(ver) { CorridorStore.all().size }
    val dot = when {
        s.mode == "sim" -> Accent
        s.mode == "gps" && s.pos != null -> Ok
        s.mode == "gps" -> Muted
        else -> Line
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(
            "  ${VehicleStore.active.plate.ifBlank { VehicleStore.active.name }} · ${s.gpsText} · $count koridor · ${s.speedKmh ?: "—"} km/s",
            color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------- SÜRÜŞ ----------------

@Composable
private fun DriveTab(s: UiState, ver: Int, onGoRoute: () -> Unit) {
    val ctx = LocalContext.current
    var limitFor by remember { mutableStateOf<Corridor?>(null) }
    var voice by remember { mutableStateOf(Engine.voiceOn) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            TrackingService.start(ctx)
        } else {
            Engine.gpsStatus("Konum izni verilmedi")
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val route = RouteSession.info
        if (route == null) {
            OutlinedButton(onClick = onGoRoute, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Nereye gidiyorsun?  Adres, firma ya da yer seç", color = Fg)
            }
        }
        Hud(s) { limitFor = it }
        if (route != null) TripCard(s, route, onGoRoute)
        if (s.confirmed && s.corridor != null) WhatIf(s) { limitFor = it }
        Box(Modifier.fillMaxWidth().height(260.dp).clip(Card).border(1.dp, Line, Card)) { LiveMap(s, ver) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.mode == "idle") {
                Button(
                    onClick = { if (hasLocation(ctx)) TrackingService.start(ctx) else launcher.launch(perms()) },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("GPS ile başlat", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { Engine.startSim() }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Örnek sürüş", color = Fg)
                }
            } else {
                Button(
                    onClick = { if (s.mode == "gps") TrackingService.stop(ctx) else Engine.stop() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Line, contentColor = Fg)
                ) { Text("Durdur", fontWeight = FontWeight.Bold) }
            }
        }
        MarkBox(s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sesli bildirim", color = Fg, modifier = Modifier.weight(1f))
            Switch(checked = voice, onCheckedChange = { voice = it; Engine.voiceOn = it })
        }
        Text(
            "Zaman payı, ortalamanı limitte tutman için elinde kalan saniyedir. Ölçüm GPS'e dayanır, resmi cihazla birebir aynı olmayabilir; levhalar ve yasal limit esastır.",
            color = Muted, fontSize = 13.sp
        )
    }
    limitFor?.let { c ->
        LimitDialog(c, onDismiss = { limitFor = null }) { v ->
            Engine.setLimit(c, v)
            limitFor = null
        }
    }
}

@Composable
private fun Hud(s: UiState, onSign: (Corridor) -> Unit) {
    val c = s.corridor
    val inC = s.confirmed && c != null
    val title = when {
        inC -> c?.name ?: ""
        c != null -> "Girişte, yön doğrulanıyor…"
        s.mode != "idle" -> "Serbest sürüş"
        s.trip.time > 0 -> "Son yolculuk"
        else -> "Koridor dışındasınız"
    }
    val sub = when {
        inC && c != null -> Geo.fmtKm(c.lengthM) + " koridor · " + c.sourceLabel +
            if (VehicleStore.type.commercial) " · ${VehicleStore.type.label} limiti" else ""
        c != null -> c.name
        s.mode != "idle" -> "Koridor yok · yolculuk ortalaman gösteriliyor"
        else -> "Başlatınca yolculuk ortalaman hesaplanır; koridora girince koridor ölçümüne geçer."
    }
    val lim = if (inC) c?.limit else null
    val big = if (inC) s.avg else s.tripAvg
    val col = when {
        big == null -> Muted
        !inC || lim == null -> Fg
        big > lim -> Over
        big > lim * 0.95 -> Warn
        else -> Ok
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = Fg, fontSize = 16.sp)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(big?.roundToInt()?.toString() ?: "—", fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.Bold, color = col)
                Text(
                    if (inC) "KORİDOR ORTALAMAN · KM/S" else "YOLCULUK ORTALAMAN · KM/S",
                    color = Muted, fontSize = 12.sp, letterSpacing = 1.sp
                )
            }
            SpeedSign(lim, clickable = inC) { if (c != null && inC) onSign(c) }
        }
        LinearProgressIndicator(
            progress = { s.progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Accent, trackColor = Line
        )
        if (inC) {
            CellGrid(
                listOf(
                    Triple("Kalan", Geo.fmtKm(s.remaining), null),
                    Triple("EDS içi süre", Geo.fmtT(s.elapsed), null),
                    Triple("Zaman payı", s.slack?.let { Geo.fmtSlack(it) } ?: "—", s.slack?.let { if (it >= 0) Ok else Over }),
                    Triple("Kalan için ort. üst sınır", s.maxAvgLeft, null),
                    Triple("EDS içi bekleme", Geo.fmtT(s.corridorStop), if (s.corridorStop > 0) Warn else null),
                    Triple("Anlık hız", "${s.speedKmh ?: "—"} km/s", null)
                )
            )
        } else {
            CellGrid(
                listOf(
                    Triple("Mesafe", Geo.fmtKm(s.trip.dist), null),
                    Triple("Süre", Geo.fmtT(s.trip.time), null),
                    Triple("EDS dışı bekleme", Geo.fmtT(s.trip.stopOut), null),
                    Triple("EDS içi bekleme", Geo.fmtT(s.trip.stopIn), null),
                    Triple("Hareket ortalaması", s.moveAvg?.let { "${it.roundToInt()} km/s" } ?: "—", null),
                    Triple("Anlık hız", "${s.speedKmh ?: "—"} km/s", null)
                )
            )
        }
        s.approach?.let { NoteBox(it, Accent) }
        s.note?.let { NoteBox(it, if (s.confirmed) Accent else Line) }
    }
}

@Composable
private fun SpeedSign(limit: Int?, clickable: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(96.dp).clip(CircleShape)
            .background(if (limit != null) Color.White else Panel)
            .border(11.dp, if (limit != null) SignRed else Line, CircleShape)
            .clickable(enabled = clickable, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            limit?.toString() ?: if (clickable) "?" else "—",
            color = if (limit != null) Color(0xFF111111) else Muted,
            fontSize = 34.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun Cells(
    a: Pair<String, String>, b: Pair<String, String>, c: Pair<String, String>, d: Pair<String, String>,
    thirdColor: Color? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(a, Modifier.weight(1f))
            Cell(b, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(c, Modifier.weight(1f), thirdColor)
            Cell(d, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CellGrid(items: List<Triple<String, String, Color?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v, c) -> Cell(k to v, Modifier.weight(1f), c) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Cell(p: Pair<String, String>, modifier: Modifier, valueColor: Color? = null) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Bg).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(p.first.uppercase(), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.second, color = valueColor ?: Fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun NoteBox(text: String, border: Color) {
    Text(
        text, color = Fg, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg)
            .border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp)
    )
}

@Composable
private fun WhatIf(s: UiState, onSetLimit: (Corridor) -> Unit) {
    val c = s.corridor ?: return
    val lim = c.limit
    if (lim == null) {
        Column(
            Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Warn, Card).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("EDS SONUNA KADAR TAHMİN", color = Muted, fontSize = 11.sp)
            Text("Bu koridorun limiti bilinmiyor. Levhadaki limiti girersen kalan kısım için ortalama tahmini burada çıkar.", color = Fg, fontSize = 14.sp)
            Button(onClick = { onSetLimit(c) }) { Text("Limiti gir") }
        }
        return
    }
    val maxV = floor(lim * 1.1).toInt()
    var v by remember(c.key) { mutableFloatStateOf(lim.toFloat()) }
    val vi = v.roundToInt().coerceIn(30, maxV)
    val tRem = s.remaining / (vi / 3.6)
    val fin = (s.traveled + s.remaining) / (s.elapsed + tRem) * 3.6
    val tol = lim * 1.1
    val finColor = when {
        fin > tol -> Over
        fin > lim -> Warn
        else -> Ok
    }
    val status = when {
        fin > tol -> "ceza eşiğinin üstünde"
        fin > lim -> "limitin üstünde, tolerans içinde"
        else -> "limitin altında biter"
    }
    Column(
        Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Line, Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EDS SONUNA KADAR BU HIZLA GİDERSEM", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                "$vi km/s" + if (vi > lim) " · limit üstü" else "",
                color = if (vi > lim) Warn else Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp
            )
        }
        Slider(
            value = v, onValueChange = { v = it }, valueRange = 30f..maxV.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = if (vi > lim) Warn else Accent,
                activeTrackColor = if (vi > lim) Warn else Accent,
                inactiveTrackColor = Line
            )
        )
        Row {
            Text("30", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$lim limit", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$maxV tolerans", color = Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("EDS bitiş ortalaman ≈ ", color = Fg)
            Text("${fin.roundToInt()} km/s", color = finColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text("EDS'de kalan ${Geo.fmtKm(s.remaining)} · yaklaşık ${Geo.fmtT(tRem)} dk · $status", color = Muted, fontSize = 13.sp)
        if (s.corridorStop > 0) Text("Bu EDS içinde ${Geo.fmtT(s.corridorStop)} bekledin; bekleme ortalamayı düşürür ve hesaba dahil.", color = Muted, fontSize = 12.sp)
        if (vi > lim) {
            Text(
                "Tolerans ölçüm payıdır; levhadaki limit $lim. Koridor içinde anlık radar da olabilir.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun LiveMap(s: UiState, ver: Int) {
    val ctx = LocalContext.current
    val holder = remember { MapHolder(ctx) }
    var follow by remember { mutableStateOf(true) }
    holder.onUserPan = { follow = false }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.map }, modifier = Modifier.fillMaxSize(), update = { holder.update(s, ver, follow) })
        if (!follow) {
            Button(
                onClick = { follow = true; holder.recenter(s) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
            ) { Text("Konumuma dön") }
        }
    }
    DisposableEffect(Unit) {
        holder.map.onResume()
        onDispose { holder.map.onPause() }
    }
}

@Composable
private fun MarkBox(s: UiState) {
    var lim by remember { mutableStateOf("90") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(s.pendingMark?.id) { s.pendingMark?.limit?.let { lim = it.toString() } }
    Column(
        Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Line, Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Burada koridor var mı?", fontWeight = FontWeight.Bold, color = Fg)
        Text(s.markText, color = Accent, fontSize = 13.sp)
        Text(
            "İlk kamerayı geçerken “Başı burası”na, son kamerayı geçerken “Sonu burası”na dokun (yolcu koltuğundan). Bir dahaki geçişte otomatik tanınır.",
            color = Muted, fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Engine.markStart() }, enabled = s.mode != "idle", modifier = Modifier.weight(1f)) {
                Text("Başı burası", color = Fg)
            }
            OutlinedButton(onClick = { Engine.markEnd() }, enabled = s.markActive, modifier = Modifier.weight(1f)) {
                Text("Sonu burası", color = Fg)
            }
        }
        if (s.pendingMark != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lim, onValueChange = { lim = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Limit") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Ad (isteğe bağlı)") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(onClick = {
                Engine.saveMark(lim.toIntOrNull()?.takeIf { it in 20..160 }, name.trim().ifBlank { null })
                name = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
        }
    }
}

@Composable
private fun LimitDialog(c: Corridor, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var t by remember { mutableStateOf(c.limit?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Koridor limiti") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, color = Muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = t, onValueChange = { t = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Levhadaki limit (km/s)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { t.toIntOrNull()?.takeIf { it in 20..160 }?.let(onSave) }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

// ---------------- ROTA ----------------

@Composable
private fun <T> Picker(
    label: String, items: List<T>, selected: T?, text: (T) -> String,
    modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let(text) ?: label, color = if (selected != null) Fg else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(text(item)) }, onClick = { open = false; onPick(item) })
            }
        }
    }
}

@Composable
private fun RouteTab() {
    val ctx = LocalContext.current
    var home by remember { mutableStateOf(Places.loadSlot(ctx, "ev")) }
    var work by remember { mutableStateOf(Places.loadSlot(ctx, "is")) }
    var manual by remember { mutableStateOf(false) }
    val rs = RouteSession
    val info = rs.info

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Nereye gidiyorsun?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotButton("Ev", home, Modifier.weight(1f), enabled = !rs.busy) {
                home?.let { rs.go(ctx, it) } ?: run { rs.msg = "Ev kayıtlı değil. Adresini arayıp sonuçta “Ev yap”a dokun."; rs.msgBad = true }
            }
            SlotButton("İş", work, Modifier.weight(1f), enabled = !rs.busy) {
                work?.let { rs.go(ctx, it) } ?: run { rs.msg = "İş kayıtlı değil. Adresini arayıp sonuçta “İş yap”a dokun."; rs.msgBad = true }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rs.query, onValueChange = { rs.query = it },
                label = { Text("Adres ya da yer") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { rs.search(ctx) }, enabled = !rs.busy, modifier = Modifier.height(56.dp)) { Text("Ara") }
        }
        Text(
            "Firmalar için en iyisi: Google Haritalar'da firmayı aç → Paylaş → Koridor Ortalama. Uygulama konumu oradan alır.",
            color = Accent, fontSize = 13.sp
        )
        rs.msg?.let { NoteBox(it, if (rs.msgBad) Over else Line) }
        rs.results.forEach { p ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(p.name, color = Fg, fontWeight = FontWeight.SemiBold)
                if (p.detail.isNotBlank()) Text(p.detail, color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { rs.go(ctx, p) }, enabled = !rs.busy, modifier = Modifier.weight(1.4f)) { Text("Git") }
                    OutlinedButton(onClick = { Places.saveSlot(ctx, "ev", p); home = p; rs.msg = "Ev kaydedildi: ${p.name}"; rs.msgBad = false }, modifier = Modifier.weight(1f)) { Text("Ev yap", color = Fg) }
                    OutlinedButton(onClick = { Places.saveSlot(ctx, "is", p); work = p; rs.msg = "İş kaydedildi: ${p.name}"; rs.msgBad = false }, modifier = Modifier.weight(1f)) { Text("İş yap", color = Fg) }
                }
            }
        }
        if (info != null) {
            Column(
                Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Accent, Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Hedef: ${info.target.name}", color = Fg, fontWeight = FontWeight.Bold)
                Text("Başlangıç: ${info.startLabel} (yeşil nokta) · Hedef: ${info.targetLabel} (mavi nokta)", color = Muted, fontSize = 12.sp)
                if (info.lineSource == "düz") Text("Yol çizgisi alınamadı; haritadaki mavi çizgi düz bağlantıdır.", color = Muted, fontSize = 12.sp)
                Text(
                    "${Geo.fmtKm(info.totalM)} · yaklaşık ${durText(info.durationS)} · " +
                        if (info.corridors.isEmpty()) "kayıtlı koridor yok" else "${info.corridors.size} koridor, toplam ${Geo.fmtKm(info.corridors.sumOf { it.lengthM })}",
                    color = Muted, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Nav.tab = 0 }, modifier = Modifier.weight(1f)) { Text("Sürüşe dön") }
                    OutlinedButton(onClick = { RouteSession.openInMaps(ctx, info.target) }, modifier = Modifier.weight(1f)) { Text("Yol tarifi (Haritalar)", color = Fg) }
                }
                TextButton(onClick = { rs.clear() }) { Text("Hedefi kaldır", color = Over) }
                if (info.corridors.isNotEmpty()) {
                    Text("Hepsi kaydedildi; sürüşte otomatik tanınır, yaklaşırken sesli uyarı verilir.", color = Muted, fontSize = 13.sp)
                }
            }
            info.corridors.forEach { CorridorRow(it) }
        }
        Text(
            "Koridorlar ve limitleri EGM Trafik Başkanlığı'nın sitesinden (trafik.gov.tr), adres araması ve rota çizgisi OpenStreetMap'ten gelir.",
            color = Muted, fontSize = 12.sp
        )
        TextButton(onClick = { manual = !manual }) { Text(if (manual) "İl/ilçe ile seçimi gizle" else "İl/ilçe ile seç", color = Accent) }
        if (manual) ManualRoute()
    }
}

private fun durText(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk"
}

@Composable
private fun TripCard(s: UiState, info: RouteSession.Info, onGoRoute: () -> Unit) {
    val pos = if (s.mode == "gps") s.pos else null
    val rem = if (pos != null) info.remainingFrom(pos) else info.totalM
    val v = s.moveAvg?.takeIf { it > 20 } ?: (info.totalM / info.durationS * 3.6)
    val tRem = rem / (v / 3.6)
    val arrive = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr", "TR"))
        .format(java.util.Date(System.currentTimeMillis() + (tRem * 1000).toLong()))
    val next = if (pos == null) null else info.corridors
        .filter { Geo.dist(pos, it.from) > 50 }
        .minByOrNull { Geo.dist(pos, it.from) }
    Column(
        Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Line, Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HEDEF", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(info.target.name, color = Fg, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onGoRoute) { Text("Değiştir", color = Accent) }
        }
        if (rem < 150 && pos != null) {
            Text("Vardın.", color = Ok, fontWeight = FontWeight.Bold)
        } else {
            Text("Kalan ${Geo.fmtKm(rem)} · tahmini varış $arrive", color = Fg)
        }
        if (next != null && pos != null) {
            Text(
                "Sıradaki EDS: ${next.name} · ${Geo.fmtKm(Geo.dist(pos, next.from))} · limit ${next.limit ?: "?"}",
                color = Muted, fontSize = 13.sp
            )
        } else if (info.corridors.isNotEmpty() && pos == null) {
            Text("Rotada ${info.corridors.size} EDS koridoru var. GPS ile başlatınca sıradaki gösterilir.", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SlotButton(label: String, p: Places.Place?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(60.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, color = Fg, fontWeight = FontWeight.Bold)
            Text(p?.name ?: "Kayıtlı değil", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ManualRoute() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("rota", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var cities by remember { mutableStateOf<List<TrafikApi.City>>(emptyList()) }
    var fromCity by remember { mutableStateOf<TrafikApi.City?>(null) }
    var toCity by remember { mutableStateOf<TrafikApi.City?>(null) }
    var fromDs by remember { mutableStateOf<List<TrafikApi.District>>(emptyList()) }
    var toDs by remember { mutableStateOf<List<TrafikApi.District>>(emptyList()) }
    var fromD by remember { mutableStateOf<TrafikApi.District?>(null) }
    var toD by remember { mutableStateOf<TrafikApi.District?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<List<Corridor>?>(null) }

    LaunchedEffect(Unit) {
        try {
            cities = TrafikApi.cities()
            val fc = prefs.getInt("fromCity", -1)
            cities.find { it.id == fc }?.let { c ->
                fromCity = c
                fromDs = TrafikApi.districts(c.id)
                val fd = prefs.getInt("fromDistrict", -1)
                fromD = fromDs.find { it.id == fd }
            }
        } catch (e: Exception) {
            msg = "trafik.gov.tr'ye ulaşılamadı (${e.message ?: "bağlantı hatası"})."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NEREDEN", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("İl", cities, fromCity, { it.name }, Modifier.weight(1f), enabled = cities.isNotEmpty()) { c ->
                fromCity = c; fromD = null; fromDs = emptyList()
                scope.launch { fromDs = runCatching { TrafikApi.districts(c.id) }.getOrDefault(emptyList()) }
            }
            Picker("İlçe", fromDs, fromD, { it.name }, Modifier.weight(1f), enabled = fromDs.isNotEmpty()) { fromD = it }
        }
        Text("NEREYE", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("İl", cities, toCity, { it.name }, Modifier.weight(1f), enabled = cities.isNotEmpty()) { c ->
                toCity = c; toD = null; toDs = emptyList()
                scope.launch { toDs = runCatching { TrafikApi.districts(c.id) }.getOrDefault(emptyList()) }
            }
            Picker("İlçe", toDs, toD, { it.name }, Modifier.weight(1f), enabled = toDs.isNotEmpty()) { toD = it }
        }
        Button(
            onClick = {
                val a = fromD
                val b = toD
                val fc = fromCity
                if (a != null && b != null) {
                    busy = true
                    msg = null
                    scope.launch {
                        try {
                            val list = TrafikApi.route(a, b)
                            CorridorStore.addRoute(list)
                            result = list
                            if (fc != null) prefs.edit().putInt("fromCity", fc.id).putInt("fromDistrict", a.id).apply()
                        } catch (e: Exception) {
                            msg = "Rota alınamadı: ${e.message ?: "bağlantı hatası"}."
                        }
                        busy = false
                    }
                }
            },
            enabled = fromD != null && toD != null && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "Getiriliyor…" else "Rotadaki koridorları getir", fontWeight = FontWeight.Bold) }
        msg?.let { NoteBox(it, Over) }
        result?.let { list ->
            Text(if (list.isEmpty()) "Bu rotada kayıtlı koridor yok." else "${list.size} koridor", color = Fg, fontWeight = FontWeight.SemiBold)
            list.forEach { CorridorRow(it) }
        }
    }
}

// ---------------- KORİDORLAR ----------------

@Composable
private fun ListTab(ver: Int) {
    val list = remember(ver) { CorridorStore.all() }
    var dlg by remember { mutableStateOf<Corridor?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${list.size} koridor", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
                Text(
                    "trafik.gov.tr ${CorridorStore.routeCount} · EGM ${CorridorStore.egmCount} · senin ${CorridorStore.userCount}. Limiti değiştirmek için koridora dokun.",
                    color = Muted, fontSize = 13.sp
                )
                Text(
                    "Rota seçtiğinde gelen koridorlar sonraki sürüşlerde de tanınmak için saklanır. Eski rotalardan kalanları silmek için:",
                    color = Muted, fontSize = 12.sp
                )
                OutlinedButton(onClick = { CorridorStore.clearRoute() }) {
                    Text("İndirilen ${CorridorStore.routeCount} koridoru temizle", color = Fg)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        items(list) { c ->
            CorridorRow(
                c,
                onClick = { dlg = c },
                onDelete = if (CorridorStore.isUser(c.id)) ({ CorridorStore.removeUser(c.id) }) else null
            )
        }
    }
    dlg?.let { c ->
        LimitDialog(c, onDismiss = { dlg = null }) { v ->
            Engine.setLimit(c, v)
            dlg = null
        }
    }
}

@Composable
private fun CorridorRow(c: Corridor, onClick: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(12.dp))
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (c.limit != null) Color.White else Bg)
                .border(5.dp, if (c.limit != null) SignRed else Line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(c.limit?.toString() ?: "?", color = if (c.limit != null) Color(0xFF111111) else Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${Geo.fmtKm(c.lengthM)} · ${c.sourceLabel}", color = Muted, fontSize = 12.sp)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Sil", color = Over) }
        }
    }
}


// ---------------- YOLCULUKLAR ----------------

private fun fmtDate(t: Long, pattern: String) =
    java.text.SimpleDateFormat(pattern, java.util.Locale("tr", "TR")).format(java.util.Date(t))

private fun fmtOdo(km: Double) = String.format(java.util.Locale("tr", "TR"), "%,.1f km", km)

@Composable
private fun TripsTab(s: UiState) {
    var open by remember { mutableStateOf<TripRecord?>(null) }
    var vehDlg by remember { mutableStateOf(false) }
    val t = open
    if (t != null) {
        TripDetail(t, onBack = { open = null }, onDelete = { TripStore.delete(t.id); open = null })
        return
    }
    val trips = TripStore.trips
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { VehicleCard(s) { vehDlg = true } }
        item {
            val total = trips.filter { !it.sample }
            Text(
                if (trips.isEmpty()) "Henüz yolculuk yok" else "Yolculuklarım · ${total.size} kayıt · ${Geo.fmtKm(total.sumOf { it.distM })}",
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Fg, modifier = Modifier.padding(top = 8.dp)
            )
            if (trips.isEmpty()) {
                Text("“GPS ile başlat” ile başlayıp “Durdur” ile bitirdiğin her sürüş burada mesafe, süre, ortalamalar ve rotasıyla saklanır.", color = Muted, fontSize = 13.sp)
            }
        }
        items(trips) { tr -> TripRow(tr) { open = tr } }
    }
    if (vehDlg) VehicleDialog { vehDlg = false }
}

@Composable
private fun VehicleCard(s: UiState, onEdit: () -> Unit) {
    val v = VehicleStore.active
    val live = if (s.mode == "gps") s.trip.dist / 1000 else 0.0
    Column(
        Modifier.fillMaxWidth().clip(Card).background(Panel).border(1.dp, Line, Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AKTİF ARAÇ", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(v.name, color = Fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(v.type.label, color = Muted, fontSize = 13.sp)
            }
            if (v.plate.isNotBlank()) {
                Text(
                    v.plate, color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White)
                        .border(2.dp, Color(0xFF1F4E9C), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Text("Km sayacı: ${fmtOdo(v.odoKm + live)}", color = Fg, fontSize = 15.sp)
        if (v.type.commercial) {
            Text(
                "Limitler ticari araca göre: şehir içi ${v.type.urban}, çift yönlü ${v.type.twoWay}, bölünmüş ${v.type.divided}, otoyol ${v.type.motorway}. Levha farklıysa levha geçerli.",
                color = Muted, fontSize = 12.sp
            )
        }
        OutlinedButton(onClick = onEdit) { Text("Araçları düzenle / değiştir", color = Fg) }
    }
}

@Composable
private fun VehicleDialog(onClose: () -> Unit) {
    var editing by remember { mutableStateOf(VehicleStore.active) }
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var plate by remember(editing.id) { mutableStateOf(editing.plate) }
    var type by remember(editing.id) { mutableStateOf(editing.type) }
    var km by remember(editing.id) { mutableStateOf(if (editing.odoKm > 0) String.format(java.util.Locale.US, "%.0f", editing.odoKm) else "") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Araçlarım") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleStore.vehicles.forEach { v ->
                    val sel = v.id == editing.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Line else Bg).clickable { editing = v }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name + if (v.id == VehicleStore.activeId) " · aktif" else "", color = Fg, fontWeight = FontWeight.SemiBold)
                            Text("${v.plate.ifBlank { "plakasız" }} · ${v.type.label}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                TextButton(onClick = {
                    val nv = Vehicle("v" + System.currentTimeMillis(), "Yeni araç", "", VehicleType.OTO, 0.0)
                    VehicleStore.upsert(nv)
                    editing = nv
                }) { Text("+ Yeni araç ekle", color = Accent) }
                Text("SEÇİLİ ARACI DÜZENLE", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Araç adı") }, singleLine = true)
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it.uppercase(java.util.Locale("tr", "TR")).filter { ch -> ch.isLetterOrDigit() || ch == ' ' }.take(12) },
                    label = { Text("Plaka (ör. 71 ABC 123)") }, singleLine = true
                )
                OutlinedTextField(
                    value = km, onValueChange = { km = it.filter { ch -> ch.isDigit() }.take(7) },
                    label = { Text("Şu anki km (gösterge)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("Araç tipi", color = Muted, fontSize = 12.sp)
                VehicleType.values().forEach { vt ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (vt == type) Line else Bg).clickable { type = vt }.padding(10.dp)
                    ) {
                        Column {
                            Text(vt.label, color = Fg, fontWeight = if (vt == type) FontWeight.Bold else FontWeight.Normal)
                            Text("${vt.urban} / ${vt.twoWay} / ${vt.divided} / ${vt.motorway} km/s", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                Text("Sıra: şehir içi / çift yönlü / bölünmüş yol / otoyol (genel sınırlar).", color = Muted, fontSize = 11.sp)
                if (VehicleStore.vehicles.size > 1) {
                    TextButton(onClick = {
                        VehicleStore.remove(editing.id)
                        editing = VehicleStore.active
                    }) { Text("Bu aracı sil", color = Over) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                VehicleStore.upsert(
                    editing.copy(
                        name = name.trim().ifBlank { "Aracım" },
                        plate = plate.trim(),
                        type = type,
                        odoKm = km.toDoubleOrNull() ?: editing.odoKm
                    )
                )
                VehicleStore.select(editing.id)
                onClose()
            }) { Text("Kaydet ve seç") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Kapat") } }
    )
}

@Composable
private fun TripRow(t: TripRecord, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row {
            Text(fmtDate(t.start, "d MMM yyyy, EEE"), color = Fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${fmtDate(t.start, "HH:mm")}–${fmtDate(t.end, "HH:mm")}", color = Muted, fontSize = 13.sp)
        }
        Text(
            "${Geo.fmtKm(t.distM)} · ${durText(t.timeS)} · ort. ${t.avg?.roundToInt() ?: "—"} km/s" +
                if (t.passes.isNotEmpty()) " · ${t.passes.size} EDS" else "",
            color = Fg, fontSize = 14.sp
        )
        Text(
            listOfNotNull(
                t.plate.ifBlank { t.vehicleName },
                t.target?.let { "→ $it" },
                if (t.sample) "örnek sürüş" else null
            ).joinToString(" · "),
            color = Muted, fontSize = 12.sp
        )
    }
}

@Composable
private fun TripDetail(t: TripRecord, onBack: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Yolculuklar", color = Accent) }
        Text(fmtDate(t.start, "d MMMM yyyy, EEEE"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        Text(
            "${fmtDate(t.start, "HH:mm")}–${fmtDate(t.end, "HH:mm")} · ${t.plate.ifBlank { t.vehicleName }} · ${t.vehicleType}" +
                (t.target?.let { " · hedef: $it" } ?: ""),
            color = Muted, fontSize = 13.sp
        )
        Box(Modifier.fillMaxWidth().height(300.dp).clip(Card).border(1.dp, Line, Card)) { TripMap(t) }
        CellGrid(
            listOf(
                Triple("Mesafe", Geo.fmtKm(t.distM), null),
                Triple("Süre", durText(t.timeS), null),
                Triple("Ortalama", "${t.avg?.roundToInt() ?: "—"} km/s", null),
                Triple("Hareket ortalaması", "${t.moveAvg?.roundToInt() ?: "—"} km/s", null),
                Triple("EDS dışı bekleme", Geo.fmtT(t.stopOutS), null),
                Triple("EDS içi bekleme", Geo.fmtT(t.stopInS), null)
            )
        )
        Text(if (t.passes.isEmpty()) "Bu yolculukta EDS koridoru geçilmedi." else "Geçilen EDS koridorları", color = Fg, fontWeight = FontWeight.SemiBold)
        t.passes.forEach { p ->
            val col = when {
                p.limit == null -> Fg
                p.avg > p.limit * 1.1 -> Over
                p.avg > p.limit -> Warn
                else -> Ok
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${Geo.fmtKm(p.lengthM)} · ${Geo.fmtT(p.durationS)}" +
                            (if (p.stopS > 0) " · bekleme ${Geo.fmtT(p.stopS)}" else "") +
                            " · limit ${p.limit ?: "?"}",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Text("${p.avg.roundToInt()}", color = col, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
        }
        if (confirm) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = Over)) { Text("Evet, sil") }
                OutlinedButton(onClick = { confirm = false }) { Text("Vazgeç", color = Fg) }
            }
        } else {
            TextButton(onClick = { confirm = true }) { Text("Bu yolculuğu sil", color = Over) }
        }
    }
}

@Composable
private fun TripMap(t: TripRecord) {
    val ctx = LocalContext.current
    val map = remember(t.id) {
        org.osmdroid.views.MapView(ctx).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(39.0, 35.0))
            val pts = t.track.map { org.osmdroid.util.GeoPoint(it.lat, it.lon) }
            if (pts.size >= 2) {
                val line = org.osmdroid.views.overlay.Polyline(this)
                line.setPoints(pts)
                line.outlinePaint.color = 0xFFF2B632.toInt()
                line.outlinePaint.strokeWidth = 10f
                overlays.add(line)
                fun dotMarker(p: org.osmdroid.util.GeoPoint, color: Int, title: String) {
                    val m = org.osmdroid.views.overlay.Marker(this)
                    m.position = p
                    m.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                    m.icon = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                        setStroke(6, android.graphics.Color.WHITE)
                        setSize(38, 38)
                    }
                    m.title = title
                    overlays.add(m)
                }
                dotMarker(pts.first(), 0xFF2FBF71.toInt(), "Başlangıç " + fmtDate(t.start, "HH:mm"))
                dotMarker(pts.last(), 0xFF4DA3FF.toInt(), "Bitiş " + fmtDate(t.end, "HH:mm"))
                val box = org.osmdroid.util.BoundingBox.fromGeoPoints(pts)
                addOnFirstLayoutListener { _, _, _, _, _ ->
                    try {
                        zoomToBoundingBox(box, false, 60)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
    DisposableEffect(t.id) {
        map.onResume()
        onDispose { map.onPause() }
    }
    if (t.track.size < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Bu yolculuk için rota kaydı yok", color = Muted)
        }
    }
}
