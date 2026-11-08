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

    /** Google anahtarı varsa Google ile (firmaları bulur), yoksa ya da hata olursa OpenStreetMap ile. */
    suspend fun search(q: String, near: LatLng?): List<Place> {
        if (GoogleApi.enabled) {
            try {
                return GoogleApi.search(q, near).also { lastSearchGoogle = true }
            } catch (_: Exception) {
            }
        }
        lastSearchGoogle = false
        return searchOsm(q, near)
    }

    /** Son arama Google'dan mı geldi (sonuç listesinde "Google" ibaresi için) */
    var lastSearchGoogle = false

    private suspend fun searchOsm(q: String, near: LatLng?): List<Place> = withContext(Dispatchers.IO) {
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

    /** Haritadan seçilen noktanın adı (OSM; yoksa koordinat). */
    suspend fun reverse(p: LatLng): Place = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=${p.lat}&lon=${p.lon}"))
            val a = o.optJSONObject("address")
            val full = o.optString("display_name")
            val parts = full.split(",").map { it.trim() }
            val name = o.optString("name").ifBlank {
                listOfNotNull(a?.optString("road")?.takeIf { it.isNotBlank() }, a?.optString("house_number")?.takeIf { it.isNotBlank() }).joinToString(" ")
            }.ifBlank { parts.firstOrNull() ?: "Haritadan seçilen nokta" }
            Place(name, parts.drop(1).take(4).joinToString(", "), p.lat, p.lon, province(a))
        } catch (_: Exception) {
            Place("Haritadan seçilen nokta", String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon), p.lat, p.lon, null)
        }
    }

    /** Şarj istasyonları: rota varsa yol boyunca, yoksa yakında (Google; yoksa OpenStreetMap). Yakından uzağa. */
    suspend fun charging(near: LatLng, along: List<LatLng>?): List<Place> {
        val list = try {
            if (GoogleApi.enabled) GoogleApi.search("elektrikli araç şarj istasyonu", near, "electric_vehicle_charging_station", along)
            else chargingOsm(near)
        } catch (_: Exception) {
            chargingOsm(near)
        }
        return list.sortedBy { Geo.dist(near, it.ll) }
    }

    private suspend fun chargingOsm(near: LatLng): List<Place> = withContext(Dispatchers.IO) {
        val q = "[out:json][timeout:20];node[amenity=charging_station](around:20000,${near.lat},${near.lon});out 30;"
        val o = JSONObject(get("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(q, "UTF-8")))
        val a = o.optJSONArray("elements") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONObject(i)
            val t = e.optJSONObject("tags")
            val name = t?.optString("name")?.ifBlank { null } ?: t?.optString("operator")?.ifBlank { null } ?: "Şarj istasyonu"
            Place(name, listOfNotNull(t?.optString("operator")?.ifBlank { null }, t?.optString("addr:street")?.ifBlank { null }).joinToString(" · "),
                e.getDouble("lat"), e.getDouble("lon"), null)
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
        if (st.mode == "gps" || st.mode == "bekle") st.pos?.let { return it }
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

    /** Konumun ne kadar güncel olduğu (rota ekranında gösterilir). */
    var lastFixNote: String? = null

    /**
     * Taze konum: takip açıksa son GPS noktası; değilse GPS ve ağ konumu aynı anda istenir,
     * ilk gelen kullanılır (en fazla 10 sn). Gelmezse son 24 saatteki kayıtlı konum, uyarıyla.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshLocation(ctx: Context): LatLng? {
        lastFixNote = null
        val st = Engine.state.value
        if (st.mode == "gps" || st.mode == "bekle") st.pos?.let { return it }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == "fused"
        }
        val loc = kotlinx.coroutines.withTimeoutOrNull(10000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                val listeners = ArrayList<android.location.LocationListener>()
                fun done(l: android.location.Location?) {
                    listeners.forEach { runCatching { lm.removeUpdates(it) } }
                    if (cont.isActive) cont.resumeWith(Result.success(l))
                }
                for (prov in providers) {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) {
                            done(l)
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    try {
                        lm.requestLocationUpdates(prov, 0L, 0f, listener, android.os.Looper.getMainLooper())
                        listeners.add(listener)
                    } catch (_: Exception) {
                    }
                }
                if (listeners.isEmpty()) done(null)
                cont.invokeOnCancellation { listeners.forEach { runCatching { lm.removeUpdates(it) } } }
            }
        }
        if (loc != null) return LatLng(loc.latitude, loc.longitude)
        val last = try {
            lm.getProviders(false).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 24 * 3600 * 1000L }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        } ?: return null
        val mins = ((System.currentTimeMillis() - last.time) / 60000).toInt()
        if (mins >= 2) lastFixNote = "Yeni konum alınamadı; $mins dk önceki konumun kullanıldı. Yanlışsa GPS ile başlatıp tekrar dene."
        return LatLng(last.latitude, last.longitude)
    }

    fun saveSlot(ctx: Context, slot: String, p: Place) {
        val o = JSONObject().put("n", p.name).put("d", p.detail).put("a", p.lat).put("b", p.lon).put("p", p.province ?: "")
        ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putString(slot, o.toString()).apply()
    }

    /** Kullanıcının isim verip kaydettiği yerler (ör. "Çevrükler Makine"). */
    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Noktaya 200 m içindeki kayıtlı yerin adı (önce isimli yerler, sonra Ev / İş). */
    fun placeAt(p: LatLng): String? {
        val c = app ?: return null
        savedList(c).filter { Geo.dist(it.ll, p) < 200 }.minByOrNull { Geo.dist(it.ll, p) }?.let { return it.name }
        loadSlot(c, "ev")?.takeIf { Geo.dist(it.ll, p) < 200 }?.let { return "Ev" }
        loadSlot(c, "is")?.takeIf { Geo.dist(it.ll, p) < 200 }?.let { return "İş" }
        return null
    }

    fun savedList(ctx: Context): List<Place> = try {
        val a = org.json.JSONArray(ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).getString("yerler", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("n"), o.optString("d"), o.getDouble("a"), o.getDouble("b"), o.optString("p").ifBlank { null })
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun savePlace(ctx: Context, name: String, p: Place) {
        val list = savedList(ctx).filter { !it.name.equals(name.trim(), ignoreCase = true) } + p.copy(name = name.trim())
        val a = org.json.JSONArray()
        list.forEach { x -> a.put(JSONObject().put("n", x.name).put("d", x.detail).put("a", x.lat).put("b", x.lon).put("p", x.province ?: "")) }
        ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putString("yerler", a.toString()).apply()
    }

    fun removePlace(ctx: Context, name: String) {
        val list = savedList(ctx).filter { it.name != name }
        val a = org.json.JSONArray()
        list.forEach { x -> a.put(JSONObject().put("n", x.name).put("d", x.detail).put("a", x.lat).put("b", x.lon).put("p", x.province ?: "")) }
        ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putString("yerler", a.toString()).apply()
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
        return d.copy(name = "${d.name}, ${city.name}", lat = p.lat, lon = p.lon)
    }
}
