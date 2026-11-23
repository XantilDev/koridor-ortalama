package com.xantildev.koridor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

/** EGM Trafik Başkanlığı'nın "İller Arası Radar ve Kontrol Noktası" sayfasının kullandığı servis. */
object TrafikApi {
    private const val BASE = "https://www.trafik.gov.tr/ISAYWebPart/PolGenControlPointV2/"
    private const val PAGE = "https://www.trafik.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari"

    data class City(val id: Int, val name: String)
    data class District(val id: Int, val name: String, val lat: Double, val lon: Double)

    // EGM'yi yormamak (ve engellenmemek) için her istek arasında en az 10 sn; ücretsiz modda 30 sn.
    // Hata gelirse ara uzar (en çok 60 sn ek), başarılı cevapta normale döner.
    private val egmLock = Any()
    @Volatile private var egmLast = 0L
    @Volatile private var egmPenalty = 0L
    val egmGapMs: Long get() = if (Access.freeMode) 30_000L else 10_000L

    private fun egmWait() = synchronized(egmLock) {
        val wait = egmLast + egmGapMs + egmPenalty - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        egmLast = System.currentTimeMillis()
    }

    private fun http(url: String, form: Map<String, String>? = null, trafik: Boolean = true): String {
        if (trafik) egmWait()
        return try {
            httpOnce(url, form, trafik).also { if (trafik) egmPenalty = 0L }
        } catch (e: IOException) {
            if (trafik) egmPenalty = minOf(maxOf(egmPenalty * 2, 10_000L), 60_000L)
            throw e
        }
    }

    private fun httpOnce(url: String, form: Map<String, String>?, trafik: Boolean): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KoridorOrtalama/0.1")
        c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
        if (trafik) {
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Referer", PAGE)
        }
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IOException("HTTP $code")
        return text
    }

    suspend fun cities(): List<City> = withContext(Dispatchers.IO) {
        val a = JSONArray(http(BASE + "GetCities"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            City(o.getInt("Id"), o.getString("Name"))
        }
    }

    suspend fun districts(cityId: Int): List<District> = withContext(Dispatchers.IO) {
        val a = JSONArray(http(BASE + "GetDistricts?cityId=$cityId"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            District(o.getInt("Id"), o.getString("Name"), o.getDouble("Latitude"), o.getDouble("Longitude"))
        }
    }

    /** Sitede "ROTA OLUŞTUR"a basmakla aynı: tek istek, rotadaki hız koridorları limitleriyle döner. */
    private fun createRouteOnce(from: District, to: District): JSONObject {
        val form = mapOf(
            "fromLatitude" to from.lat.toString(),
            "fromLongitude" to from.lon.toString(),
            "toLatitude" to to.lat.toString(),
            "toLongitude" to to.lon.toString(),
            "fromDistrictId" to from.id.toString(),
            "toDistrictId" to to.id.toString()
        )
        val o = JSONObject(http(BASE + "CreateRoute", form))
        if (!o.optBoolean("success")) {
            egmPenalty = minOf(maxOf(egmPenalty * 2, 10_000L), 60_000L)
            val m = o.optString("message")
            throw IOException(
                if (m.contains("Servis çağrılırken")) "trafik.gov.tr rota servisi şu an yanıt vermiyor; sitenin kendisi de aynı hatayı veriyor"
                else m.ifBlank { "Servis hata verdi" }
            )
        }
        return o
    }

    /** Hangi yöntemle cevap alındı (Teşhis'te gösterilir). */
    @Volatile var lastMethod: String? = null

    /**
     * Önce tam konumla sor; EGM "servis hatası" verirse sitenin yaptığı gibi ilçe merkezleriyle tekrar sor
     * (site ilçe seçince ilçe merkezinin koordinatını gönderir).
     */
    private fun createRoute(from: District, to: District): JSONObject {
        val fc = DistrictFinder.center(from.id); val tc = DistrictFinder.center(to.id)
        // Önce sitenin yöntemi: ilçe merkezleri (tam konum bazen EGM'de servis hatası veriyor)
        if (fc != null && tc != null) {
            try {
                return createRouteOnce(from.copy(lat = fc.lat, lon = fc.lon), to.copy(lat = tc.lat, lon = tc.lon)).also { lastMethod = "ilçe merkezi" }
            } catch (_: IOException) { }
        }
        return createRouteOnce(from, to).also { lastMethod = "tam konum" }
    }

    /** Yalnız radar/kontrol sayıları (ilçe arası bölüm hesabı için; ana rota yanıtını değiştirmez). Hata varsa nedeni. */
    suspend fun counts(from: District, to: District): Pair<Enforcement?, String?> = withContext(Dispatchers.IO) {
        try {
            val e = enforcement(createRoute(from, to))
            if (e.radar == null && e.other == null) e to "yanıtta sayı yok"
            else { EgmShare.save(from.id, to.id, e); e to null }
        } catch (e: Exception) {
            // EGM kesik: başka bir çalışanın (ya da bizim) son aldığı sonuç varsa o kullanılır
            EgmShare.load(from.id, to.id)?.let { c ->
                TLog.i("egm", "EGM yanıt vermedi (${why(e)}); ortak kayıttan alındı", org.json.JSONObject()
                    .put("from", from.id).put("to", to.id).put("yas_dk", (System.currentTimeMillis() - (c.cachedAt ?: 0)) / 60000))
                return@withContext c to null
            }
            null to why(e)
        }
    }

    /** Hata nedenini anlaşılır yaz (teşhis için). */
    fun why(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "internet yok ya da EGM adresi çözülemedi"
        is java.net.SocketTimeoutException -> "EGM zamanında cevap vermedi"
        is java.net.ConnectException -> "EGM bağlantıyı reddetti"
        is javax.net.ssl.SSLException -> "güvenli bağlantı kurulamadı"
        else -> when (val m = e.message ?: "") {
            "HTTP 429" -> "çok fazla istek (HTTP 429, geçici engel)"
            "HTTP 403" -> "erişim engellendi (HTTP 403)"
            "HTTP 503", "HTTP 502", "HTTP 500" -> "EGM servisi hata verdi ($m)"
            else -> m.ifBlank { e.javaClass.simpleName }
        }
    }

    suspend fun route(from: District, to: District): List<Corridor> = withContext(Dispatchers.IO) {
        val o = createRoute(from, to)
        lastRaw = o.toString().take(200_000)
        lastEnforcement = try { enforcement(o) } catch (_: Exception) { null }
        lastEnforcement?.let { EgmShare.save(from.id, to.id, it) }
        val data = o.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("SpeedTunnels") ?: JSONArray()
        val out = ArrayList<Corridor>()
        for (i in 0 until arr.length()) {
            try {
                val s = arr.getJSONObject(i)
                val vt = s.optInt("vehicleType", 1)
                if (vt != 1 && vt != 0) continue
                val f = LatLng(s.getDouble("startLatY"), s.getDouble("startLonX"))
                val t = LatLng(s.getDouble("endLatY"), s.getDouble("endLonX"))
                val co = s.optJSONArray("coordinates")
                var path: List<LatLng> = if (co == null) emptyList() else (0 until co.length()).map { j ->
                    val q = co.getJSONObject(j)
                    LatLng(q.getDouble("y"), q.getDouble("x"))
                }
                val plen = if (path.size >= 2) Geo.pathLength(path) else 0.0
                val len = if (plen > 100) plen else max(s.optDouble("length", 0.0) * 1000, Geo.dist(f, t))
                if (path.size > 60) {
                    val step = path.size / 50.0
                    path = (0 until 50).map { k -> path[(k * step).toInt()] } + path.last()
                }
                val lim = s.optInt("speedLimit", 0).takeIf { it in 20..160 }
                val name = s.optString("name").replace(Regex("\\s+"), " ").trim().ifBlank { "Hız koridoru" }
                out.add(Corridor("tg" + s.optString("id"), name, f, t, lim, len, "tg", path))
            } catch (_: Exception) {
            }
        }
        out
    }

    /** Son CreateRoute yanıtı (Ayrıntılar'dan paylaşılabilir; alan adlarını görmek için). */
    @Volatile var lastRaw: String? = null
    @Volatile var lastEnforcement: Enforcement? = null

    /** Rotadaki trafik uygulamaları: sayılar (radarlı/radarsız) ve varsa koordinatlı noktalar. */
    class Enforcement(val radar: Int?, val other: Int?, val points: List<LatLng>, val fields: String) {
        /** EGM cevap vermediği için ortak kayıttan (Supabase) alındıysa o sonucun saati. */
        var cachedAt: Long? = null
        val text: String?
            get() = when {
                radar != null && other != null -> "Rotada $radar radar noktası, $other kontrol noktası var."
                radar != null -> "Rotada $radar radar noktası var."
                other != null -> "Rotada $other trafik uygulaması var."
                points.isNotEmpty() -> "Rotada ${points.size} trafik uygulama noktası var."
                else -> null
            }
    }

    /**
     * Alan adları yayınlanmadığı için yanıt genel olarak taranır (diziler dahil, SpeedTunnels hariç).
     * Aynı adlı sayılar toplanır (ör. il il radar sayıları). Sitedeki kutular: Radar Noktası, Kontrol Noktası.
     */
    private fun enforcement(o: JSONObject): Enforcement {
        val sums = LinkedHashMap<String, Int>()      // yaprak alan adı → toplam
        val arrs = LinkedHashMap<String, JSONArray>()
        val pts = ArrayList<LatLng>()
        fun num(v: Any?): Int? = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> if (v == Math.floor(v) && v in 0.0..10000.0) v.toInt() else null
            is String -> v.trim().takeIf { it.length <= 5 }?.toIntOrNull()
            else -> null
        }
        fun walkObj(x: JSONObject, path: String, depth: Int) {
            if (depth > 6) return
            val lat = listOf("latitude", "lat", "latY", "Latitude", "Lat", "LatY").firstNotNullOfOrNull { k -> x.opt(k)?.toString()?.toDoubleOrNull() }
            val lon = listOf("longitude", "lon", "lng", "lonX", "Longitude", "Lon", "LonX").firstNotNullOfOrNull { k -> x.opt(k)?.toString()?.toDoubleOrNull() }
            if (lat != null && lon != null && lat in 35.0..43.0 && lon in 25.0..46.0 && path.isNotEmpty()) pts.add(LatLng(lat, lon))
            for (k in x.keys()) {
                if (k == "SpeedTunnels") continue
                val v = x.opt(k)
                when (v) {
                    is JSONObject -> walkObj(v, "$path$k.", depth + 1)
                    is JSONArray -> {
                        arrs["$path$k"] = v
                        for (i in 0 until v.length()) v.optJSONObject(i)?.let { walkObj(it, "$path$k[].", depth + 1) }
                    }
                    else -> num(v)?.let { n -> if (!k.contains("id", true) || k.contains("count", true)) sums[k] = (sums[k] ?: 0) + n }
                }
            }
        }
        walkObj(o, "", 0)
        fun lc(k: String) = k.lowercase().replace("ı", "i").replace("İ", "i")
        fun isOther(k: String) = lc(k).let { it.contains("radarsiz") || it.contains("nonradar") || it.contains("withoutradar") || it.contains("noradar") }
        fun isRadar(k: String) = lc(k).contains("radar") && !isOther(k)
        fun isControl(k: String) = lc(k).let { (it.contains("control") || it.contains("kontrol")) && !it.contains("radar") }
        val radar = sums.entries.filter { isRadar(it.key) }.maxByOrNull { it.value }?.value
            ?: arrs.entries.firstOrNull { isRadar(it.key.substringAfterLast('.')) }?.value?.length()
        val other = (sums.entries.filter { isOther(it.key) } + sums.entries.filter { isControl(it.key) && lc(it.key).contains("count") })
            .maxByOrNull { it.value }?.value
            ?: arrs.entries.firstOrNull { isOther(it.key.substringAfterLast('.')) || isControl(it.key.substringAfterLast('.')) }?.value?.length()
        val fields = (sums.map { "${it.key}=${it.value}" } + arrs.map { "${it.key}[${it.value.length()}]" }).joinToString(", ")
        return Enforcement(radar, other, pts, fields)
    }

    /** OpenStreetMap'te bu noktadaki yolun kayıtlı limiti (varsa). */
    suspend fun osmLimit(p: LatLng): Int? = withContext(Dispatchers.IO) {
        val q = "[out:json][timeout:10];way(around:30,${p.lat},${p.lon})[highway][maxspeed];out tags 3;"
        val txt = http("https://overpass-api.de/api/interpreter", mapOf("data" to q), trafik = false)
        val el = JSONObject(txt).optJSONArray("elements") ?: return@withContext null
        if (el.length() == 0) return@withContext null
        el.getJSONObject(0).optJSONObject("tags")?.optString("maxspeed")
            ?.takeWhile { it.isDigit() }?.toIntOrNull()
    }
}

/**
 * EGM sonuçlarının ortak kaydı (Supabase egm_sonuc): ilçe → ilçe radar/kontrol sayısı ve saati.
 * EGM cevap verince yazılır; kesikken son bilinen sonuç (en çok 7 gün) okunur. Yalnız firma sürümü.
 */
object EgmShare {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, TrafikApi.Enforcement>()

    fun save(from: Int, to: Int, e: TrafikApi.Enforcement) {
        if (e.cachedAt != null || (e.radar == null && e.other == null)) return
        val copy = TrafikApi.Enforcement(e.radar, e.other, emptyList(), "").also { it.cachedAt = System.currentTimeMillis() }
        mem["$from/$to"] = copy
        val token = Firma.logToken ?: return
        Thread {
            runCatching {
                Firma.rpc("egm_sonuc_yaz", org.json.JSONObject().put("p_token", token).put("p_from", from).put("p_to", to)
                    .put("p_radar", e.radar ?: org.json.JSONObject.NULL).put("p_kontrol", e.other ?: org.json.JSONObject.NULL))
            }
        }.start()
    }

    /** Çağıran zaten IO iş parçacığında. */
    fun load(from: Int, to: Int): TrafikApi.Enforcement? {
        val token = Firma.logToken
        if (token != null) runCatching {
            val text = Firma.rpc("egm_sonuc_oku", org.json.JSONObject().put("p_token", token).put("p_from", from).put("p_to", to))
            if (text.isNotBlank() && text != "null") {
                val o = org.json.JSONObject(text)
                return TrafikApi.Enforcement(if (o.isNull("radar")) null else o.getInt("radar"), if (o.isNull("kontrol")) null else o.getInt("kontrol"), emptyList(), "")
                    .also { it.cachedAt = o.getLong("zaman") * 1000 }
            }
        }
        return mem["$from/$to"]
    }
}
