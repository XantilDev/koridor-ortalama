package com.xantildev.koridor

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
    /** Son rota başlangıcının nereden alındığı, ör. "GPS ±12 m" (önizlemede ve loglarda). */
    var lastFixSource: String? = null

    /** Son güvenilir GPS konumu (ağ konumunun saçma olup olmadığını anlamak için). */
    @Volatile var trustedFix: LocFix? = null
        private set
    fun noteGps(p: LatLng, acc: Float?, t: Long) {
        if (acc != null && acc > 100f) return
        val prev = trustedFix
        trustedFix = LocFix(p, acc, t, true)
        if (prev == null || t - prev.t > 60_000) app?.getSharedPreferences("rota", Context.MODE_PRIVATE)?.edit()
            ?.putString("guvenilir", "${p.lat},${p.lon},${acc ?: 0f},$t")?.apply()
    }
    private fun loadTrusted(c: Context) {
        if (trustedFix != null) return
        val s = c.getSharedPreferences("rota", Context.MODE_PRIVATE).getString("guvenilir", null) ?: return
        runCatching { val q = s.split(","); trustedFix = LocFix(LatLng(q[0].toDouble(), q[1].toDouble()), q[2].toFloat(), q[3].toLong(), true) }
    }

    /**
     * Taze konum: takip açıksa son GPS noktası; değilse GPS ve ağ konumu aynı anda istenir.
     * GPS (±100 m içinde) gelince hemen kullanılır. Ağ konumu yalnız doğruluğu ±1,5 km içindeyse ve
     * son güvenilir GPS konumuyla çelişmiyorsa yedek olarak alınır; IP'den tahmin edilen kaba konumlar
     * (ör. operatör yüzünden "İstanbul") böylece elenir. Hiçbiri yoksa son 24 saatteki güvenilir konum, uyarıyla.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshLocation(ctx: Context): LatLng? {
        lastFixNote = null
        lastFixSource = null
        loadTrusted(ctx)
        val st = Engine.state.value
        if (st.mode == "gps" || st.mode == "bekle") st.pos?.let { lastFixSource = "sürüş konumu (${st.gpsText})"; return it }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == "fused"
        }
        val got = ArrayList<LocFix>()
        val rejected = ArrayList<String>()
        val started = System.currentTimeMillis()
        val chosen: LocFix? = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<LocFix?> { cont ->
                val listeners = ArrayList<android.location.LocationListener>()
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                var finished = false
                fun done(l: LocFix?) {
                    if (finished) return
                    finished = true
                    listeners.forEach { runCatching { lm.removeUpdates(it) } }
                    if (cont.isActive) cont.resumeWith(Result.success(l))
                }
                // GPS gelmezse 6 sn sonra en iyi kabul edilebilir ağ konumu kullanılır
                main.postDelayed({ if (!finished) LocPick.best(got, trustedFix, System.currentTimeMillis())?.let { done(it) } }, 6_000)
                main.postDelayed({ if (!finished) done(LocPick.best(got, trustedFix, System.currentTimeMillis())) }, 11_500)
                for (prov in providers) {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) {
                            val f = LocFix(LatLng(l.latitude, l.longitude), if (l.hasAccuracy()) l.accuracy else null, System.currentTimeMillis(),
                                l.provider == LocationManager.GPS_PROVIDER)
                            val why = LocPick.reject(f, trustedFix, f.t)
                            if (why != null) { rejected.add("${if (f.gps) "GPS" else "ağ"}: $why"); return }
                            got.add(f)
                            if (f.gps) done(f)
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
        val secs = (System.currentTimeMillis() - started) / 1000
        if (chosen != null) {
            if (chosen.gps) noteGps(chosen.ll, chosen.acc, chosen.t)
            lastFixSource = "${if (chosen.gps) "GPS" else "ağ konumu"} ±${chosen.acc?.toInt() ?: "?"} m"
            if (!chosen.gps) lastFixNote = "GPS henüz gelmedi; başlangıç ağ konumundan (±${chosen.acc?.toInt() ?: "?"} m). GPS gelince rota gerekirse kendiliğinden düzeltilir."
            TLog.i("konum", "Rota başlangıcı: $lastFixSource, $secs sn", org.json.JSONObject()
                .put("adaylar", got.size).put("elenen", org.json.JSONArray(rejected.take(6))))
            return chosen.ll
        }
        // Son çare: telefonun bildiği son konumlardan güvenilir olanı (24 saat içinde)
        val now = System.currentTimeMillis()
        val last = try {
            lm.getProviders(false).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < 24 * 3600 * 1000L }
                .map { LocFix(LatLng(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null, it.time, it.provider == LocationManager.GPS_PROVIDER) }
                .filter { LocPick.reject(it, trustedFix, now) == null }
        } catch (_: Exception) {
            emptyList()
        }
        val fb = LocPick.best(last, trustedFix, now) ?: trustedFix?.takeIf { now - it.t < 24 * 3600 * 1000L }
        if (fb == null) {
            TLog.w("konum", "Rota başlangıcı için konum alınamadı ($secs sn)", org.json.JSONObject().put("elenen", org.json.JSONArray(rejected.take(6))))
            return null
        }
        val mins = ((now - fb.t) / 60000).toInt()
        lastFixSource = "$mins dk önceki konum ±${fb.acc?.toInt() ?: "?"} m"
        if (mins >= 2) lastFixNote = "Yeni konum alınamadı; $mins dk önceki konumun kullanıldı. GPS gelince rota gerekirse kendiliğinden düzeltilir."
        TLog.w("konum", "Rota başlangıcı eski konumdan: $lastFixSource", org.json.JSONObject().put("elenen", org.json.JSONArray(rejected.take(6))))
        return fb.ll
    }

    fun saveSlot(ctx: Context, slot: String, p: Place) {
        val o = JSONObject().put("n", p.name).put("d", p.detail).put("a", p.lat).put("b", p.lon).put("p", p.province ?: "")
        ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putString(slot, o.toString()).apply()
    }

    /** Kullanıcının isim verip kaydettiği yerler (ör. "Çevrükler Makine"). */
    private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext; DistrictFinder.init(ctx) }

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

/**
 * Konumdan trafik.gov.tr'nin istediği ilçe kimliğini bulur — internete sormadan.
 * EGM'nin 81 il / 973 ilçe listesi (kimlik + ilçe merkezi) telefonda durur: önce kayıtlı kopya,
 * yoksa APK'nin içindeki kopya, o da yoksa Supabase'den, o da olmazsa doğrudan EGM'den indirilir.
 * Supabase'deki liste haftada bir kontrol edilir. Bulunulan ilçe = en yakın ilçe merkezi.
 */
object DistrictFinder {
    data class Ilce(val id: Int, val ilId: Int, val il: String, val ad: String, val lat: Double, val lon: Double)

    @Volatile private var list: List<Ilce> = emptyList()
    var source = "—"
        private set
    private var app: Context? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    private const val SB_URL = "https://advcgkmlfrnzwqbtplba.supabase.co"
    private const val SB_KEY = "sb_publishable_wur9wh2sFeQ7Tsb2r9qdvw_IQFQcxx6"

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val f = java.io.File(ctx.filesDir, "egm_ilce.json")
        val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull()
        parse(text)?.let { list = it; source = "telefonda kayıtlı" }
        if (list.isEmpty()) runCatching { ctx.assets.open("egm_ilce.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { parse(it) }?.let { list = it; source = "uygulama içi" }
    }

    /** Supabase biçimi: {"surum":…, "ilceler":[[id, il_id, il, ad, lat, lon], …]} */
    fun parse(text: String?): List<Ilce>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val a = JSONObject(text).getJSONArray("ilceler")
            (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                Ilce(r.getInt(0), r.getInt(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5))
            }
        }.getOrNull()?.takeIf { it.size > 500 }
    }

    /** Listeyi gerekirse indirir (ilk kez ya da 7 günde bir). */
    private suspend fun ensure() {
        val c = app
        val prefs = c?.getSharedPreferences("egm", Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - (prefs?.getLong("indirme", 0L) ?: 0L)
        if (list.isNotEmpty() && age < 7 * 24 * 3600_000L) return
        lock.withLock {
            if (list.isNotEmpty() && System.currentTimeMillis() - (prefs?.getLong("indirme", 0L) ?: 0L) < 7 * 24 * 3600_000L) return
            val fresh = withContext(Dispatchers.IO) {
                runCatching { supabase() }.onFailure { TLog.w("ilce", "İlçe listesi Supabase'den alınamadı: ${it.message}") }.getOrNull()
                    ?: if (list.isEmpty()) runCatching { fromEgm() }.onFailure { TLog.e("ilce", "İlçe listesi EGM'den de alınamadı: ${it.message}") }.getOrNull() else null
            }
            if (fresh != null) {
                list = fresh.second
                source = fresh.first
                if (c != null) runCatching { java.io.File(c.filesDir, "egm_ilce.json").writeText(fresh.third) }
                prefs?.edit()?.putLong("indirme", System.currentTimeMillis())?.apply()
                TLog.i("ilce", "İlçe listesi güncellendi: ${list.size} ilçe (${fresh.first})")
            } else if (list.isNotEmpty()) {
                // Eldeki liste kullanılmaya devam eder; bir gün sonra tekrar denenir
                prefs?.edit()?.putLong("indirme", System.currentTimeMillis() - 6 * 24 * 3600_000L)?.apply()
            }
        }
    }

    private fun supabase(): Triple<String, List<Ilce>, String> {
        val c = URL("$SB_URL/rest/v1/rpc/egm_ilceler").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("apikey", SB_KEY)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{}".toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IOException("HTTP $code")
        val l = parse(text) ?: throw IOException("liste boş ya da bozuk")
        return Triple("Supabase", l, text)
    }

    /** Son çare: 81 il için EGM'ye tek tek sorulur (ilk kurulumda Supabase'e ulaşılamazsa). */
    private suspend fun fromEgm(): Triple<String, List<Ilce>, String> {
        val out = ArrayList<Ilce>()
        val arr = JSONArray()
        for (city in TrafikApi.cities()) {
            for (d in TrafikApi.districts(city.id)) {
                out.add(Ilce(d.id, city.id, city.name, d.name, d.lat, d.lon))
                arr.put(JSONArray().put(d.id).put(city.id).put(city.name).put(d.name).put(d.lat).put(d.lon))
            }
        }
        if (out.size < 500) throw IOException("EGM listesi eksik (${out.size})")
        return Triple("EGM", out, JSONObject().put("surum", 0).put("ilceler", arr).toString())
    }

    private fun norm(s: String) = s.lowercase(java.util.Locale("tr", "TR"))
        .replace(" ili", "").replace(" province", "").trim()

    /** İlçe merkezinin koordinatı (EGM sitesi ilçe seçilince bunu gönderir). */
    fun center(id: Int): LatLng? = list.firstOrNull { it.id == id }?.let { LatLng(it.lat, it.lon) }

    /** En yakın ilçe merkezi; il biliniyorsa (hedefin aramadan gelen ili) önce o ilin ilçeleri arasından. Saf mantık, testli. */
    fun pick(all: List<Ilce>, p: LatLng, provinceHint: String?): Ilce? {
        val inProv = provinceHint?.let { h -> all.filter { norm(it.il) == norm(h) }.ifEmpty { all.filter { norm(h).contains(norm(it.il)) } } }
        val pool = inProv?.takeIf { it.isNotEmpty() } ?: all
        return pool.minByOrNull { Geo.dist(p, LatLng(it.lat, it.lon)) }
    }

    suspend fun nearest(p: LatLng, provinceHint: String?): TrafikApi.District {
        ensure()
        val all = list
        if (all.isEmpty()) throw IOException("ilçe listesi yok (Supabase ve EGM'ye ulaşılamadı)")
        val d = pick(all, p, provinceHint) ?: throw IOException("ilçe bulunamadı")
        // Rota tam noktadan hesaplansın diye ilçe merkezi yerine gerçek koordinat kullanılır.
        return TrafikApi.District(d.id, "${d.ad}, ${d.il}", p.lat, p.lon)
    }
}

/** Bir konum ölçümü: GPS mi, ağ mı; doğruluk (m) ve zaman. */
data class LocFix(val ll: LatLng, val acc: Float?, val t: Long, val gps: Boolean)

/** Rota başlangıcı için konum seçimi (saf mantık; birim testleri var). */
object LocPick {
    const val MAX_NET_ACC = 1500f
    const val MAX_GPS_ACC = 100f

    /** Kabul edilmeyecekse nedeni. */
    fun reject(f: LocFix, trusted: LocFix?, now: Long): String? {
        val a = f.acc
        if (f.gps) { if (a != null && a > MAX_GPS_ACC) return "doğruluk ±${a.toInt()} m"; }
        else {
            if (a == null) return "doğruluğu bilinmiyor"
            if (a > MAX_NET_ACC) return "kaba konum ±${a.toInt()} m"
        }
        // Son güvenilir GPS konumundan, aradaki sürede araçla gidilemeyecek kadar uzaksa (150 km/s + 5 km) saçma
        if (!f.gps && trusted != null && now - trusted.t < 6 * 3600_000L) {
            val hours = (now - trusted.t) / 3600_000.0
            val maxM = 5_000 + 150_000 * hours
            val d = Geo.dist(trusted.ll, f.ll)
            if (d > maxM) return "son GPS konumuna ${(d / 1000).toInt()} km uzak"
        }
        return null
    }

    /** En iyisi: önce GPS, sonra en doğru ağ konumu. */
    fun best(list: List<LocFix>, trusted: LocFix?, now: Long): LocFix? =
        list.filter { reject(it, trusted, now) == null }
            .sortedWith(compareByDescending<LocFix> { it.gps }.thenBy { it.acc ?: Float.MAX_VALUE }.thenByDescending { it.t })
            .firstOrNull()
}
