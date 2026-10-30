package com.xantildev.koridor

import android.Manifest
import android.content.Context
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val s by Engine.state.collectAsStateWithLifecycle()
    val ver by CorridorStore.version.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        StatusLine(s, ver)
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Fg) {
            listOf("Sürüş", "Rota", "Koridorlar").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) })
            }
        }
        when (tab) {
            0 -> DriveTab(s, ver)
            1 -> RouteTab()
            else -> ListTab(ver)
        }
    }
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
            "  ${s.gpsText} · $count koridor · Anlık ${s.speedKmh ?: "—"} km/s",
            color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------- SÜRÜŞ ----------------

@Composable
private fun DriveTab(s: UiState, ver: Int) {
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
        Hud(s) { limitFor = it }
        if (s.confirmed && s.corridor?.limit != null) WhatIf(s)
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
        inC && c != null -> Geo.fmtKm(c.lengthM) + " koridor · " + c.sourceLabel
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
            Cells(
                "Kalan" to Geo.fmtKm(s.remaining),
                "Geçen süre" to Geo.fmtT(s.elapsed),
                "Zaman payı" to (s.slack?.let { Geo.fmtSlack(it) } ?: "—"),
                "Kalan için ort. üst sınır" to s.maxAvgLeft,
                thirdColor = s.slack?.let { if (it >= 0) Ok else Over }
            )
        } else {
            Cells(
                "Mesafe" to Geo.fmtKm(s.trip.dist),
                "Süre" to Geo.fmtT(s.trip.time),
                "Hareket ortalaması" to (s.moveAvg?.let { "${it.roundToInt()} km/s" } ?: "—"),
                "Durma süresi" to Geo.fmtT(s.trip.stop)
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
private fun WhatIf(s: UiState) {
    val c = s.corridor ?: return
    val lim = c.limit ?: return
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
            Text("KALAN YOLU BU HIZLA GİDERSEM", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
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
            Text("Bitiş ortalaman ≈ ", color = Fg)
            Text("${fin.roundToInt()} km/s", color = finColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text("Kalan ${Geo.fmtKm(s.remaining)} · yaklaşık ${Geo.fmtT(tRem)} dk · $status", color = Muted, fontSize = 13.sp)
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
            msg = "trafik.gov.tr'ye ulaşılamadı (${e.message ?: "bağlantı hatası"}). İnternet bağlantını kontrol et."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Nereye gidiyoruz?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        Text(
            "Rotadaki ortalama hız koridorları limitleriyle birlikte EGM Trafik Başkanlığı'nın sitesinden (trafik.gov.tr) alınır ve telefonda saklanır. Sürüşte otomatik tanınır.",
            color = Muted, fontSize = 13.sp
        )
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
                            Engine.say(
                                if (list.isEmpty()) "Bu rotada ortalama hız koridoru bulunamadı."
                                else "Rotanızda ${list.size} ortalama hız koridoru var."
                            )
                        } catch (e: Exception) {
                            msg = "Rota alınamadı: ${e.message ?: "bağlantı hatası"}. Biraz sonra tekrar dene."
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
            val total = list.sumOf { it.lengthM }
            Text(
                if (list.isEmpty()) "Bu rotada kayıtlı ortalama hız koridoru yok."
                else "${list.size} koridor · toplam ${Geo.fmtKm(total)}",
                color = Fg, fontWeight = FontWeight.SemiBold
            )
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
