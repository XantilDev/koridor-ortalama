package com.xantildev.koridor

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Araç tipleri ve Karayolları Trafik Yönetmeliği md. 100'deki genel hız sınırları (km/s):
 * yerleşim yeri içi / yerleşim yeri dışı çift yönlü / bölünmüş yol / otoyol.
 */
enum class VehicleType(val code: String, val label: String, val urban: Int, val twoWay: Int, val divided: Int, val motorway: Int) {
    OTO("oto", "Otomobil", 50, 90, 110, 140),
    KAMYONET("kamyonet", "Ticari · Kamyonet", 50, 80, 85, 95),
    PANELVAN("panelvan", "Ticari · Panelvan", 50, 85, 100, 110);

    val commercial: Boolean get() = this != OTO

    /**
     * Koridor limiti otomobile göre yayınlanır. Ticari araçta yol türü bu limitten tahmin edilir
     * ve aracın o yol türündeki genel sınırıyla küçük olan alınır.
     */
    fun adjust(carLimit: Int?): Int? {
        if (carLimit == null || !commercial) return carLimit
        val classMax = when {
            carLimit <= 50 -> urban
            carLimit <= 90 -> twoWay
            carLimit <= 110 -> divided
            else -> motorway
        }
        return minOf(carLimit, classMax)
    }

    companion object {
        fun of(code: String?): VehicleType = values().firstOrNull { it.code == code } ?: OTO
    }
}

data class Vehicle(
    val id: String,
    val name: String,
    val plate: String,
    val type: VehicleType,
    val odoKm: Double
)

object VehicleStore {
    private lateinit var prefs: SharedPreferences
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
        private set
    var activeId by mutableStateOf("")
        private set

    val active: Vehicle get() = vehicles.firstOrNull { it.id == activeId } ?: vehicles.first()
    val type: VehicleType get() = if (vehicles.isEmpty()) VehicleType.OTO else active.type

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("araclar", Context.MODE_PRIVATE)
        val list = try {
            val a = JSONArray(prefs.getString("list", "[]") ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Vehicle(o.getString("id"), o.getString("n"), o.optString("p"), VehicleType.of(o.optString("t")), o.optDouble("km", 0.0))
            }
        } catch (_: Exception) {
            emptyList()
        }
        vehicles = list.ifEmpty { listOf(Vehicle("v1", "Aracım", "", VehicleType.OTO, 0.0)) }
        activeId = prefs.getString("active", null)?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.first().id
    }

    private fun save() {
        val a = JSONArray()
        vehicles.forEach { v ->
            a.put(JSONObject().put("id", v.id).put("n", v.name).put("p", v.plate).put("t", v.type.code).put("km", v.odoKm))
        }
        prefs.edit().putString("list", a.toString()).putString("active", activeId).apply()
    }

    fun select(id: String) {
        activeId = id
        save()
        CorridorStore.vehicleChanged()
    }

    fun upsert(v: Vehicle) {
        vehicles = if (vehicles.any { it.id == v.id }) vehicles.map { if (it.id == v.id) v else it } else vehicles + v
        save()
        CorridorStore.vehicleChanged()
    }

    fun remove(id: String) {
        if (vehicles.size <= 1) return
        vehicles = vehicles.filter { it.id != id }
        if (activeId == id) activeId = vehicles.first().id
        save()
        CorridorStore.vehicleChanged()
    }

    fun addKm(id: String, km: Double) {
        vehicles = vehicles.map { if (it.id == id) it.copy(odoKm = it.odoKm + km) else it }
        save()
    }
}
