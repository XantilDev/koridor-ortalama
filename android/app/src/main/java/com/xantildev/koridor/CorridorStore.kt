package com.xantildev.koridor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bütün koridor kaynaklarını birleştirir:
 *  - trafik.gov.tr rota sorgularından gelenler (limitli, kalıcı önbellek)
 *  - kullanıcının kendi işaretledikleri
 *  - EGM haritasındaki İstanbul/Kayseri OHTS çiftleri
 * Giriş ve çıkışı 500 m içinde çakışanlar tek koridor sayılır.
 */
object CorridorStore {
    private lateinit var prefs: SharedPreferences
    val version = MutableStateFlow(0)

    private val user = ArrayList<Corridor>()
    private val route = LinkedHashMap<String, Corridor>()
    private val ov = HashMap<String, Int>()
    private var cache: List<Corridor>? = null
    private val egm: List<Corridor> by lazy { EgmData.list() }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("koridorlar", Context.MODE_PRIVATE)
        user.addAll(fromJson(prefs.getString("user", "[]") ?: "[]"))
        fromJson(prefs.getString("route", "[]") ?: "[]").forEach { route[it.id] = it }
        try {
            val o = JSONObject(prefs.getString("ov", "{}") ?: "{}")
            o.keys().forEach { k -> ov[k] = o.getInt(k) }
        } catch (_: Exception) {
        }
    }

    val routeCount: Int get() = route.size
    val egmCount: Int get() = egm.size
    val userCount: Int get() = user.size

    fun isUser(id: String): Boolean = user.any { it.id == id }

    /**
     * Etkin limit: aktif araç tipi için girilmiş levha değeri varsa o; yoksa otomobil limiti
     * (servis/elle girilen) aracın tipine göre uyarlanır.
     */
    fun withOverride(c: Corridor): Corridor {
        val t = VehicleStore.type
        ov[c.key + "|" + t.code]?.let { return c.copy(limit = it) }
        val car = ov[c.key] ?: c.limit
        return c.copy(limit = t.adjust(car))
    }

    fun vehicleChanged() = changed()

    fun all(): List<Corridor> {
        cache?.let { return it }
        val out = ArrayList<Corridor>()
        fun add(c: Corridor) {
            val i = out.indexOfFirst { Geo.dist(it.from, c.from) < 500 && Geo.dist(it.to, c.to) < 500 }
            if (i < 0) {
                out.add(c)
            } else {
                val h = out[i]
                out[i] = h.copy(
                    source = if (h.source.contains(c.source)) h.source else h.source + "+" + c.source,
                    limit = h.limit ?: c.limit,
                    path = if (h.path.size >= 2) h.path else c.path
                )
            }
        }
        route.values.forEach { add(it) }
        user.forEach { add(it) }
        egm.forEach { add(it) }
        val res = out.map { withOverride(it) }
        cache = res
        return res
    }

    private fun changed() {
        cache = null
        version.value = version.value + 1
    }

    fun addUser(c: Corridor) {
        user.add(c)
        prefs.edit().putString("user", toJson(user)).apply()
        changed()
    }

    fun removeUser(id: String) {
        user.removeAll { it.id == id }
        prefs.edit().putString("user", toJson(user)).apply()
        changed()
    }

    /** Kullanıcının levhada gördüğü limit; ticari araçtaysa o tipe özel saklanır. */
    fun setLimit(key: String, v: Int) {
        val t = VehicleStore.type
        ov[if (t.commercial) key + "|" + t.code else key] = v
        val o = JSONObject()
        ov.forEach { (k, value) -> o.put(k, value) }
        prefs.edit().putString("ov", o.toString()).apply()
        changed()
    }

    fun clearRoute() {
        route.clear()
        prefs.edit().putString("route", "[]").apply()
        changed()
    }

    fun addRoute(list: List<Corridor>) {
        list.forEach { route[it.id] = it }
        prefs.edit().putString("route", toJson(route.values)).apply()
        changed()
    }

    private fun toJson(list: Collection<Corridor>): String {
        val a = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("n", c.name)
            o.put("a", c.from.lat)
            o.put("b", c.from.lon)
            o.put("c", c.to.lat)
            o.put("d", c.to.lon)
            o.put("l", c.limit ?: -1)
            o.put("len", c.lengthM)
            o.put("s", c.source)
            val p = JSONArray()
            c.path.forEach { pt -> p.put(JSONArray().put(pt.lat).put(pt.lon)) }
            o.put("p", p)
            a.put(o)
        }
        return a.toString()
    }

    private fun fromJson(s: String): List<Corridor> = try {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val p = o.optJSONArray("p")
            val path = if (p == null) emptyList() else (0 until p.length()).map { j ->
                val q = p.getJSONArray(j)
                LatLng(q.getDouble(0), q.getDouble(1))
            }
            Corridor(
                o.getString("id"), o.getString("n"),
                LatLng(o.getDouble("a"), o.getDouble("b")),
                LatLng(o.getDouble("c"), o.getDouble("d")),
                o.optInt("l", -1).takeIf { it > 0 },
                o.getDouble("len"), o.getString("s"), path
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
