package com.xantildev.koridor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Levhaya göre düzeltilmiş koridor limitleri (ortak, Supabase'de). EGM'nin limiti yanlış olabiliyor
 * (ör. levhada 70, EGM'de 82). Çalışan ya da firma cihazı levhadaki limiti girince herkes onu görür:
 * "levhaya göre 70 · Ramazan". Misafir görür ama düzeltemez (adlar misafire gönderilmez). Kodsuz kullanıcıda yok.
 */
object SharedLimit {
    data class Fix(val limit: Int, val by: String?, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var map: Map<String, Fix> = emptyMap()
    private var prefs: android.content.SharedPreferences? = null

    fun get(key: String): Fix? = map[key]

    /** Ortak listeye yazabilir mi (çalışan ya da firma cihazı). */
    val canEdit: Boolean get() = Firma.enabled && !Access.isGuest && Firma.logToken != null

    fun init(ctx: Context) {
        if (!Firma.enabled) return
        prefs = ctx.getSharedPreferences("ortak_limit", Context.MODE_PRIVATE)
        runCatching { parse(JSONArray(prefs?.getString("liste", "[]") ?: "[]")) }
        scope.launch {
            delay(5_000)   // erişim kontrolü önce bitsin
            while (true) {
                refresh()
                delay(30 * 60_000L)
            }
        }
    }

    private fun parse(a: JSONArray) {
        val m = HashMap<String, Fix>()
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            val k = r.optString(0).ifBlank { null } ?: continue
            m[k] = Fix(r.optInt(1), if (r.isNull(2)) null else r.optString(2).ifBlank { null }, r.optLong(3) * 1000)
        }
        map = m
    }

    fun refresh() {
        if (!Firma.enabled || Access.freeMode) return
        try {
            val res = Firma.rpc("limit_duzeltmeleri", JSONObject().put("p_token", Firma.logToken ?: JSONObject.NULL)
                .put("p_kod", Access.code ?: JSONObject.NULL).put("p_cihaz", TLog.deviceId))
            val a = JSONArray(res)
            val before = map
            parse(a)
            prefs?.edit()?.putString("liste", res)?.apply()
            if (before != map) CorridorStore.vehicleChanged()   // etkin limitleri yeniden hesaplat
        } catch (_: Exception) {
            // Sunucu kurulu değil ya da bağlantı yok: telefondaki son liste kullanılır
        }
    }

    /** Levhadaki limiti ortak listeye yazar (yalnız otomobil levhası; ticari araç limiti telefonda kalır). */
    fun submit(c: Corridor, v: Int) {
        if (!canEdit || VehicleStore.type.commercial) return
        map = map + (c.key to Fix(v, Firma.driver ?: Access.guestName ?: "sen", System.currentTimeMillis()))
        scope.launch {
            try {
                Firma.rpc("limit_duzelt", JSONObject().put("p_token", Firma.logToken).put("p_anahtar", c.key)
                    .put("p_ad", c.name).put("p_limit", v).put("p_egm", c.limit ?: JSONObject.NULL).put("p_cihaz", TLog.deviceId))
                TLog.i("limit", "Levha limiti girildi: ${c.name} → $v (önceki ${c.limit ?: "?"})")
                refresh()
            } catch (e: Exception) {
                TLog.w("limit", "Levha limiti gönderilemedi: ${e.message}")
            }
        }
    }

    /** Ekranda limitin yanında: "levhaya göre · Ramazan". */
    fun label(c: Corridor?): String? {
        val f = c?.let { get(it.key) } ?: return null
        return "levhaya göre" + (f.by?.let { " · $it" } ?: "")
    }
}
