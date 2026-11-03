package com.xantildev.koridor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Google Maps Platform (anahtar varsa). Anahtar derlemede MAPS_API_KEY olarak verilir; yoksa
 * uygulama eskisi gibi OpenStreetMap (harita), Nominatim (arama) ve OSRM (rota) kullanır.
 * Maliyet: Text Search (Pro) ayda 5.000, Compute Routes (Essentials) ayda 10.000 çağrıya kadar ücretsiz;
 * Android'de harita gösterimi ücretsiz. Yalnız gereken alanlar istenir (FieldMask), pahalı alanlar istenmez.
 */
object GoogleApi {
    val key: String = BuildConfig.MAPS_KEY
    val enabled: Boolean get() = key.isNotBlank()

    private var pkg = ""
    private var cert = ""

    fun init(ctx: Context) {
        pkg = ctx.packageName
        cert = runCatching { certSha1(ctx) }.getOrDefault("")
    }

    /** Android ile kısıtlanmış anahtar REST çağrılarında bu iki başlığı ister. */
    @Suppress("DEPRECATION")
    private fun certSha1(ctx: Context): String {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return ""
        return MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    private fun post(url: String, fieldMask: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Goog-Api-Key", key)
        c.setRequestProperty("X-Goog-FieldMask", fieldMask)
        c.setRequestProperty("X-Android-Package", pkg)
        if (cert.isNotBlank()) c.setRequestProperty("X-Android-Cert", cert)
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val m = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
            throw IOException("Google: " + (m?.ifBlank { null } ?: "HTTP $code"))
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** Firma/adres arama (Places API New · Text Search). Yakındaki sonuçlar öne çıkar. */
    suspend fun search(q: String, near: LatLng?): List<Places.Place> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("textQuery", q).put("languageCode", "tr").put("regionCode", "TR").put("pageSize", 8)
        if (near != null) {
            body.put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", near.lat).put("longitude", near.lon)).put("radius", 50000.0)))
        }
        val o = post(
            "https://places.googleapis.com/v1/places:searchText",
            "places.displayName,places.formattedAddress,places.location,places.addressComponents",
            body
        )
        val a = o.optJSONArray("places") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val p = a.getJSONObject(i)
            val loc = p.optJSONObject("location") ?: return@mapNotNull null
            val name = p.optJSONObject("displayName")?.optString("text")?.ifBlank { null } ?: return@mapNotNull null
            var prov: String? = null
            val comps = p.optJSONArray("addressComponents") ?: JSONArray()
            for (j in 0 until comps.length()) {
                val cmp = comps.getJSONObject(j)
                val types = cmp.optJSONArray("types")?.toString() ?: ""
                if (types.contains("administrative_area_level_1")) prov = cmp.optString("longText").removeSuffix(" İli").ifBlank { null }
            }
            Places.Place(name, p.optString("formattedAddress").removeSuffix(", Türkiye"), loc.getDouble("latitude"), loc.getDouble("longitude"), prov)
        }
    }

    /** Araç rotası (Routes API · Essentials): çizgi, metre, saniye. */
    suspend fun route(a: LatLng, b: LatLng): Triple<List<LatLng>, Double, Double> = routes(a, b, false).first()

    /** Alternatifli rota (en fazla 3). computeAlternativeRoutes ücret sınıfını değiştirmez (tek istek, Essentials). */
    suspend fun routes(a: LatLng, b: LatLng, alternatives: Boolean): List<Triple<List<LatLng>, Double, Double>> = withContext(Dispatchers.IO) {
        fun wp(p: LatLng) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
        val body = JSONObject().put("origin", wp(a)).put("destination", wp(b))
            .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_UNAWARE").put("languageCode", "tr").put("units", "METRIC")
            .put("computeAlternativeRoutes", alternatives)
        val o = post("https://routes.googleapis.com/directions/v2:computeRoutes", "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline", body)
        val arr = o.optJSONArray("routes") ?: JSONArray()
        val out = (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            val enc = r.optJSONObject("polyline")?.optString("encodedPolyline")?.ifBlank { null } ?: return@mapNotNull null
            val line = decode(enc)
            Triple(line, r.optDouble("distanceMeters", Geo.pathLength(line)), r.optString("duration").removeSuffix("s").toDoubleOrNull() ?: 0.0)
        }.take(3)
        if (out.isEmpty()) throw IOException("Google: rota bulunamadı")
        out
    }

    /** Google'ın kodlanmış çizgi biçimini çözer. */
    fun decode(s: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0; var lat = 0; var lng = 0
        while (i < s.length) {
            var res = 0; var shift = 0; var b: Int
            do { b = s[i++].code - 63; res = res or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            res = 0; shift = 0
            do { b = s[i++].code - 63; res = res or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lng += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }
}
