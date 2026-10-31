package com.xantildev.koridor

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Konya'daki koridorları arka planda yavaş yavaş toplar: merkez ilçelerden her ilçeye ve geri
 * trafik.gov.tr rota sorgusu atar, bulunanları telefona kaydeder. Servis engellemesin diye
 * uygulama açıkken en fazla 90 sn'de bir sorgu, hata olursa 5 dk bekler. Bitince 30 gün sonra yeniler.
 */
object CorridorCrawler {
    private const val PROVINCE = "Konya"
    private val HUBS = listOf("Selçuklu", "Meram", "Karatay")
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var districts: List<TrafikApi.District>? = null
    private var started = false

    var status by mutableStateOf("Konya koridorları birazdan taranmaya başlayacak.")
        private set

    private fun tr(s: String) = s.lowercase(Locale("tr", "TR")).trim()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("tarama", Context.MODE_PRIVATE)
        if (started) return
        started = true
        scope.launch {
            delay(20_000)
            while (true) {
                val wait = try {
                    step()
                } catch (e: Exception) {
                    status = "Konya taraması: servis şu an cevap vermiyor, 5 dk sonra devam edecek."
                    300_000L
                }
                delay(wait)
            }
        }
    }

    private suspend fun pairs(): List<Pair<TrafikApi.District, TrafikApi.District>> {
        val ds = districts ?: run {
            val city = TrafikApi.cities().firstOrNull { tr(it.name) == tr(PROVINCE) } ?: return emptyList()
            TrafikApi.districts(city.id).also { districts = it }
        }
        val hubs = ds.filter { d -> HUBS.any { tr(it) == tr(d.name) } }.ifEmpty { ds.take(1) }
        val hub = hubs.first()
        val out = ArrayList<Pair<TrafikApi.District, TrafikApi.District>>()
        for (d in ds) if (d.id != hub.id) { out.add(hub to d); out.add(d to hub) }
        for (a in hubs) for (b in hubs) if (a.id != b.id && a.id != hub.id && b.id != hub.id) out.add(a to b)
        return out
    }

    /** Bir sorgu yapar; bir sonraki sorguya kadar beklenecek süreyi döner. */
    private suspend fun step(): Long {
        val now = System.currentTimeMillis()
        val doneAt = prefs.getLong("bitti", 0)
        if (doneAt > now - 30L * 24 * 3600 * 1000) {
            status = "Konya taraması tamam · telefonda ${CorridorStore.routeCount} koridor kayıtlı."
            return 3_600_000
        }
        if (doneAt > 0) prefs.edit().remove("bitti").putStringSet("yapildi", emptySet()).apply()
        val all = pairs()
        if (all.isEmpty()) return 600_000
        val done = HashSet(prefs.getStringSet("yapildi", emptySet()) ?: emptySet())
        val next = all.firstOrNull { (a, b) -> "${a.id}-${b.id}" !in done }
        if (next == null) {
            prefs.edit().putLong("bitti", now).apply()
            status = "Konya taraması tamam · telefonda ${CorridorStore.routeCount} koridor kayıtlı."
            return 3_600_000
        }
        val (a, b) = next
        status = "Konya taranıyor: ${a.name} → ${b.name} (${done.size + 1}/${all.size})"
        val found = TrafikApi.route(a, b)
        CorridorStore.addRoute(found)
        done.add("${a.id}-${b.id}")
        prefs.edit().putStringSet("yapildi", done).apply()
        status = "Konya taranıyor: ${done.size}/${all.size} yol bakıldı · telefonda ${CorridorStore.routeCount} koridor kayıtlı."
        return 90_000
    }
}
