package com.xantildev.koridor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Yol tarifi (dönüş dönüş): rota servisi (Google Routes ya da OSRM) adımları verir;
 * sürüşte sıradaki manevra, kalan mesafe ve sesli yönlendirme.
 */
object Directions {
    /** Manevra kodu: left, right, slight_left, slight_right, sharp_left, sharp_right, uturn, straight, roundabout, merge, ramp_left, ramp_right, fork_left, fork_right, arrive */
    class Step(val at: LatLng, val code: String, val text: String)

    class Next(val step: Step, val distM: Double, val after: Step?, val afterGapM: Double?)

    private val byLine = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<List<LatLng>, List<Step>>())
    private val alongCache = java.util.IdentityHashMap<List<LatLng>, DoubleArray>()
    private val spoken = HashSet<String>()

    var next by mutableStateOf<Next?>(null)
        private set

    fun store(line: List<LatLng>, steps: List<Step>) {
        if (byLine.size > 12) byLine.clear()
        byLine[line] = steps
    }

    fun has(line: List<LatLng>) = byLine[line]?.isNotEmpty() == true

    fun onFix(p: LatLng, kmh: Int?, inf: RouteSession.Info) {
        val steps = byLine[inf.line]
        if (steps.isNullOrEmpty()) { next = null; return }
        val al = alongCache.getOrPut(inf.line) { DoubleArray(steps.size) { inf.project(steps[it].at).first } }
        if (alongCache.size > 12) alongCache.clear()
        val (me, off) = inf.project(p)
        if (off > 250) { next = null; return }   // rotadan çıktık: yeni rota hesaplanır
        val i = (steps.indices).firstOrNull { al[it] > me + 8 } ?: run { next = null; return }
        val d = al[i] - me
        val after = steps.getOrNull(i + 1)
        val gap = if (after != null) al[i + 1] - al[i] else null
        next = Next(steps[i], d, after, gap)
        // Sesli yönlendirme: hıza göre uzaktan bir kez, yaklaşınca bir kez, dönüşte bir kez
        val st = steps[i]
        if (st.code == "straight") return
        val key = "${System.identityHashCode(inf.line)}/$i"
        val fast = (kmh ?: 0) >= 80
        val far = if (fast) 1500.0 else 500.0
        val near = if (fast) 400.0 else 150.0
        fun once(tag: String, text: String) {
            if (spoken.add("$key/$tag")) Engine.say(text, "yon$tag", 3000, corridor = true)
        }
        when {
            d <= 40 -> once("now", st.text)
            d <= near -> once("near", "${say(d)} sonra ${st.text.replaceFirstChar { it.lowercase() }}" +
                if (after != null && gap != null && gap < 150 && after.code != "straight") ", ardından ${after.text.replaceFirstChar { it.lowercase() }}" else "")
            d <= far && d > near * 1.5 -> once("far", "${say(d)} sonra ${st.text.replaceFirstChar { it.lowercase() }}")
        }
        if (spoken.size > 400) spoken.clear()
    }

    fun distText(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} km"
        m >= 1000 -> String.format(java.util.Locale("tr", "TR"), "%.1f km", m / 1000)
        m >= 100 -> "${((m / 50).roundToInt() * 50)} m"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
    }

    private fun say(m: Double): String = when {
        m >= 1000 -> String.format(java.util.Locale("tr", "TR"), "%.1f kilometre", m / 1000).replace(",0 ", " ")
        else -> "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} metre"
    }

    fun arrow(code: String): String = when (code) {
        "left" -> "↰"
        "right" -> "↱"
        "slight_left", "ramp_left", "fork_left" -> "↖"
        "slight_right", "ramp_right", "fork_right" -> "↗"
        "sharp_left" -> "⮢"
        "sharp_right" -> "⮣"
        "uturn" -> "↶"
        "roundabout" -> "⟳"
        "merge" -> "⤴"
        "arrive" -> "🏁"
        else -> "↑"
    }

    // ---------------- Google Routes ----------------
    fun fromGoogle(r: JSONObject): List<Step> {
        val out = ArrayList<Step>()
        val legs = r.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val steps = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val st = steps.optJSONObject(i) ?: continue
                val ll = st.optJSONObject("startLocation")?.optJSONObject("latLng") ?: continue
                val at = LatLng(ll.optDouble("latitude"), ll.optDouble("longitude"))
                val ni = st.optJSONObject("navigationInstruction")
                val man = ni?.optString("maneuver").orEmpty()
                val text = ni?.optString("instructions").orEmpty().replace("\n", " ").trim()
                if (i == 0 && l == 0) continue  // yola çıkış
                val code = when {
                    man.startsWith("ROUNDABOUT") -> "roundabout"
                    man.startsWith("UTURN") -> "uturn"
                    man == "TURN_SHARP_LEFT" -> "sharp_left"
                    man == "TURN_SHARP_RIGHT" -> "sharp_right"
                    man == "TURN_SLIGHT_LEFT" -> "slight_left"
                    man == "TURN_SLIGHT_RIGHT" -> "slight_right"
                    man == "TURN_LEFT" -> "left"
                    man == "TURN_RIGHT" -> "right"
                    man == "RAMP_LEFT" -> "ramp_left"
                    man == "RAMP_RIGHT" -> "ramp_right"
                    man == "FORK_LEFT" -> "fork_left"
                    man == "FORK_RIGHT" -> "fork_right"
                    man == "MERGE" -> "merge"
                    else -> "straight"
                }
                out.add(Step(at, code, text.ifBlank { tr(code, "") }))
            }
        }
        return out
    }

    // ---------------- OSRM ----------------
    fun fromOsrm(r: JSONObject): List<Step> {
        val out = ArrayList<Step>()
        val legs = r.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val steps = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val st = steps.optJSONObject(i) ?: continue
                val m = st.optJSONObject("maneuver") ?: continue
                val loc = m.optJSONArray("location") ?: continue
                val type = m.optString("type")
                if (type == "depart") continue
                val mod = m.optString("modifier")
                val name = st.optString("name").ifBlank { st.optString("ref") }
                val code = when {
                    type == "arrive" -> "arrive"
                    type == "roundabout" || type == "rotary" || type == "roundabout turn" -> "roundabout"
                    type == "merge" -> "merge"
                    mod == "uturn" -> "uturn"
                    type == "on ramp" || type == "off ramp" -> if (mod.contains("left")) "ramp_left" else "ramp_right"
                    type == "fork" -> if (mod.contains("left")) "fork_left" else "fork_right"
                    type == "new name" || type == "continue" && mod == "straight" -> "straight"
                    mod == "left" -> "left"
                    mod == "right" -> "right"
                    mod == "slight left" -> "slight_left"
                    mod == "slight right" -> "slight_right"
                    mod == "sharp left" -> "sharp_left"
                    mod == "sharp right" -> "sharp_right"
                    else -> "straight"
                }
                val exit = m.optInt("exit", 0)
                val text = if (code == "roundabout" && exit > 0) "Göbekten $exit. çıkıştan çıkın" + (if (name.isNotBlank()) ", $name" else "")
                else tr(code, name)
                out.add(Step(LatLng(loc.getDouble(1), loc.getDouble(0)), code, text))
            }
        }
        return out
    }

    private fun tr(code: String, road: String): String {
        val base = when (code) {
            "left" -> "Sola dönün"
            "right" -> "Sağa dönün"
            "slight_left" -> "Hafif sola dönün"
            "slight_right" -> "Hafif sağa dönün"
            "sharp_left" -> "Keskin sola dönün"
            "sharp_right" -> "Keskin sağa dönün"
            "uturn" -> "U dönüşü yapın"
            "roundabout" -> "Göbekten geçin"
            "merge" -> "Yola katılın"
            "ramp_left" -> "Soldaki bağlantı yoluna girin"
            "ramp_right" -> "Sağdaki bağlantı yoluna girin"
            "fork_left" -> "Ayrımda soldan devam edin"
            "fork_right" -> "Ayrımda sağdan devam edin"
            "arrive" -> "Hedefe vardınız"
            else -> "Düz devam edin"
        }
        return if (road.isNotBlank() && code != "arrive") "$base, $road" else base
    }
}
