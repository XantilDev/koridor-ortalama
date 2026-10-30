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

data class Trip(val dist: Double = 0.0, val time: Double = 0.0, val move: Double = 0.0, val stop: Double = 0.0)

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
    val markText: String = "Listede olmayanı kendin ekle",
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

    private class Run(var c: Corridor, val t0: Long, val startD: Double) {
        var now = t0
        var traveled = 0.0
        var confirmed = false
        var stopStart: Long? = null
        var stopAvgBefore: Double? = null
        var stopSlackBefore: Double? = null
        var stopNote: String? = null
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

    fun startTracking(m: String) {
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
        publish()
    }

    fun stop() {
        simJob?.cancel()
        simJob = null
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
            val r0 = run
            if (r0 != null) step(r0, d, dt, v)
            if (dt <= 30) {
                val moving = v * 3.6 >= 4
                trip = trip.copy(
                    dist = trip.dist + d,
                    time = trip.time + dt,
                    move = trip.move + (if (moving) dt else 0.0),
                    stop = trip.stop + (if (moving) 0.0 else dt)
                )
            }
        }
        speedKmh = vOut?.let { (it * 3.6).roundToInt() }
        prev = f
        track.add(f.ll)
        if (track.size > 3000) track.removeAt(0)

        val r = run
        if (r == null) {
            val pool = pool()
            approach(f.ll, pool)
            rejected.removeAll { k ->
                val c = pool.find { it.key == k }
                c == null || Geo.dist(f.ll, c.from) > 800
            }
            val c = pool.firstOrNull { Geo.dist(f.ll, it.from) < ENTRY && it.key !in rejected }
            if (c != null) run = Run(c, f.t, Geo.dist(f.ll, c.to))
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
        if (r.confirmed && (dEnd < ENTRY || r.traveled > r.c.lengthM * 1.2)) {
            finish(r)
        } else if (r.confirmed) {
            val lim = r.c.limit
            val a = r.avg
            if (lim != null && a != null && r.elapsed > 60 && a > lim && r.stopStart == null) {
                say("Dikkat, ortalaman ${a.roundToInt()}. Limit $lim.", "over", 45000)
            }
        }
    }

    private fun announceStart(r: Run) {
        val c = r.c
        say(
            "Ortalama hız koridoru başladı. " +
                (c.limit?.let { "Limit $it. " } ?: "Limit bilinmiyor. ") +
                "Uzunluk ${Geo.km1(c.lengthM)} kilometre."
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

    private fun finish(r: Run) {
        val avg = if (r.elapsed > 0) r.traveled / r.elapsed * 3.6 else 0.0
        val lim = r.c.limit
        lastResult = "Son koridor: ${r.c.name} — ortalama ${avg.roundToInt()} km/s" +
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
                onFix(Fix(c.from.lat + (c.to.lat - c.from.lat) * k, c.from.lon + (c.to.lon - c.from.lon) * k, t, ms, 8f))
                if (x > len + 300) {
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
            remaining = max(0.0, c.lengthM - r.traveled)
            progress = min(1.0, r.traveled / c.lengthM).toFloat()
            val lim = c.limit
            if (lim != null) {
                val left = c.lengthM / (lim / 3.6) - r.elapsed
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
            markText = markText,
            tick = tick
        )
    }
}
