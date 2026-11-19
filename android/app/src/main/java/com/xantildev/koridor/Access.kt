package com.xantildev.koridor

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Erişim kontrolü (yalnız firma sürümü): çalışanlar kendi girişleriyle, çalışan olmayanlar (misafir) yöneticinin
 * verdiği davet koduyla kullanır. Yönetici paneldeki "Erişim" sekmesinden kodu kapatabilir, cihazı engelleyebilir.
 * Uygulama açılışta ve 6 saatte bir sorar. İnternet yoksa son onaydan sonra 3 gün çalışır, sonra kilitlenir.
 * Misafirlerin günlük Google rota/arama sayısı sınırlıdır; sınır dolunca rota ücretsiz OpenStreetMap'ten gelir.
 */
object Access {
    private const val GRACE_MS = 3 * 24 * 3600_000L
    private const val EMPLOYEE_GRACE_MS = 7 * 24 * 3600_000L
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var app: Context? = null

    /** Kilit ekranında gösterilecek neden; null = kullanabilir. */
    var lockReason by mutableStateOf<String?>(null)
        private set
    /** Kilitli değil ama uyarı (ör. çalışan oturumu bitmek üzere). */
    var notice by mutableStateOf<String?>(null)
        private set
    var type by mutableStateOf("")          // "calisan" / "kod" / ""
        private set
    var guestName by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    private var dailyLimit: Int? = null
    private var usedToday by mutableIntStateOf(0)
    private var pendingRoute = 0
    private var pendingSearch = 0
    private var pendingKm = 0.0
    private var pendingTrips = 0

    val enabled: Boolean get() = Firma.enabled
    val allowed: Boolean get() = !enabled || lockReason == null
    val isGuest: Boolean get() = type == "kod"

    private fun prefs() = app?.getSharedPreferences("erisim", Context.MODE_PRIVATE)
    val code: String? get() = prefs()?.getString("kod", null)

    fun init(ctx: Context) {
        if (!enabled) return
        app = ctx.applicationContext
        val p = prefs()!!
        type = p.getString("tur", "") ?: ""
        guestName = p.getString("ad", null)
        dailyLimit = if (p.contains("sinir")) p.getInt("sinir", 50) else null
        // Açılışta önce son bilinen duruma göre karar ver (çevrimdışı açılış için), sonra sunucuya sor
        decideOffline()
        scope.launch {
            while (true) {
                check()
                repeat(36) { delay(10 * 60_000L); flushUsage() }   // 6 saatte bir kontrol, 10 dk'da bir kullanım
            }
        }
    }

    private fun decideOffline() {
        val p = prefs() ?: return
        val last = p.getLong("son_onay", 0L)
        val age = System.currentTimeMillis() - last
        lockReason = when {
            !p.getBoolean("kurulu", false) -> null                          // sunucuda sistem henüz kurulu değil

            Firma.logToken != null -> null                                  // çalışan girişi var
            last > 0 && age < GRACE_MS && type == "kod" -> null            // misafir, yakın zamanda onaylı
            !code.isNullOrBlank() && last == 0L -> "Davet kodu doğrulanamadı. İnternete bağlanıp “Tekrar dene”ye bas."
            last > 0 && type == "kod" -> "3 günden uzun süredir doğrulanamadı. İnternete bağlanıp “Tekrar dene”ye bas."
            else -> "Bu uygulamayı kullanmak için çalışan girişi ya da yöneticinin verdiği davet kodu gerekir."
        }
    }

    private fun reasonText(n: String?): String = when (n) {
        "cihaz_engelli" -> "Bu cihazın erişimi yönetici tarafından kapatıldı."
        "calisan_kapali" -> "Çalışan hesabın kapatılmış. Yöneticine başvur."
        "kod_yok" -> "Davet kodu bulunamadı. Kodu kontrol et."
        "kod_kapali" -> "Bu davet kodu yönetici tarafından kapatıldı."
        "kod_bitti" -> "Davet kodunun süresi doldu."
        "cihaz_siniri" -> "Bu davet kodu başka cihazlarda kullanılıyor (cihaz sınırı dolu)."
        "oturum_bitti" -> "Çalışan girişinin süresi doldu. Aşağıdan yeniden giriş yap."
        else -> "Bu uygulamayı kullanmak için çalışan girişi ya da yöneticinin verdiği davet kodu gerekir."
    }

    /** Sunucuya sor. [newCode] verilirse önce o denenir (kilit ekranından). Dönen metin: hata/sonuç. */
    suspend fun check(newCode: String? = null): String? {
        if (!enabled) return null
        val p = prefs() ?: return null
        checking = true
        val kod = (newCode ?: code)?.trim()?.uppercase()
        try {
            val res = withContext(Dispatchers.IO) {
                Firma.rpc("erisim_kontrol", JSONObject().put("p_token", Firma.logToken ?: JSONObject.NULL)
                    .put("p_kod", kod ?: JSONObject.NULL).put("p_cihaz", TLog.deviceId.ifBlank { JSONObject.NULL })
                    .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()))
            }
            val o = JSONObject(res)
            p.edit().putBoolean("kurulu", true).apply()
            if (o.optBoolean("izin")) {
                val t = o.optString("tur")
                type = t
                p.edit().putLong("son_onay", System.currentTimeMillis()).putString("tur", t).apply()
                if (t == "kod") {
                    guestName = o.optString("ad").ifBlank { null }
                    dailyLimit = if (o.isNull("gunluk")) null else o.optInt("gunluk")
                    usedToday = o.optInt("bugun")
                    p.edit().putString("kod", kod).putString("ad", guestName)
                        .apply { if (dailyLimit == null) remove("sinir") else putInt("sinir", dailyLimit!!) }.apply()
                    if (newCode != null) TLog.i("erisim", "Davet koduyla açıldı")
                } else p.edit().putLong("calisan_onay", System.currentTimeMillis()).apply()
                lockReason = null; notice = null
                return null
            }
            val n = o.optString("neden")
            if (n == "oturum_bitti" && System.currentTimeMillis() - p.getLong("calisan_onay", 0L) < EMPLOYEE_GRACE_MS) {
                // Çalışan oturumu bitti ama yakın zamanda onaylıydı: kullanmaya devam, yeniden giriş iste
                lockReason = null
                notice = "Çalışan girişinin süresi doldu. Ayarlar'dan yeniden giriş yap (7 gün içinde)."
                return null
            }
            lockReason = reasonText(n)
            if (newCode != null) p.edit().remove("kod").apply()
            TLog.w("erisim", "Erişim reddedildi: $n")
            return lockReason
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("Could not find the function", true) || m.contains("erisim_kontrol", true)) {
                // Sunucuda erişim sistemi henüz kurulmadı: kimseyi kilitleme
                lockReason = null
                return null
            }
            decideOffline()
            return if (newCode != null) "Bağlantı kurulamadı: $m" else null
        } finally {
            checking = false
        }
    }

    // ---------------- Kullanım ve kota ----------------

    /** Google rota/arama isteğinden önce: misafirin günlük sınırı dolduysa false. */
    fun googleAllowed(): Boolean {
        val lim = dailyLimit ?: return true
        return !isGuest || usedToday < lim
    }

    val quotaText: String? get() = if (isGuest && dailyLimit != null) "Bugün $usedToday / $dailyLimit Google rota+arama" else null

    fun countGoogle(kind: String) {
        if (!enabled) return
        usedToday++
        if (kind == "rota") pendingRoute++ else pendingSearch++
    }

    fun countTrip(km: Double) {
        if (!enabled) return
        pendingTrips++; pendingKm += km
        scope.launch { flushUsage() }
    }

    private suspend fun flushUsage() {
        if (pendingRoute + pendingSearch + pendingTrips == 0 && pendingKm == 0.0) return
        val r = pendingRoute; val s = pendingSearch; val k = pendingKm; val t = pendingTrips
        pendingRoute = 0; pendingSearch = 0; pendingKm = 0.0; pendingTrips = 0
        try {
            val res = withContext(Dispatchers.IO) {
                Firma.rpc("kullanim_ekle", JSONObject().put("p_token", Firma.logToken ?: JSONObject.NULL)
                    .put("p_kod", code ?: JSONObject.NULL).put("p_cihaz", TLog.deviceId)
                    .put("p_rota", r).put("p_arama", s).put("p_km", Math.round(k * 10) / 10.0).put("p_yolculuk", t))
            }
            runCatching { JSONObject(res).optInt("bugun", usedToday) }.getOrNull()?.let { usedToday = maxOf(usedToday, it) }
        } catch (e: Exception) {
            if ((e.message ?: "").contains("Could not find the function", true)) return   // sunucu henüz kurulmadı
            // Gönderilemedi: sonraki sefere ekle
            pendingRoute += r; pendingSearch += s; pendingKm += k; pendingTrips += t
        }
    }
}
