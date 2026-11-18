package com.xantildev.koridor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Haritada rotanın üstüne çizilen ek çizgiler (trafik, radar bölümleri). Üç harita da bunu çizer. */
object MapExtras {
    class XLine(val pts: List<LatLng>, val color: Int, val widthDp: Float, val z: Float)

    private var bump = 0
    private var cacheKey: String? = null
    private var cache: List<XLine> = emptyList()

    /** Değişince haritalar yeniden çizer. */
    fun key(): String = "${System.identityHashCode(RouteSession.info)}/${Traffic.version}/${RadarMap.updatedAt}/$bump"

    fun rebuild() { bump++ }

    fun lines(): List<XLine> {
        val k = key()
        if (k == cacheKey) return cache
        cacheKey = k
        val inf = RouteSession.info
        val out = ArrayList<XLine>()
        if (inf != null && inf.line.size >= 2) {
            // Radar/kontrol bulunan ilçe arası bölümler: rotanın çevresinde turuncu geniş bant
            RadarMap.segs.filter { it.info.target == inf.target && (it.radar > 0 || it.other > 0) }.forEach { g ->
                val s0 = inf.project(g.info.pointAt(g.startAlong)).first
                val s1 = inf.project(g.info.pointAt(g.endAlong)).first
                val pts = inf.sliceAlong(s0, s1)
                if (pts.size >= 2) out.add(XLine(pts, 0x66FF8A00, 26f, 0.85f))
            }
            // Trafik: yavaş sarı, sıkışık kırmızı (rotanın ortasında)
            Traffic.of(inf.line).forEach { t ->
                val a0 = t.from.coerceIn(0, inf.line.size - 1)
                val b0 = (t.to + 1).coerceIn(a0 + 1, inf.line.size)
                val pts = inf.line.subList(a0, b0)
                if (pts.size >= 2) out.add(XLine(pts, if (t.level >= 2) 0xFFC5221F.toInt() else 0xFFF9AB00.toInt(), 8f, 1.3f))
            }
        }
        cache = out
        return out
    }
}

/**
 * Google'ın rota boyunca trafik bilgisi (TRAFFIC_ON_POLYLINE): yavaş ve sıkışık bölümler.
 * Ekranda "İleride 3 km'de trafik sıkışması" gibi yazı; ses yok.
 */
object Traffic {
    /** Çizgi noktası aralığı ve seviye: 1 yavaş, 2 sıkışık. */
    class Seg(val from: Int, val to: Int, val level: Int)

    private val byLine = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<List<LatLng>, List<Seg>>())

    var ahead by mutableStateOf<String?>(null)
        private set
    var aheadJam by mutableStateOf(false)
        private set

    var version by mutableIntStateOf(0)
        private set

    fun store(line: List<LatLng>, segs: List<Seg>) {
        if (byLine.size > 12) byLine.clear()
        byLine[line] = segs
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { version++ }
    }

    fun of(line: List<LatLng>): List<Seg> = byLine[line] ?: emptyList()

    fun onFix(p: LatLng, inf: RouteSession.Info) {
        val segs = of(inf.line)
        if (segs.isEmpty()) { ahead = null; return }
        val (me, off) = inf.project(p)
        if (off > 300) { ahead = null; return }
        // Bitişik yavaş/sıkışık parçaları birleştir; 300 m'den kısa olanları sayma
        data class Run(val s0: Double, var s1: Double, var jam: Boolean)
        val runs = ArrayList<Run>()
        for (t in segs.sortedBy { it.from }) {
            val s0 = inf.alongAt(t.from); val s1 = inf.alongAt(t.to)
            val last = runs.lastOrNull()
            if (last != null && s0 - last.s1 < 150) { last.s1 = maxOf(last.s1, s1); last.jam = last.jam || t.level >= 2 }
            else runs.add(Run(s0, s1, t.level >= 2))
        }
        val r = runs.firstOrNull { it.s1 > me && it.s1 - it.s0 >= 300 && it.s0 - me < 15_000 }
        if (r == null) { ahead = null; aheadJam = false; return }
        val what = if (r.jam) "trafik sıkışması" else "yavaş trafik"
        ahead = if (r.s0 <= me + 50) "🚦 ${what.replaceFirstChar { it.uppercase() }}: ${Geo.fmtKm(r.s1 - me)} daha"
        else "🚦 ${Geo.fmtKm(r.s0 - me)} ileride $what (${Geo.fmtKm(r.s1 - r.s0)})"
        aheadJam = r.jam
    }
}

/**
 * EGM radar/kontrol sayılarını ilçe arası bölümlere yerleştirir (fark yöntemi):
 * rota üzerindeki her ilçeden hedefe sorulan sayıların farkı, o iki ilçe arasındaki uygulama sayısıdır.
 */
object RadarMap {
    class Seg(val info: RouteSession.Info, val fromName: String, val toName: String, val startAlong: Double, val endAlong: Double, val radar: Int, val other: Int)

    var segs by mutableStateOf<List<Seg>>(emptyList())
        private set
    var updatedAt by mutableStateOf(0L)
        private set
    /** Kalan rotadaki toplam (içinde bulunulan bölümden itibaren). */
    var remainingText by mutableStateOf<String?>(null)
        private set
    var currentText by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var forTarget: Places.Place? = null
    private var lastSeg = -1

    fun clear() { job?.cancel(); segs = emptyList(); updatedAt = 0; remainingText = null; currentText = null; forTarget = null; lastSeg = -1 }

    /** Rota hazır olunca ve 15 dk'da bir (sürüş sırasında) çağrılır. Yalnız ilerideki bölümler sorgulanır. */
    fun refresh(inf: RouteSession.Info, here: LatLng?) {
        if (job?.isActive == true) return
        if (forTarget != inf.target) { segs = emptyList(); lastSeg = -1 }
        forTarget = inf.target
        job = scope.launch {
            try { build(inf, here) } catch (_: Exception) { }
        }
    }

    private suspend fun build(inf: RouteSession.Info, here: LatLng?) {
        val total = inf.totalM
        if (total < 8_000) return
        val me = here?.let { inf.project(it).first } ?: 0.0
        // Rota boyunca 12 km'de bir nokta (en çok 14), ilçeye göre birleştir
        val step = maxOf(12_000.0, (total - me) / 14)
        var s = me
        val samples = ArrayList<Pair<Double, TrafikApi.District>>()
        while (s < total - 4_000) {
            val pt = inf.pointAt(s)
            val d = runCatching { DistrictFinder.nearest(pt, null) }.getOrNull()
            if (d != null && samples.lastOrNull()?.second?.id != d.id) samples.add(s to d)
            s += step
            delay(400)   // ters coğrafi kodlama servisini yormamak için
        }
        if (samples.size < 2) return
        val to = DistrictFinder.nearest(inf.target.ll, inf.target.province)
        val counts = ArrayList<Triple<Double, String, TrafikApi.Enforcement?>>()
        for ((along, d) in samples) {
            if (RouteSession.info?.target != inf.target) return
            val e = runCatching { TrafikApi.counts(d, to) }.getOrNull()
            counts.add(Triple(along, d.name.substringBefore(","), e))
            delay(800)   // EGM'yi yormamak için sırayla
        }
        val out = ArrayList<Seg>()
        for (i in counts.indices) {
            val (a0, n0, e0) = counts[i]
            val next = counts.getOrNull(i + 1)
            val r0 = e0?.radar; val o0 = e0?.other
            if (r0 == null && o0 == null) continue
            val r1 = if (next == null) 0 else next.third?.radar
            val o1 = if (next == null) 0 else next.third?.other
            if (next != null && next.third == null) continue
            val dr = (r0 ?: 0) - (r1 ?: 0)
            val dot = (o0 ?: 0) - (o1 ?: 0)
            if (dr < 0 || dot < 0) continue   // tutarsız fark (EGM farklı yol çizmiş olabilir): gösterme
            out.add(Seg(inf, n0, next?.second ?: to.name.substringBefore(","), a0, next?.first ?: total, dr, dot))
        }
        if (RouteSession.info?.target != inf.target) return
        segs = out
        updatedAt = System.currentTimeMillis()
    }

    /** Sürüş sırasında: içinde bulunulan bölüm ve kalan toplam; yeni radarlı bölüme girince bir kez söyler. */
    fun onFix(p: LatLng, inf: RouteSession.Info) {
        val list = segs.filter { it.info.target == inf.target }
        if (list.isEmpty()) { currentText = null; remainingText = null; return }
        // Bölüm sınırları eski rota çizgisine göre; yeni çizgide konumu bulmak için bölüm başlangıç noktası kullanılır
        val me = inf.project(p).first
        val idx = list.indexOfLast { seg -> inf.project(seg.info.pointAt(seg.startAlong)).first <= me + 100 }.coerceAtLeast(0)
        val cur = list[idx]
        val rest = list.drop(idx)
        val rr = rest.sumOf { it.radar }; val ro = rest.sumOf { it.other }
        remainingText = "🚓 Kalan rotada $rr radar · $ro kontrol noktası"
        currentText = if (cur.radar > 0 || cur.other > 0)
            "Bu bölümde ${listOfNotNull(cur.radar.takeIf { it > 0 }?.let { "$it radar" }, cur.other.takeIf { it > 0 }?.let { "$it kontrol" }).joinToString(" · ")} · ${cur.fromName} – ${cur.toName}"
        else "Bu bölümde uygulama yok · ${cur.fromName} – ${cur.toName}"
        if (idx != lastSeg) {
            if (lastSeg >= 0 && (cur.radar > 0 || cur.other > 0))
                Engine.say("${cur.fromName} bölümüne geçtin. Bu bölümde ${cur.radar} radar, ${cur.other} kontrol noktası var.", "radarbolum", 60_000)
            lastSeg = idx
        }
    }

    fun timeText(): String? = if (updatedAt == 0L) null
    else java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr", "TR")).format(java.util.Date(updatedAt))
}
