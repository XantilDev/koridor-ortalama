package com.xantildev.koridor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bir yolculukta geçilen EDS koridoru. */
data class CorridorPass(
    val name: String,
    val limit: Int?,
    val avg: Double,
    val durationS: Double,
    val stopS: Double,
    val lengthM: Double
)

data class TripRecord(
    val id: String,
    val start: Long,
    val end: Long,
    val vehicleName: String,
    val plate: String,
    val vehicleType: String,
    val distM: Double,
    val timeS: Double,
    val moveS: Double,
    val stopInS: Double,
    val stopOutS: Double,
    val sample: Boolean,
    val target: String?,
    val passes: List<CorridorPass>,
    val track: List<LatLng>
) {
    val avg: Double? get() = if (timeS > 10) distM / timeS * 3.6 else null
    val moveAvg: Double? get() = if (moveS > 10) distM / moveS * 3.6 else null
}

/** Yolculuklar telefonda dosya olarak saklanır (uygulamanın kendi klasörü). */
object TripStore {
    private lateinit var dir: File
    var trips by mutableStateOf<List<TripRecord>>(emptyList())
        private set

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "yolculuklar").apply { mkdirs() }
        trips = (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.start }
    }

    fun save(t: TripRecord) {
        runCatching { File(dir, t.id + ".json").writeText(toJson(t).toString()) }
        trips = (listOf(t) + trips.filter { it.id != t.id }).sortedByDescending { it.start }
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
        trips = trips.filter { it.id != id }
    }

    private fun toJson(t: TripRecord): JSONObject {
        val o = JSONObject()
            .put("id", t.id).put("s", t.start).put("e", t.end)
            .put("vn", t.vehicleName).put("vp", t.plate).put("vt", t.vehicleType)
            .put("d", t.distM).put("t", t.timeS).put("m", t.moveS)
            .put("si", t.stopInS).put("so", t.stopOutS).put("x", t.sample)
            .put("hd", t.target ?: "")
        val ps = JSONArray()
        t.passes.forEach { p ->
            ps.put(
                JSONObject().put("n", p.name).put("l", p.limit ?: -1).put("a", p.avg)
                    .put("t", p.durationS).put("s", p.stopS).put("len", p.lengthM)
            )
        }
        o.put("ps", ps)
        val tr = JSONArray()
        t.track.forEach { tr.put(JSONArray().put(it.lat).put(it.lon)) }
        o.put("tr", tr)
        return o
    }

    private fun fromJson(o: JSONObject): TripRecord {
        val ps = o.optJSONArray("ps") ?: JSONArray()
        val tr = o.optJSONArray("tr") ?: JSONArray()
        return TripRecord(
            o.getString("id"), o.getLong("s"), o.getLong("e"),
            o.optString("vn"), o.optString("vp"), o.optString("vt"),
            o.getDouble("d"), o.getDouble("t"), o.optDouble("m", 0.0),
            o.optDouble("si", 0.0), o.optDouble("so", 0.0), o.optBoolean("x"),
            o.optString("hd").ifBlank { null },
            (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                CorridorPass(
                    p.getString("n"), p.optInt("l", -1).takeIf { it > 0 }, p.getDouble("a"),
                    p.getDouble("t"), p.optDouble("s", 0.0), p.optDouble("len", 0.0)
                )
            },
            (0 until tr.length()).map { i ->
                val q = tr.getJSONArray(i)
                LatLng(q.getDouble(0), q.getDouble(1))
            }
        )
    }
}
