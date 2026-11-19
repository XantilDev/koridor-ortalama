package com.xantildev.koridor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * Bizim radar / çevirme kayıtlarımız: çalışanlar yolda gördüklerinde tek dokunuşla işaretler, işaret Supabase'e gider.
 * Radarlar genelde hep aynı yerlere kurulduğu için yakın işaretler birleştirilip "tahmini nokta" olur;
 * sürüşte rotada (rota yoksa gidiş yönünde) böyle bir nokta yaklaşınca ~3 km ve ~800 m önce uyarılır.
 */
object OurRadar {
    data class Mark(val id: String, val tur: String, val ll: LatLng, val yon: Int?, val t: Long, val by: String? = null)
    /** Tahmini nokta: [count] işaret, [days] farklı gün. */
    data class Spot(val ll: LatLng, val tur: String, val yon: Int?, val count: Int, val days: Int, val last: Long, val radar: Int, val cevirme: Int, val lastBy: String? = null) {
        val key: String get() = "%.4f,%.4f".format(java.util.Locale.US, ll.lat, ll.lon)
        val label: String get() = when { cevirme == 0 -> "radar"; radar == 0 -> "çevirme"; else -> "radar/çevirme" }
        val icon: String get() = if (radar >= cevirme) "📷" else "👮"
    }

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var app: Context? = null
    private var server: List<Mark> = emptyList()
    private val pending = ArrayList<JSONObject>()

    var spots by mutableStateOf<List<Spot>>(emptyList())
        private set
    var version by mutableIntStateOf(0)
        private set
    /** Ekranda: "📷 Tahmini radar 2,4 km · 5 kez görüldü" */
    var aheadText by mutableStateOf<String?>(null)
        private set
    /** Son işaret: ekranda "Geri al" için (15 sn). */
    var lastMarkId by mutableStateOf<String?>(null)
        private set
    var lastMarkAt by mutableStateOf(0L)
        private set
    var lastMarkText by mutableStateOf<String?>(null)
        private set

    val enabled: Boolean get() = Firma.enabled && !Access.isGuest   // misafirler firmanın radar kayıtlarını görmez

    fun init(ctx: Context) {
        if (!Firma.enabled) return
        app = ctx.applicationContext
        runCatching { File(ctx.filesDir, "radar_isaret.json").takeIf { it.exists() }?.readText()?.let { server = parseServer(it) } }
        runCatching {
            val a = JSONArray(ctx.getSharedPreferences("radar", Context.MODE_PRIVATE).getString("bekleyen", "[]"))
            for (i in 0 until a.length()) pending.add(a.getJSONObject(i))
        }
        recluster()
        scope.launch {
            var n = 0
            while (true) {
                if (pending.isNotEmpty()) sendPending()
                if (n % 360 == 0) refresh()          // 6 saatte bir (ve açılışta) listeyi yenile
                n++
                delay(60_000)
            }
        }
    }

    /** Supabase biçimi: [[id, tur, lat, lon, yon, epoch_sn], …] */
    fun parseServer(text: String): List<Mark> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val r = a.getJSONArray(i)
                Mark("s" + r.getLong(0), r.getString(1), LatLng(r.getDouble(2), r.getDouble(3)), if (r.isNull(4)) null else r.getInt(4), r.getLong(5) * 1000,
                    if (r.length() > 6 && !r.isNull(6)) r.getString(6) else null)
            }.getOrNull()
        }
    }

    fun refresh() {
        val token = Firma.logToken ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { Firma.rpc("radar_noktalari", JSONObject().put("p_token", token)) }
                    .onFailure { TLog.w("radar", "Radar noktaları alınamadı: ${it.message}") }.getOrNull()
            } ?: return@launch
            server = parseServer(text)
            app?.let { c -> runCatching { File(c.filesDir, "radar_isaret.json").writeText(text) } }
            recluster()
        }
    }

    private fun localMarks(): List<Mark> = pending.mapNotNull { o ->
        runCatching { Mark(o.getString("i"), o.getString("tur"), LatLng(o.getDouble("lat"), o.getDouble("lon")), if (o.has("yon")) o.getInt("yon") else null, o.getLong("t"), "sen") }.getOrNull()
    }

    private fun recluster() {
        spots = cluster(server + localMarks())
        version++
        MapExtras.rebuild()
    }

    /** Aracın şu anki gidiş yönü: son 30+ m'lik hareketten, yoksa GPS'in yönü. */
    private fun currentHeading(): Int? {
        val tr = Engine.state.value.track
        val p = tr.lastOrNull()
        if (p != null) for (i in tr.size - 2 downTo maxOf(0, tr.size - 40)) {
            if (Geo.dist(tr[i], p) >= 30) return HeadingTracker.bearing(tr[i], p).toInt()
        }
        return GpsHeading.fresh()?.toInt()
    }

    /** "Radar gördüm" / "Çevirme gördüm". at verilmezse şu anki konum ve gidiş yönü. */
    fun mark(tur: String, at: LatLng? = null): String {
        if (!enabled) return "Bu sürümde yok"
        val st = Engine.state.value
        val p = at ?: st.pos ?: return "Konum yok, işaretlenemedi".also { Engine.say(it) }
        val yon = if (at == null) currentHeading() else null
        val id = UUID.randomUUID().toString()
        val o = JSONObject().put("i", id).put("tur", tur).put("lat", p.lat).put("lon", p.lon).put("t", System.currentTimeMillis())
        if (yon != null) o.put("yon", yon)
        st.speedKmh?.let { o.put("hiz", it) }
        pending.add(o)
        savePending()
        recluster()
        lastMarkId = id; lastMarkAt = System.currentTimeMillis()
        val ad = if (tur == "cevirme") "Çevirme" else "Radar"
        lastMarkText = "$ad işaretlendi"
        Engine.say("$ad işaretlendi.")
        TLog.i("radar", "$ad işaretlendi", JSONObject().put("yon", yon).put("elle_nokta", at != null))
        scope.launch { sendPending() }
        return "$ad işaretlendi"
    }

    /** Yanlışlıkla basıldıysa (sunucuda 30 dk içinde silinebilir). */
    fun undo(id: String) {
        val wasPending = pending.removeAll { it.optString("i") == id }
        savePending()
        server = server.filterNot { it.id == "s:$id" }
        if (lastMarkId == id) { lastMarkId = null; lastMarkText = "İşaret geri alındı" }
        recluster()
        Engine.say("İşaret geri alındı.")
        TLog.i("radar", "İşaret geri alındı")
        if (!wasPending) {
            val token = Firma.logToken ?: return
            scope.launch(Dispatchers.IO) {
                runCatching { Firma.rpc("radar_isaret_geri", JSONObject().put("p_token", token).put("p_istemci", id)) }
                withContext(Dispatchers.Main) { refresh() }
            }
        }
    }

    private fun savePending() {
        val a = JSONArray(); pending.forEach { a.put(it) }
        app?.getSharedPreferences("radar", Context.MODE_PRIVATE)?.edit()?.putString("bekleyen", a.toString())?.apply()
    }

    private var sending = false
    private suspend fun sendPending() {
        if (sending) return
        val token = Firma.logToken ?: return
        sending = true
        try {
            for (o in pending.toList()) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Firma.rpc("radar_isaretle", JSONObject().put("p_token", token).put("p_istemci", o.getString("i"))
                            .put("p_tur", o.getString("tur")).put("p_lat", o.getDouble("lat")).put("p_lon", o.getDouble("lon"))
                            .put("p_yon", if (o.has("yon")) o.getInt("yon") else JSONObject.NULL)
                            .put("p_hiz", if (o.has("hiz")) o.getInt("hiz") else JSONObject.NULL)
                            .put("p_zaman", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date(o.getLong("t"))))
                            .put("p_cihaz", JSONObject.NULL))
                    }.onFailure { TLog.w("radar", "İşaret gönderilemedi (sonra tekrar denenecek): ${it.message}") }.isSuccess
                }
                if (!ok) break
                // Gönderilen işaret sunucu listesine geçer (yeniden indirilene kadar burada tutulur)
                pending.remove(o)
                server = server + Mark("s:" + o.getString("i"), o.getString("tur"), LatLng(o.getDouble("lat"), o.getDouble("lon")),
                    if (o.has("yon")) o.getInt("yon") else null, o.getLong("t"), "sen")
                savePending()
            }
        } finally {
            sending = false
        }
    }

    // ---------------- Uyarı ----------------
    private val warned = HashMap<String, Int>()   // nokta → 1: 3 km uyarısı yapıldı, 2: yakın uyarısı da
    private var lastDist = 0.0

    /** Sürüşte her konumda: rotada / gidiş yönünde ileride tahmini nokta var mı. */
    fun onFix(p: LatLng, kmh: Int?) {
        if (!enabled || spots.isEmpty()) { aheadText = null; return }
        val dist = Engine.state.value.trip.dist
        if (dist + 1 < lastDist) warned.clear()   // yeni yolculuk başladı
        lastDist = dist
        val inf = RouteSession.info
        val ahead = if (inf != null && inf.line.size >= 2 && Geo.distToLine(p, inf.line) < 300) aheadOnRoute(spots, inf, p)
            else aheadByHeading(spots, p, currentHeading(), kmh)
        if (ahead == null) { aheadText = null; return }
        val (s, d) = ahead
        aheadText = "${s.icon} Tahmini ${s.label} ${Geo.fmtKm(d)} · ${seenText(s)}"
        val level = warned[s.key] ?: 0
        val far = if (inf != null) 3_000.0 else 1_500.0
        if (level == 0 && d <= far && d > 1_200) {
            warned[s.key] = 1
            Engine.say("${Geo.km1(d)} kilometre ileride tahmini ${s.label} noktası. ${seenText(s)}.")
            TLog.i("radar", "Uyarı: ${Geo.fmtKm(d)} ileride tahmini ${s.label}", JSONObject().put("sayi", s.count).put("rota", inf != null))
        } else if (level < 2 && d <= 1_000) {
            warned[s.key] = 2
            Engine.say("${(d / 100).toInt() * 100} metre ileride tahmini ${s.label} noktası, hızına dikkat.")
        }
    }

    fun seenText(s: Spot): String {
        val ago = ((System.currentTimeMillis() - s.last) / 86_400_000L).toInt()
        val agoT = when { ago <= 0 -> "bugün"; ago == 1 -> "dün"; ago < 60 -> "$ago gün önce"; else -> "${ago / 30} ay önce" }
        val who = s.lastBy?.let { if (it == "sen") " (sen)" else " (${it.substringBefore(' ')})" } ?: ""
        return "${s.count} kez görüldü, son $agoT$who"
    }

    /** Rotadaki tahmini noktalar (rota hazır olunca söylenir, EGM bölümleriyle karşılaştırılır). */
    fun onRoute(inf: RouteSession.Info): List<Pair<Spot, Double>> = onLine(spots, inf.line, 0.0, Double.MAX_VALUE)

    fun routeSummary(inf: RouteSession.Info): String? {
        val l = onRoute(inf)
        if (l.isEmpty()) return null
        val r = l.count { it.first.radar >= it.first.cevirme }
        val c = l.size - r
        return "Kayıtlarımızda rotanda " + listOfNotNull(r.takeIf { it > 0 }?.let { "$it radar" }, c.takeIf { it > 0 }?.let { "$it çevirme" }).joinToString(", ") + " noktası var."
    }

    /** Bir EGM bölümündeki (rota üzerinde a0–a1 metre arası) bizim noktalarımız. */
    fun inSegment(inf: RouteSession.Info, a0: Double, a1: Double): Int = onLine(spots, inf.line, a0, a1).size

    private fun aheadOnRoute(all: List<Spot>, inf: RouteSession.Info, p: LatLng): Pair<Spot, Double>? {
        val me = inf.project(p).first
        return onLine(all, inf.line, me + 30, me + 5_000).minByOrNull { it.second }?.let { it.first to it.second - me }
    }

    // ---------------- Saf hesaplar (birim testli) ----------------
    const val MERGE_M = 300.0
    const val YON_FARK = 60

    private fun yonUyar(a: Int?, b: Int?): Boolean {
        if (a == null || b == null) return true
        var d = abs(a - b) % 360; if (d > 180) d = 360 - d
        return d <= YON_FARK
    }

    /** Yakın (300 m) ve aynı yöne giden işaretleri tek noktada toplar. */
    fun cluster(marks: List<Mark>): List<Spot> {
        class Acc(var lat: Double, var lon: Double, var n: Int, var yonX: Double, var yonY: Double, var yonN: Int,
                  val days: HashSet<Long>, var last: Long, var radar: Int, var cev: Int, var by: String? = null)
        val acc = ArrayList<Acc>()
        for (m in marks.sortedBy { it.t }) {
            val hit = acc.filter { a ->
                val ay = if (a.yonN > 0) ((Math.toDegrees(Math.atan2(a.yonX, a.yonY)) + 360) % 360).toInt() else null
                Geo.dist(LatLng(a.lat, a.lon), m.ll) < MERGE_M && yonUyar(ay, m.yon)
            }.minByOrNull { Geo.dist(LatLng(it.lat, it.lon), m.ll) }
            val day = m.t / 86_400_000L
            if (hit == null) {
                acc.add(Acc(m.ll.lat, m.ll.lon, 1, 0.0, 0.0, 0, hashSetOf(day), m.t, if (m.tur == "cevirme") 0 else 1, if (m.tur == "cevirme") 1 else 0, m.by).also { a ->
                    m.yon?.let { a.yonX = Math.sin(Math.toRadians(it.toDouble())); a.yonY = Math.cos(Math.toRadians(it.toDouble())); a.yonN = 1 }
                })
            } else {
                hit.lat = (hit.lat * hit.n + m.ll.lat) / (hit.n + 1)
                hit.lon = (hit.lon * hit.n + m.ll.lon) / (hit.n + 1)
                hit.n++
                m.yon?.let { hit.yonX += Math.sin(Math.toRadians(it.toDouble())); hit.yonY += Math.cos(Math.toRadians(it.toDouble())); hit.yonN++ }
                hit.days.add(day)
                if (m.t >= hit.last) { hit.last = m.t; if (m.by != null) hit.by = m.by }
                if (m.tur == "cevirme") hit.cev++ else hit.radar++
            }
        }
        return acc.map { a ->
            val y = if (a.yonN > 0) ((Math.toDegrees(Math.atan2(a.yonX, a.yonY)) + 360) % 360).toInt() else null
            Spot(LatLng(a.lat, a.lon), if (a.radar >= a.cev) "radar" else "cevirme", y, a.n, a.days.size, a.last, a.radar, a.cev, a.by)
        }
    }

    /** Çizgiye 80 m'den yakın, yönü çizgiyle uyumlu ve [from, to] (çizgi boyunca metre) arasındaki noktalar. */
    fun onLine(all: List<Spot>, line: List<LatLng>, from: Double, to: Double): List<Pair<Spot, Double>> {
        if (line.size < 2) return emptyList()
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + Geo.dist(line[i - 1], line[i])
        val out = ArrayList<Pair<Spot, Double>>()
        for (s in all) {
            var best = Double.MAX_VALUE; var bestAlong = 0.0; var bestSeg = 0
            for (i in 0 until line.size - 1) {
                val a = line[i]; val b = line[i + 1]
                val (d, f) = segDist(s.ll, a, b)
                if (d < best) { best = d; bestAlong = cum[i] + f * (cum[i + 1] - cum[i]); bestSeg = i }
            }
            if (best > 80 || bestAlong < from || bestAlong > to) continue
            val lineYon = HeadingTracker.bearing(line[bestSeg], line[bestSeg + 1]).toInt()
            if (!yonUyar(s.yon, lineYon)) continue
            out.add(s to bestAlong)
        }
        return out.sortedBy { it.second }
    }

    /** Rota yoksa: gidiş yönünde 25° içinde, en çok 1,5 km ilerideki ve yönü uyan nokta. */
    fun aheadByHeading(all: List<Spot>, p: LatLng, heading: Int?, kmh: Int?): Pair<Spot, Double>? {
        if (heading == null || (kmh ?: 0) < 20) return null
        return all.mapNotNull { s ->
            val d = Geo.dist(p, s.ll)
            if (d > 1_500 || d < 30) return@mapNotNull null
            val b = HeadingTracker.bearing(p, s.ll).toInt()
            if (!yonUyar(s.yon, heading)) return@mapNotNull null
            var diff = abs(b - heading) % 360; if (diff > 180) diff = 360 - diff
            if (diff > 25) null else s to d
        }.minByOrNull { it.second }
    }

    /** Noktanın [a,b] parçasına uzaklığı (m) ve parça üstündeki oranı (0..1). Kısa parçalar için düz yaklaşım. */
    private fun segDist(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(a.lat)) * 111_320.0
        val ax = 0.0; val ay = 0.0
        val bx = (b.lon - a.lon) * k; val by = (b.lat - a.lat) * 110_540.0
        val px = (p.lon - a.lon) * k; val py = (p.lat - a.lat) * 110_540.0
        val l2 = bx * bx + by * by
        val f = if (l2 == 0.0) 0.0 else (((px - ax) * bx + (py - ay) * by) / l2).coerceIn(0.0, 1.0)
        val dx = px - f * bx; val dy = py - f * by
        return Math.sqrt(dx * dx + dy * dy) to f
    }
}
