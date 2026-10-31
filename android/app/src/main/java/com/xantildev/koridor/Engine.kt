package com.xantildev.koridor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class Fix(val lat: Double, val lon: Double, val t: Long, val speed: Double?, val acc: Float?) {
    val ll: LatLng get() = LatLng(lat, lon)
}

data class Trip(val dist: Double = 0.0, val time: Double = 0.0, val move: Double = 0.0, val stop: Double = 0.0, val stopIn: Double = 0.0) {
    val stopOut: Double get() = stop - stopIn
}

data class UiState(
    val mode: String = "idle",
    val gpsText: String = "Konum kapalı",
    val speedKmh: Int? = null,
    val pos: LatLng? = null,
    val corridor: Corridor? = null,
    val confirmed: Boolean = false,
    val avg: Double? = null,
    val traveled: Double = 0.0,
    val remaining: Double = 0.0,
    val elapsed: Double = 0.0,
    val corridorStop: Double = 0.0,
    val slack: Double? = null,
    val maxAvgLeft: String = "—",
    val progress: Float = 0f,
    val note: String? = null,
    val approach: String? = null,
    val trip: Trip = Trip(),
    val tripAvg: Double? = null,
    val moveAvg: Double? = null,
    val track: List<LatLng> = emptyList(),
    val markActive: Boolean = false,
    val pendingMark: Corridor? = null,
    val partial: Boolean = false,
    val markText: String = "Listede olmayanı kendin ekle",
    val arrived: String? = null,
    val tick: Long = 0
)

/**
 * Ölçüm motoru: web sürümündeki mantığın aynısı.
 * Konumlar ana iş parçacığından gelir (GPS dinleyicisi ve örnek sürüş), durum StateFlow ile yayınlanır.
 */
object Engine {
    const val ENTRY = 120.0

    val DEMO: Corridor by lazy {
        val f = LatLng(39.9000, 32.6000)
        val t = LatLng(39.9600, 32.7100)
        Corridor("demo", "Örnek koridor (gerçek değil)", f, t, 90, Geo.dist(f, t), "demo")
    }

    val state = MutableStateFlow(UiState())
    var voiceOn = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mode = "idle"
    private var gpsText = "Konum kapalı"
    private var speedKmh: Int? = null
    private var prev: Fix? = null
    private var run: Run? = null
    private var trip = Trip()
    private val track = ArrayList<LatLng>()
    private val near = HashMap<String, Double>()
    private val announced = HashSet<String>()
    private val rejected = HashSet<String>()
    private val lastSpoken = HashMap<String, Long>()
    private var lastResult: String? = null
    private var approachText: String? = null
    private var approachAt = 0L
    private var simJob: Job? = null
    private var markFrom: LatLng? = null
    private var markD0 = 0.0
    private var pendingMark: Corridor? = null
    private var markText = "Listede olmayanı kendin ekle"
    private var tick = 0L
    private var tripStart = 0L
    private var lastFixWall = 0L
    private var gapJob: Job? = null
    private val fullTrack = ArrayList<LatLng>()
    private val passes = ArrayList<CorridorPass>()
    private var lastCheckpoint = 0L
    private var arrivedAt: String? = null      // hedefe varıldı uyarısı (gösterilecek)
    private var arrivedFor: String? = null     // aynı hedef için tekrar sorma
    private var savedTarget: String? = null    // devam edilen yolculuğun hedef adı

    /** offset > 0: koridorun ortasından başlayan (kısmi) ölçüm; len = ölçülen kısmın boyu. */
    private class Run(var c: Corridor, val t0: Long, val startD: Double, val offset: Double = 0.0) {
        val partial: Boolean get() = offset > 0
        val len: Double get() = (c.lengthM - offset).coerceAtLeast(1.0)
        var now = t0
        var traveled = 0.0
        var confirmed = false
        var stopStart: Long? = null
        var stopAvgBefore: Double? = null
        var stopSlackBefore: Double? = null
        var stopNote: String? = null
        var stopTotal = 0.0
        var minDEnd = Double.MAX_VALUE
        var lastV = 10.0
        val elapsed: Double get() = (now - t0) / 1000.0
        val avg: Double? get() = if (elapsed > 5) traveled / elapsed * 3.6 else null
        val slack: Double? get() = c.limit?.let { elapsed - traveled / (it / 3.6) }
    }

    val tracking: Boolean get() = mode != "idle"

    fun say(text: String, key: String? = null, gap: Long = 30000) {
        if (!voiceOn) return
        val now = System.currentTimeMillis()
        if (key != null) {
            val l = lastSpoken[key]
            if (l != null && now - l < gap) return
            lastSpoken[key] = now
        }
        Speaker.say(text)
    }

    fun startTracking(m: String, resume: TripRecord? = null) {
        simJob?.cancel()
        simJob = null
        mode = m
        gpsText = if (m == "sim") "Örnek sürüş (×10 hız)" else "Konum bekleniyor…"
        prev = null
        run = null
        trip = Trip()
        track.clear()
        near.clear()
        announced.clear()
        rejected.clear()
        lastResult = null
        approachText = null
        markFrom = null
        pendingMark = null
        tripStart = System.currentTimeMillis()
        lastFixWall = 0L
        fullTrack.clear()
        passes.clear()
        arrivedAt = null
        arrivedFor = null
        savedTarget = resume?.target
        lastCheckpoint = System.currentTimeMillis()
        if (resume != null) {
            // Yarım kalan yolculuğa devam: önceki toplamlar ve rota korunur
            tripStart = resume.start
            trip = Trip(resume.distM, resume.timeS, resume.moveS, resume.stopInS + resume.stopOutS, resume.stopInS)
            fullTrack.addAll(resume.track)
            track.addAll(resume.track.takeLast(3000))
            passes.addAll(resume.passes)
        }
        if (m == "gps") TripStore.setActive("y$tripStart")
        gapJob?.cancel()
        if (m == "gps") {
            gapJob = scope.launch {
                while (true) {
                    delay(5000)
                    val since = System.currentTimeMillis() - lastFixWall
                    if (lastFixWall > 0 && since > 12000 && mode == "gps") {
                        gpsText = "GPS sinyali yok (${since / 1000} sn) — tünel olabilir, ölçüm sürüyor"
                        publish()
                    }
                }
            }
        }
        publish()
    }

    /** Süren yolculuğun şu anki hâli (ara kayıt ve bitiş için). */
    private fun record(): TripRecord {
        val v = VehicleStore.active
        return TripRecord(
                id = "y" + tripStart,
                start = tripStart,
                end = System.currentTimeMillis(),
                vehicleName = v.name,
                plate = v.plate,
                vehicleType = v.type.label,
                distM = trip.dist,
                timeS = trip.time,
                moveS = trip.move,
                stopInS = trip.stopIn,
                stopOutS = trip.stopOut,
                sample = mode == "sim",
                target = RouteSession.info?.target?.name ?: savedTarget,
                passes = ArrayList(passes),
                track = ArrayList(fullTrack)
            )
    }

    /** Takip bitince yolculuğu kaydeder ve aracın km sayacına ekler. */
    private fun saveTrip() {
        if (mode == "gps") TripStore.setActive(null)
        if (mode == "idle" || trip.dist < 300) {
            if (mode == "gps") TripStore.delete("y$tripStart") // çok kısa ara kayıt kalmasın
            return
        }
        TripStore.save(record())
        if (mode == "gps") VehicleStore.addKm(VehicleStore.active.id, trip.dist / 1000)
    }

    /** Uygulama ya da telefon kapansa da yolculuk kaybolmasın diye 30 sn'de bir ara kayıt. */
    private fun checkpoint() {
        if (mode != "gps" || trip.dist < 300) return
        val now = System.currentTimeMillis()
        if (now - lastCheckpoint < 30000) return
        lastCheckpoint = now
        TripStore.save(record())
    }

    /** Hedefe varınca bir kez sorar. */
    private fun checkArrival(p: LatLng) {
        val tgt = RouteSession.info?.target ?: return
        if (arrivedFor == tgt.name) return
        val d = Geo.dist(p, tgt.ll)
        if (d < 150 || (d < 300 && (speedKmh ?: 99) < 15)) {
            arrivedFor = tgt.name
            arrivedAt = tgt.name
            say("Hedefe vardın. İstersen sürüşü bitirebilirsin.")
        }
    }

    /** Yeni hedef seçildi: süren yolculuk kaydedilip bitirilir, aynı anda sıfırdan yenisi başlar. */
    fun newTrip() {
        if (mode == "idle") return
        if (mode == "sim") { stop(); return }
        val m = mode
        saveTrip()
        startTracking(m)
        say("Önceki sürüş kaydedildi, yeni sürüş başladı.")
    }

    fun dismissArrival() {
        arrivedAt = null
        publish()
    }

    /** Süren yolculuğun kısa özeti (açılış sorusu için). */
    fun summary(): String {
        val mins = (trip.time / 60).toInt()
        return Geo.fmtKm(trip.dist) + " · " + (if (mins >= 60) "${mins / 60} sa ${mins % 60} dk" else "$mins dk")
    }

    fun stop() {
        simJob?.cancel()
        simJob = null
        gapJob?.cancel()
        gapJob = null
        saveTrip()
        mode = "idle"
        gpsText = "Konum kapalı"
        run = null
        prev = null
        speedKmh = null
        publish()
    }

    fun gpsStatus(text: String) {
        gpsText = text
        publish()
    }

    private fun pool(): List<Corridor> =
        CorridorStore.all() + if (mode == "sim") listOf(CorridorStore.withOverride(DEMO)) else emptyList()

    fun onFix(f: Fix) {
        if (mode == "idle") return
        val acc = f.acc
        if (acc != null && acc > 60f) {
            gpsText = "GPS zayıf ±${acc.roundToInt()} m"
            publish()
            return
        }
        if (mode == "gps") gpsText = "GPS ±${(acc ?: 0f).roundToInt()} m"
        var vOut: Double? = f.speed
        val p = prev
        if (p != null) {
            val d = Geo.dist(p.ll, f.ll)
            val dt = (f.t - p.t) / 1000.0
            if (dt <= 0) return
            val v: Double = f.speed ?: (d / dt)
            vOut = v
            // Uzun kesintide (tünel vb.) duruş/hareket kararı aradaki ortalama hızla verilir
            val vEff = if (dt > 30) d / dt else v
            val r0 = run
            val inCorridor = r0?.confirmed == true
            if (r0 != null) {
                step(r0, d, dt, vEff)
                r0.lastV = v
                // Koridor çizgisi üzerindeki konum: kesintide düz çizginin kısa kalmasını telafi eder
                alongOf(r0.c, f.ll)?.let { a -> val rel = a - r0.offset; if (rel > r0.traveled) r0.traveled = rel }
            }
            if (true) {
                val moving = vEff * 3.6 >= 4
                trip = trip.copy(
                    dist = trip.dist + d,
                    time = trip.time + dt,
                    move = trip.move + (if (moving) dt else 0.0),
                    stop = trip.stop + (if (moving) 0.0 else dt),
                    stopIn = trip.stopIn + (if (!moving && inCorridor) dt else 0.0)
                )
            }
        }
        speedKmh = vOut?.let { (it * 3.6).roundToInt() }
        prev = f
        lastFixWall = System.currentTimeMillis()
        track.add(f.ll)
        if (track.size > 3000) track.removeAt(0)
        val lastFull = fullTrack.lastOrNull()
        if (lastFull == null || Geo.dist(lastFull, f.ll) > 25) fullTrack.add(f.ll)
        checkpoint()
        checkArrival(f.ll)

        val r = run
        if (r == null) {
            val pool = pool()
            approach(f.ll, pool)
            rejected.removeAll { k ->
                val c = pool.find { it.key == k }
                c == null || (Geo.dist(f.ll, c.from) > 800 && lineHit(c, f.ll, 300.0) == null)
            }
            val c = pool.firstOrNull { Geo.dist(f.ll, it.from) < ENTRY && it.key !in rejected }
            if (c != null) {
                run = Run(c, f.t, Geo.dist(f.ll, c.to))
            } else if ((speedKmh ?: 0) >= 25) {
                // Koridorun ortasındayız (bilgi geç geldi ya da takip geç başladı): buradan itibaren ölç
                val mid = pool.asSequence().filter { it.key !in rejected && (it.path.size >= 2 || it.lengthM < 3000) }
                    .mapNotNull { k -> lineHit(k, f.ll, 60.0)?.let { k to it } }
                    .firstOrNull { (k, a) -> a > ENTRY && a < k.lengthM - 400 }
                if (mid != null) run = Run(mid.first, f.t, Geo.dist(f.ll, mid.first.to), mid.second)
            }
        } else {
            check(r, f)
        }
        publish()
    }

    private fun step(r: Run, d: Double, dt: Double, v: Double) {
        r.traveled += d
        r.now += (dt * 1000).toLong()
        val kmh = v * 3.6
        if (kmh < 4) {
            if (r.confirmed) r.stopTotal += dt
            if (r.stopStart == null) {
                r.stopStart = r.now
                r.stopAvgBefore = r.avg
                r.stopSlackBefore = r.slack
            }
        } else if (kmh > 10) {
            val ss = r.stopStart ?: return
            val dur = (r.now - ss) / 1000.0
            val a0 = r.stopAvgBefore
            val a1 = r.avg
            if (dur >= 8 && a0 != null && a1 != null) {
                val s1 = r.slack
                val s0 = r.stopSlackBefore
                val gain = if (s1 != null && s0 != null) s1 - s0 else dur
                r.stopNote = "Son mola ${Geo.fmtT(dur)}: ortalama ${a0.roundToInt()} → ${a1.roundToInt()} km/s, +${gain.roundToInt()} sn pay."
                val tail = when {
                    s1 == null -> ""
                    s1 >= 0 -> " Toplam payın ${s1.roundToInt()} saniye."
                    else -> " Hâlâ ${(-s1).roundToInt()} saniye limitin üstündesin."
                }
                val mins = (dur / 60).toInt()
                val secs = (dur % 60).roundToInt()
                val durText = if (mins > 0) "$mins dakika $secs saniye" else "$secs saniye"
                say("$durText durdun. Ortalaman önce ${a0.roundToInt()}, şimdi ${a1.roundToInt()}. ${gain.roundToInt()} saniye kazandın.$tail")
            }
            r.stopStart = null
        }
    }

    private fun check(r: Run, f: Fix) {
        val dEnd = Geo.dist(f.ll, r.c.to)
        val passedEnd = r.minDEnd < 1500 && dEnd > r.minDEnd + 200 && r.traveled > r.len * 0.6
        if (dEnd < r.minDEnd) r.minDEnd = dEnd
        if (!r.confirmed && r.traveled > 250) {
            if (dEnd < r.startD - 100) {
                r.confirmed = true
                announceStart(r)
            } else {
                rejected.add(r.c.key) // ters yön ya da aynı yerden başlayan başka kol
                run = null
                return
            }
        }
        if (r.confirmed && (dEnd < ENTRY || passedEnd || r.traveled > r.len * 1.2)) {
            finish(r, dEnd)
        } else if (r.confirmed) {
            val lim = r.c.limit
            val a = r.avg
            if (lim != null && a != null && r.elapsed > 60 && a > lim && r.stopStart == null) {
                say("Dikkat, ortalaman ${a.roundToInt()}. Limit $lim.", "over", 45000)
            }
        }
    }

    /** Konumun koridor boyunca girişten itibaren kaç metre ilerde olduğu (çizgiye 300 m'den uzaksa null). */
    /** Koridor çizgisine maxD metreden yakınsa girişten itibaren konum (m), değilse null. */
    private fun lineHit(c: Corridor, p: LatLng, maxD: Double): Double? {
        val line = if (c.path.size >= 2) c.path else listOf(c.from, c.to)
        val kx = Math.cos(Math.toRadians(p.lat)) * 111320.0
        val ky = 110540.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var cum = 0.0
        for (i in 0 until line.size - 1) {
            val seg = Geo.dist(line[i], line[i + 1])
            val ax = (line[i].lon - p.lon) * kx
            val ay = (line[i].lat - p.lat) * ky
            val dx = (line[i + 1].lon - p.lon) * kx - ax
            val dy = (line[i + 1].lat - p.lat) * ky - ay
            val l2 = dx * dx + dy * dy
            val t = (if (l2 > 0) -(ax * dx + ay * dy) / l2 else 0.0).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            if (d < best) { best = d; bestAlong = cum + t * seg }
            cum += seg
        }
        if (best > maxD || cum <= 0) return null
        return bestAlong * (c.lengthM / cum)
    }

    private fun alongOf(c: Corridor, p: LatLng): Double? {
        val line = if (c.path.size >= 2) c.path else listOf(c.from, c.to)
        val kx = Math.cos(Math.toRadians(p.lat)) * 111320.0
        val ky = 110540.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var cum = 0.0
        var total = 0.0
        for (i in 0 until line.size - 1) total += Geo.dist(line[i], line[i + 1])
        for (i in 0 until line.size - 1) {
            val seg = Geo.dist(line[i], line[i + 1])
            val ax = (line[i].lon - p.lon) * kx
            val ay = (line[i].lat - p.lat) * ky
            val bx = (line[i + 1].lon - p.lon) * kx
            val by = (line[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = (if (l2 > 0) -(ax * dx + ay * dy) / l2 else 0.0).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                bestAlong = cum + t * seg
            }
            cum += seg
        }
        if (best > 300 || total <= 0) return null
        // Çizgi boyunu koridorun bilinen uzunluğuna ölçekle
        return bestAlong * (c.lengthM / total)
    }

    private fun announceStart(r: Run) {
        val c = r.c
        say(
            (if (r.partial) "Koridorun içindesin. Ortalaman buradan itibaren hesaplanıyor, kalan ${Geo.km1(r.len)} kilometre. "
            else "Ortalama hız koridoru başladı. Uzunluk ${Geo.km1(c.lengthM)} kilometre. ") +
                (c.limit?.let { "Limit $it." } ?: "Limit bilinmiyor.")
        )
        if (c.limit == null && mode == "gps") {
            scope.launch {
                val v = try {
                    TrafikApi.osmLimit(c.from)
                } catch (_: Exception) {
                    null
                }
                if (v != null && run === r && r.c.limit == null) {
                    r.c = r.c.copy(limit = v)
                    say("Bu koridorda yol limiti $v görünüyor. Levhayı kontrol et.")
                    publish()
                }
            }
        }
    }

    /**
     * Koridor bitişi: resmi ölçüm gibi koridor uzunluğu / iki kamera arası süre.
     * Çıkış kamerası geçildikten sonra gelen konumda, çıkıştan sonraki kısa süre düşülür.
     */
    private fun finish(r: Run, dEnd: Double) {
        val overT = dEnd / maxOf(r.lastV, 5 / 3.6)
        val tEnd = maxOf(1.0, r.elapsed - overT)
        val dist = maxOf(r.len, r.traveled - dEnd)
        val avg = dist / tEnd * 3.6
        val lim = r.c.limit
        val nm = r.c.name + if (r.partial) " (kısmi ölçüm)" else ""
        passes.add(CorridorPass(nm, lim, avg, r.elapsed, r.stopTotal, r.traveled))
        lastResult = "Son koridor: $nm — ortalama ${avg.roundToInt()} km/s" +
            (lim?.let { ", limit $it" } ?: "") + ", süre ${Geo.fmtT(r.elapsed)}."
        say(
            "Koridor bitti. Ortalaman ${avg.roundToInt()}" + when {
                lim == null -> "."
                avg <= lim -> ". Limitin altındasın."
                else -> ". Limitin üstündesin."
            }
        )
        run = null
    }

    private fun approach(p: LatLng, pool: List<Corridor>) {
        for (c in pool) {
            val k = c.key
            val d = Geo.dist(p, c.from)
            if (d < 1600) {
                val prevD = near[k]
                if (k !in announced && prevD != null && d < prevD - 25 && d > ENTRY * 2) {
                    announced.add(k)
                    approachText = "${Geo.km1(d)} km sonra koridor" + (c.limit?.let { " · limit $it" } ?: "") + " · ${c.name}"
                    approachAt = System.currentTimeMillis()
                    say(
                        "${Geo.km1(d)} kilometre sonra ortalama hız koridoru. " +
                            (c.limit?.let { "Limit $it, " } ?: "") +
                            "uzunluk ${Geo.km1(c.lengthM)} kilometre."
                    )
                }
                near[k] = d
            } else {
                near.remove(k)
                if (d > 5000) announced.remove(k)
            }
        }
    }

    fun setLimit(c: Corridor, v: Int) {
        CorridorStore.setLimit(c.key, v)
        run?.let { if (it.c.key == c.key) it.c = it.c.copy(limit = v) }
        publish()
    }

    // ---- Koridoru elle işaretleme ----
    fun markStart() {
        val p = prev
        if (p == null) {
            markText = "Önce takibi başlat"
        } else {
            markFrom = p.ll
            markD0 = trip.dist
            pendingMark = null
            markText = "Başlangıç kaydedildi, sonu bekleniyor"
            say("Koridor başı kaydedildi.")
        }
        publish()
    }

    fun markEnd() {
        val from = markFrom ?: return
        val p = prev ?: return
        val length = max(Geo.dist(from, p.ll), trip.dist - markD0)
        if (length < 300) {
            markText = "Çok kısa, tekrar dene"
        } else {
            pendingMark = Corridor(
                "u" + System.currentTimeMillis(),
                "Benim koridorum ${CorridorStore.userCount + 1}",
                from, p.ll, run?.c?.limit, length, "user"
            )
            markFrom = null
            markText = "${Geo.fmtKm(length)} · limiti gir ve kaydet"
        }
        publish()
    }

    fun saveMark(limit: Int?, name: String?) {
        val pm = pendingMark ?: return
        val c = pm.copy(limit = limit, name = name ?: pm.name)
        CorridorStore.addUser(c)
        if (limit != null) CorridorStore.setLimit(c.key, limit)
        pendingMark = null
        markText = "Kaydedildi: ${c.name}"
        say("Koridor kaydedildi.")
        publish()
    }

    // ---- Örnek sürüş: evde denemek için, gerçek GPS kullanmaz ----
    fun startSim() {
        startTracking("sim")
        val c = CorridorStore.withOverride(DEMO)
        val len = c.lengthM
        say("Örnek sürüş başladı.")
        simJob = scope.launch {
            var x = -400.0
            var t = System.currentTimeMillis()
            var stopLeft = 75
            var stopped = false
            while (true) {
                val v: Double = if (!stopped && x > 0.42 * len && stopLeft > 0) {
                    stopLeft--
                    if (stopLeft <= 0) stopped = true
                    0.0
                } else {
                    val base = when {
                        x < 0 -> 70.0
                        x < 0.35 * len -> 104.0
                        x < 0.55 * len -> 98.0
                        else -> 84.0
                    }
                    base + sin(t / 7000.0) * 3
                }
                val ms = v / 3.6
                x += ms
                t += 1000
                val k = x / len
                // Örnek tünel: koridorun %62–%74 arasında GPS sinyali yok
                if (x > 0.62 * len && x < 0.74 * len) {
                    if (gpsText != "Örnek tünel: GPS sinyali yok") {
                        gpsText = "Örnek tünel: GPS sinyali yok"
                        publish()
                    }
                } else {
                    if (gpsText.startsWith("Örnek tünel")) gpsText = "Örnek sürüş (×10 hız)"
                    onFix(Fix(c.from.lat + (c.to.lat - c.from.lat) * k, c.from.lon + (c.to.lon - c.from.lon) * k, t, ms, 8f))
                }
                if (x > len + 300) {
                    saveTrip()
                    mode = "idle"
                    gpsText = "Örnek sürüş bitti"
                    run = null
                    publish()
                    break
                }
                delay(100)
            }
        }
    }

    private fun publish() {
        val r = run
        val now = System.currentTimeMillis()
        if (approachText != null && now - approachAt > 60000) approachText = null
        val tripAvg = if (trip.time > 10) trip.dist / trip.time * 3.6 else null
        val moveAvg = if (trip.move > 10) trip.dist / trip.move * 3.6 else null
        var note: String? = lastResult
        var maxLeft = "—"
        var remaining = 0.0
        var progress = 0f
        if (r != null && r.confirmed) {
            val c = r.c
            remaining = max(0.0, r.len - r.traveled)
            progress = min(1.0, (r.offset + r.traveled) / c.lengthM).toFloat()
            val lim = c.limit
            if (lim != null) {
                val left = r.len / (lim / 3.6) - r.elapsed
                maxLeft = if (left <= 0) "Yasal limit" else {
                    val mv = remaining / left * 3.6
                    if (mv > lim) "Yasal limit" else "${max(0, mv.roundToInt())} km/s"
                }
            }
            val ss = r.stopStart
            note = if (ss != null && r.now - ss >= 8000) {
                val dur = (r.now - ss) / 1000.0
                val gain = (r.slack ?: 0.0) - (r.stopSlackBefore ?: 0.0)
                "Duruyorsun: ${Geo.fmtT(dur)}. Bu molayla ${gain.roundToInt()} sn pay kazandın; ortalaman " +
                    "${(r.stopAvgBefore ?: 0.0).roundToInt()} → ${(r.avg ?: 0.0).roundToInt()} km/s."
            } else {
                r.stopNote
            }
        }
        tick++
        state.value = UiState(
            mode = mode,
            gpsText = gpsText,
            speedKmh = speedKmh,
            pos = prev?.ll,
            corridor = r?.c,
            confirmed = r?.confirmed == true,
            avg = r?.avg,
            traveled = r?.traveled ?: 0.0,
            remaining = remaining,
            elapsed = r?.elapsed ?: 0.0,
            corridorStop = r?.stopTotal ?: 0.0,
            slack = r?.slack,
            maxAvgLeft = maxLeft,
            progress = progress,
            note = note,
            approach = approachText,
            trip = trip,
            tripAvg = tripAvg,
            moveAvg = moveAvg,
            track = ArrayList(track),
            markActive = markFrom != null,
            pendingMark = pendingMark,
            partial = r?.partial == true,
            markText = markText,
            arrived = arrivedAt,
            tick = tick
        )
    }
}
