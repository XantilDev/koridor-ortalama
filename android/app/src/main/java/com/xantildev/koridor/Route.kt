package com.xantildev.koridor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.hypot

/** Açık sekme: başka ekrandan (ör. Google Haritalar paylaşımı) yönlendirmek için. */
object Nav {
    var tab by mutableIntStateOf(0)
}

/** Seçili hedef ve rota bilgisi. Uygulama açık olduğu sürece sekmeler arasında korunur. */
object RouteSession {
    class Info(
        val target: Places.Place,
        val corridors: List<Corridor>,
        val line: List<LatLng>,
        val totalM: Double,
        val durationS: Double
    ) {
        private val cum: DoubleArray = DoubleArray(line.size).also { c ->
            for (i in 1 until line.size) c[i] = c[i - 1] + Geo.dist(line[i - 1], line[i])
        }

        /** Rota üzerinde kalan mesafe; rotadan 1,5 km'den fazla uzaktaysa düz çizgi. */
        fun remainingFrom(p: LatLng): Double {
            if (line.size < 2) return Geo.dist(p, target.ll)
            val kx = Math.cos(Math.toRadians(p.lat)) * 111320.0
            val ky = 110540.0
            var bestD = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in 0 until line.size - 1) {
                val ax = (line[i].lon - p.lon) * kx
                val ay = (line[i].lat - p.lat) * ky
                val bx = (line[i + 1].lon - p.lon) * kx
                val by = (line[i + 1].lat - p.lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val l2 = dx * dx + dy * dy
                var t = if (l2 > 0) -(ax * dx + ay * dy) / l2 else 0.0
                t = t.coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < bestD) {
                    bestD = d
                    bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
                }
            }
            return if (bestD > 1500) Geo.dist(p, target.ll) * 1.25 else (cum.last() - bestAlong).coerceAtLeast(0.0)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var info by mutableStateOf<Info?>(null)
    var busy by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var msgBad by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Places.Place>>(emptyList())

    fun clear() {
        info = null
        msg = null
    }

    fun search(ctx: Context) {
        val text = query.trim()
        if (text.isEmpty() || busy) return
        busy = true
        msg = "Aranıyor…"
        msgBad = false
        scope.launch {
            try {
                results = Places.search(text, Places.currentLocation(ctx))
                msg = if (results.isEmpty()) {
                    "Sonuç bulunamadı. Firmayı Google Haritalar'da bulup “Paylaş → Koridor Ortalama” ile gönderebilirsin."
                } else null
                msgBad = results.isEmpty()
            } catch (e: Exception) {
                msg = "Arama yapılamadı: ${e.message ?: "bağlantı hatası"}."
                msgBad = true
            }
            busy = false
        }
    }

    fun go(ctx: Context, p: Places.Place) {
        val here = Places.currentLocation(ctx)
        if (here == null) {
            msg = "Şu anki konumun bilinmiyor. Sürüş ekranında “GPS ile başlat”a bas, konum gelince tekrar dene."
            msgBad = true
            return
        }
        busy = true
        results = emptyList()
        msg = "Rota hesaplanıyor: ${p.name}…"
        msgBad = false
        scope.launch {
            // Yol çizgisi ve mesafe (varış tahmini için)
            val road = try {
                osrm(here, p.ll)
            } catch (_: Exception) {
                null
            }
            // Koridorlar (trafik.gov.tr)
            var corridors: List<Corridor> = emptyList()
            var err: String? = null
            try {
                val a = DistrictFinder.nearest(here, null)
                val b = DistrictFinder.nearest(p.ll, p.province)
                corridors = TrafikApi.route(a, b)
                CorridorStore.addRoute(corridors)
            } catch (e: Exception) {
                err = "Koridorlar alınamadı: ${e.message ?: "bağlantı hatası"}. Varış tahmini yine çalışır."
            }
            val line = road?.first ?: listOf(here, p.ll)
            val total = road?.second ?: (Geo.dist(here, p.ll) * 1.25)
            val dur = road?.third ?: (total / (80 / 3.6))
            info = Info(p, corridors, line, total, dur)
            msg = err
            msgBad = err != null
            busy = false
            if (err == null) {
                Engine.say(
                    if (corridors.isEmpty()) "${p.name} rotası hazır. Rotada kayıtlı ortalama hız koridoru yok."
                    else "${p.name} rotası hazır. Rotanızda ${corridors.size} ortalama hız koridoru var."
                )
            }
        }
    }

    /** OSRM (OpenStreetMap) ile araç rotası: çizgi, metre, saniye. */
    private suspend fun osrm(a: LatLng, b: LatLng): Triple<List<LatLng>, Double, Double> = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?overview=simplified&geometries=geojson"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "KoridorOrtalama/0.1")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(text)
        val r = o.getJSONArray("routes").getJSONObject(0)
        val co = r.getJSONObject("geometry").getJSONArray("coordinates")
        val line = (0 until co.length()).map { i ->
            val q = co.getJSONArray(i)
            LatLng(q.getDouble(1), q.getDouble(0))
        }
        Triple(line, r.getDouble("distance"), r.getDouble("duration"))
    }

    /** Google Haritalar'dan paylaşılan metni (isim + kısa link) bir yere çevirir. */
    fun fromShared(ctx: Context, text: String) {
        Nav.tab = 1
        busy = true
        msg = "Google Haritalar'dan gelen yer çözülüyor…"
        msgBad = false
        scope.launch {
            val p = try {
                resolveShared(text)
            } catch (_: Exception) {
                null
            }
            busy = false
            if (p == null) {
                msg = "Paylaşılan linkten konum okunamadı. Yerin adını arama kutusuna yazmayı dene."
                msgBad = true
                query = text.lineSequence().firstOrNull { it.isNotBlank() && !it.contains("http") }?.trim() ?: ""
            } else {
                go(ctx, p)
            }
        }
    }

    private val reCoords = listOf(
        Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
        Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
        Regex("[?&](?:q|ll|query|destination)=(-?\\d+\\.\\d+)(?:,|%2C)\\s*(-?\\d+\\.\\d+)"),
        Regex("center=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)")
    )

    private fun coordsIn(s: String): LatLng? {
        for (re in reCoords) {
            val m = re.find(s) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in 35.0..43.0 && lon in 25.0..45.5) return LatLng(lat, lon)
        }
        return null
    }

    private suspend fun resolveShared(text: String): Places.Place? = withContext(Dispatchers.IO) {
        val url = Regex("https?://\\S+").find(text)?.value ?: return@withContext null
        val name = text.substringBefore(url).lineSequence().map { it.trim().trimEnd(',') }
            .firstOrNull { it.isNotBlank() } ?: "Paylaşılan yer"
        var u = url
        var body = ""
        for (i in 0 until 8) {
            coordsIn(u)?.let { return@withContext Places.Place(name, "Google Haritalar", it.lat, it.lon, null) }
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "tr")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (code in 300..399 && loc != null) {
                u = URL(URL(u), loc).toString()
                c.disconnect()
                continue
            }
            body = try {
                c.inputStream.bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                ""
            }
            c.disconnect()
            break
        }
        val ll = coordsIn(u) ?: coordsIn(body)
        if (ll != null) return@withContext Places.Place(name, "Google Haritalar", ll.lat, ll.lon, null)
        // Koordinat yoksa isimle ara
        Places.search(name, null).firstOrNull()
    }

    /** Yol tarifini Google Haritalar'da açar (dönüş dönüş yönlendirme için). */
    fun openInMaps(ctx: Context, p: Places.Place) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lon}"))
            .setPackage("com.google.android.apps.maps")
        try {
            ctx.startActivity(nav)
        } catch (_: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")))
        }
    }
}
