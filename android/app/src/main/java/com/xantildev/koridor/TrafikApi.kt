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

    private fun http(url: String, form: Map<String, String>? = null, trafik: Boolean = true): String {
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
    suspend fun route(from: District, to: District): List<Corridor> = withContext(Dispatchers.IO) {
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
            val m = o.optString("message")
            throw IOException(
                if (m.contains("Servis çağrılırken")) "trafik.gov.tr rota servisi şu an yanıt vermiyor; sitenin kendisi de aynı hatayı veriyor"
                else m.ifBlank { "Servis hata verdi" }
            )
        }
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
