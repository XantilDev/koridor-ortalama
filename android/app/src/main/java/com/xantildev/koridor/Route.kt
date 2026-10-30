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
        val durationS: Double,
        val start: LatLng,
        val startLabel: String,
        val targetLabel: String,
        val lineSource: String
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
        if (busy) return
        busy = true
        results = emptyList()
        msg = "Konumun alınıyor…"
        msgBad = false
        scope.launch {
            val here = Places.freshLocation(ctx)
            if (here == null) {
                msg = "Şu anki konumun alınamadı. Telefonun konumu açık mı? Sürüş ekranında “GPS ile başlat”a basıp konum gelince tekrar dene."
                msgBad = true
                busy = false
                return@launch
            }
            msg = "Rota hesaplanıyor: ${p.name}…"
            // Yol çizgisi ve mesafe (varış tahmini için)
            val road = try {
                osrm(here, p.ll)
            } catch (_: Exception) {
                null
            }
            // Koridorlar (trafik.gov.tr)
            var corridors: List<Corridor> = emptyList()
            var err: String? = null
            var startLabel = "konumun"
            var targetLabel = p.name
            try {
                val a = DistrictFinder.nearest(here, null)
                val b = DistrictFinder.nearest(p.ll, p.province)
                startLabel = a.name
                targetLabel = b.name
                corridors = TrafikApi.route(a, b)
                CorridorStore.addRoute(corridors)
            } catch (e: Exception) {
                err = "Koridorlar alınamadı: ${e.message ?: "bağlantı hatası"}. Varış tahmini yine çalışır."
            }
            val line = road?.first ?: listOf(here, p.ll)
            val total = road?.second ?: (Geo.dist(here, p.ll) * 1.25)
            val dur = road?.third ?: (total / (80 / 3.6))
            info = Info(p, corridors, line, total, dur, here, startLabel, targetLabel, if (road != null) "yol" else "düz")
            msg = listOfNotNull(Places.lastFixNote, err).joinToString("\n").ifBlank { null }
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
                msg = "Paylaşılan linkten konum okunamadı. Yerin adını ya da adresini arama kutusuna yazmayı dene." +
                    if (lastSharedDebug.isNotBlank()) "\n(Link: $lastSharedDebug)" else ""
                msgBad = true
                query = text.lineSequence().firstOrNull { it.isNotBlank() && !it.contains("http") }?.trim() ?: ""
            } else {
                go(ctx, p)
            }
        }
    }

    // Yalnızca yerin kendi işareti ya da açık koordinat kabul edilir. Haritanın görünüm merkezi (@…, center=)
    // Google'ın bağlantıya göre açtığı varsayılan bölgedir (ör. Ankara), hedef olarak kullanılmaz.
    private val reCoords = listOf(
        Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
        Regex("[?&](?:q|ll|query|destination|daddr)=(-?\\d+\\.\\d+)(?:,|%2C)\\s*(-?\\d+\\.\\d+)")
    )
    private val reFtid = Regex("(0x[0-9a-f]{6,}:0x[0-9a-f]{6,})")
    private val reCid = Regex("[?&]cid=(\\d+)")

    private fun coordsIn(s: String): LatLng? {
        for (re in reCoords) {
            val m = re.find(s) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in 35.0..43.0 && lon in 25.0..45.5) return LatLng(lat, lon)
        }
        return null
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"

    /** Yönlendirmeleri tek tek izler; son adresi ve sayfa içeriğini döner. */
    private fun follow(start: String): Pair<String, String> {
        var u = start
        for (i in 0 until 10) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", DESKTOP_UA)
            c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (code in 300..399 && loc != null) {
                u = URL(URL(u), loc).toString()
                c.disconnect()
                continue
            }
            val body = try {
                c.inputStream.bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                ""
            }
            c.disconnect()
            return u to body
        }
        return u to ""
    }

    /** Google'ın firma kartı: firma kimliğinden gerçek konum, ad ve adres. */
    private fun previewPlace(ftid: String): Places.Place? {
        val pb = "!1m14!1s$ftid!3m12!1m3!1d1000!2d35.0!3d39.0!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1"
        val (_, t) = follow(
            "https://www.google.com/maps/preview/place?authuser=0&hl=tr&gl=tr&pb=" +
                java.net.URLEncoder.encode(pb, "UTF-8")
        )
        if (t.isBlank()) return null
        try {
            val a = org.json.JSONArray(t.substringAfter('\n'))
            val d = a.getJSONArray(6)
            val c = d.getJSONArray(9)
            val lat = c.getDouble(2)
            val lon = c.getDouble(3)
            val addr = d.optString(39).takeIf { it != "null" } ?: ""
            val name = d.optString(11).takeIf { it.isNotBlank() && it != "null" } ?: "Paylaşılan yer"
            val prov = if (addr.contains('/')) addr.substringAfterLast('/').trim() else null
            if (lat in 35.0..43.0 && lon in 25.0..45.5) return Places.Place(name, addr, lat, lon, prov)
        } catch (_: Exception) {
        }
        val m = Regex("\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]").find(t) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in 35.0..43.0 && lon in 25.0..45.5) Places.Place("Paylaşılan yer", "", lat, lon, null) else null
    }

    var lastSharedDebug: String = ""

    private suspend fun resolveShared(text: String): Places.Place? = withContext(Dispatchers.IO) {
        val url = Regex("https?://\\S+").find(text)?.value ?: return@withContext null
        val sharedName = text.substringBefore(url).lineSequence().map { it.trim().trimEnd(',') }
            .firstOrNull { it.isNotBlank() }
        fun named(p: Places.Place) = if (sharedName != null) p.copy(name = sharedName) else p
        fun pin(ll: LatLng) = Places.Place(sharedName ?: "Paylaşılan yer", "Google Haritalar", ll.lat, ll.lon, null)

        coordsIn(url)?.let { return@withContext pin(it) }
        val (finalUrl, body) = follow(url)
        lastSharedDebug = finalUrl.take(160)
        coordsIn(finalUrl)?.let { return@withContext pin(it) }

        // Firma kimliği: linkte, yoksa sayfada; cid varsa 0x0:0x<cid>
        val ftid = reFtid.find(finalUrl)?.value
            ?: reCid.find(finalUrl)?.groupValues?.get(1)?.toBigIntegerOrNull()?.let { "0x0:0x" + it.toString(16) }
            ?: reFtid.find(body)?.value
        if (ftid != null) {
            previewPlace(ftid)?.let { return@withContext named(it) }
        }

        // Linkte açık adres/isim araması varsa
        val q = Regex("[?&]q=([^&]+)").find(finalUrl)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val placeName = Regex("/maps/place/([^/@?]+)").find(finalUrl)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8").replace('+', ' ') }
        for (term in listOfNotNull(q, sharedName, placeName).distinct()) {
            val hit = Places.search(term, null).firstOrNull()
            if (hit != null) return@withContext named(hit)
        }
        null
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
