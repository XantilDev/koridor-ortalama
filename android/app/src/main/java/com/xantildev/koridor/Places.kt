package com.xantildev.koridor

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Adres, firma ve yer araması (OpenStreetMap Nominatim). */
object Places {
    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val province: String?) {
        val ll: LatLng get() = LatLng(lat, lon)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "KoridorOrtalama/0.1 (github.com/XantilDev/koridor-ortalama)")
        c.setRequestProperty("Accept-Language", "tr")
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IOException("HTTP $code")
        return text
    }

    private fun province(addr: JSONObject?): String? {
        if (addr == null) return null
        for (k in listOf("province", "city", "town", "state")) {
            val v = addr.optString(k)
            if (v.isNotBlank() && !v.contains("Bölgesi")) return v
        }
        return null
    }

    suspend fun search(q: String, near: LatLng?): List<Place> = withContext(Dispatchers.IO) {
        var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=8&countrycodes=tr&q=" +
            URLEncoder.encode(q, "UTF-8")
        if (near != null) {
            url += "&viewbox=${near.lon - 1.5},${near.lat + 1.0},${near.lon + 1.5},${near.lat - 1.0}"
        }
        val a = JSONArray(get(url))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val full = o.optString("display_name")
            val parts = full.split(",").map { it.trim() }
            val name = o.optString("name").ifBlank { parts.firstOrNull() ?: full }
            Place(name, parts.drop(1).take(4).joinToString(", "), o.getDouble("lat"), o.getDouble("lon"), province(o.optJSONObject("address")))
        }
    }

    suspend fun provinceAt(p: LatLng): String? = withContext(Dispatchers.IO) {
        val o = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=10&lat=${p.lat}&lon=${p.lon}"))
        province(o.optJSONObject("address"))
    }

    /** Takip açıksa son konum, değilse telefonun bildiği son konum. */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context): LatLng? {
        val st = Engine.state.value
        if (st.mode == "gps") st.pos?.let { return it }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { LatLng(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    fun saveSlot(ctx: Context, slot: String, p: Place) {
        val o = JSONObject().put("n", p.name).put("d", p.detail).put("a", p.lat).put("b", p.lon).put("p", p.province ?: "")
        ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putString(slot, o.toString()).apply()
    }

    fun loadSlot(ctx: Context, slot: String): Place? = try {
        val s = ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).getString(slot, null)
        if (s == null) null else {
            val o = JSONObject(s)
            Place(o.getString("n"), o.optString("d"), o.getDouble("a"), o.getDouble("b"), o.optString("p").ifBlank { null })
        }
    } catch (_: Exception) {
        null
    }
}

/** Konumdan trafik.gov.tr'nin istediği il/ilçe kimliğini bulur. */
object DistrictFinder {
    private var cities: List<TrafikApi.City>? = null
    private val districts = HashMap<Int, List<TrafikApi.District>>()

    private fun norm(s: String) = s.lowercase(java.util.Locale("tr", "TR"))
        .replace(" ili", "").replace(" province", "").trim()

    suspend fun nearest(p: LatLng, provinceHint: String?): TrafikApi.District {
        val cs = cities ?: TrafikApi.cities().also { cities = it }
        val prov = provinceHint ?: runCatching { Places.provinceAt(p) }.getOrNull()
            ?: throw IOException("Konumun hangi ilde olduğu bulunamadı")
        val city = cs.find { norm(it.name) == norm(prov) }
            ?: cs.find { norm(prov).contains(norm(it.name)) }
            ?: throw IOException("“$prov” ili listede bulunamadı")
        val ds = districts[city.id] ?: TrafikApi.districts(city.id).also { districts[city.id] = it }
        val d = ds.minByOrNull { Geo.dist(p, LatLng(it.lat, it.lon)) } ?: throw IOException("İlçe bulunamadı")
        // Rota tam noktadan hesaplansın diye ilçe merkezi yerine gerçek koordinat kullanılır.
        return d.copy(lat = p.lat, lon = p.lon)
    }
}
