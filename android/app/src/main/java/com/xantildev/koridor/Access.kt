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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Erişim (yalnız firma sürümü). Dört durum:
 *  - "calisan": çalışan girişi. Google hakkı günde 30 (panelden değişir), firma özellikleri açık.
 *  - "firma":   firma cihazı kodu (ör. BYD tableti). Google genelde sınırsız, firma özellikleri açık.
 *  - "kod":     misafir davet kodu (aylık). Google hakkı günde 20, firma özellikleri ve teknik kayıt kapalı.
 *  - "":        kodsuz → ücretsiz mod: yalnız OpenStreetMap, EGM 30 sn arayla.
 * Kilit (uygulama hiç açılmaz) yalnız yönetici cihazı engellediyse, kodu kapattıysa ya da kişiyi pasif yaptıysa.
 * Her Google isteğinden önce sunucuya "hakkı var mı" sorulur; sayaç sunucuda tutulur.
 */
object Access {
    private const val GRACE_MS = 3 * 24 * 3600_000L
    private const val EMPLOYEE_GRACE_MS = 7 * 24 * 3600_000L
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var app: Context? = null

    /** Kilit ekranında gösterilecek neden; null = kullanabilir. */
    var lockReason by mutableStateOf<String?>(null)
        private set
    /** Kilitli değil ama bilgi/uyarı (ücretsiz mod, kodun süresi doldu, günlük hak doldu…). */
    var notice by mutableStateOf<String?>(null)
        private set
    var type by mutableStateOf("")          // "calisan" / "firma" / "kod" / ""
        private set
    var guestName by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Firma cihazı kodunun sunucu anahtarı (radar, teknik kayıt için). */
    var deviceToken: String? = null
        private set
    private var dailyLimit: Int? = null
    private var usedToday by mutableIntStateOf(0)
    private var quotaFull by mutableStateOf(false)
    private var quotaDay = ""
    private var pendingKm = 0.0
    private var pendingTrips = 0

    val enabled: Boolean get() = Firma.enabled
    val allowed: Boolean get() = !enabled || lockReason == null
    /** Firma özellikleri (ortak radar, teknik kayıt, limit düzeltme) kapalı olanlar: misafir ve kodsuz. */
    val isGuest: Boolean get() = enabled && (type == "kod" || type == "")
    /** Kodsuz: Google yok, yalnız OpenStreetMap; EGM daha seyrek. */
    val freeMode: Boolean get() = enabled && type == ""
    /** Google rota/arama şu an kullanılabilir mi (ücretsiz mod değil, bugünkü hak dolmamış). */
    val googleOk: Boolean get() = !enabled || (!freeMode && !quotaFullToday())

    private fun prefs() = app?.getSharedPreferences("erisim", Context.MODE_PRIVATE)
    val code: String? get() = prefs()?.getString("kod", null)

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Istanbul") }
    private fun today(): String = dayFmt.format(Date())
    private fun quotaFullToday(): Boolean {
        if (quotaDay != today()) { quotaDay = today(); quotaFull = false; usedToday = 0 }
        return quotaFull
    }

    fun init(ctx: Context) {
        if (!enabled) return
        app = ctx.applicationContext
        val p = prefs()!!
        type = p.getString("tur", "") ?: ""
        guestName = p.getString("ad", null)
        deviceToken = p.getString("cihaz_anahtar", null)
        dailyLimit = if (p.contains("sinir")) p.getInt("sinir", 20) else null
        decideOffline()
        scope.launch {
            while (true) {
                check()
                repeat(36) { delay(10 * 60_000L); flushUsage() }   // 6 saatte bir kontrol, 10 dk'da bir kullanım/çevrimiçi
            }
        }
    }

    /** İnternet yokken: son bilinen duruma göre. */
    private fun decideOffline() {
        val p = prefs() ?: return
        p.getString("kilit", null)?.let { lockReason = it; return }
        lockReason = null
        val age = System.currentTimeMillis() - p.getLong("son_onay", 0L)
        val saved = p.getString("tur", "") ?: ""
        type = when {
            saved == "calisan" && Firma.employeeToken != null && age < EMPLOYEE_GRACE_MS -> "calisan"
            (saved == "kod" || saved == "firma") && age < GRACE_MS -> saved
            Firma.employeeToken != null && !p.getBoolean("kurulu", false) -> "calisan"
            else -> ""
        }
        if (type == "") notice = FREE_TEXT
    }

    private const val FREE_TEXT = "Ücretsiz mod: rota ve arama OpenStreetMap'ten. Google ve firma özellikleri için çalışan girişi ya da davet kodu gir."

    private fun lockText(n: String?): String = when (n) {
        "cihaz_engelli" -> "Bu cihazın erişimi yönetici tarafından kapatıldı."
        "calisan_pasif" -> "Uygulama erişimin yönetici tarafından kapatıldı."
        "kod_kapali" -> "Bu davet kodu yönetici tarafından kapatıldı. Yeni kod aldıysan aşağıya gir."
        else -> "Erişim kapalı."
    }

    private fun freeText(n: String?): String = when (n) {
        "kod_bitti" -> "Davet kodunun süresi doldu; ücretsiz moddasın (yalnız OpenStreetMap). Yeni kod için yöneticine başvur."
        "oturum_bitti" -> "Çalışan girişinin süresi doldu; ücretsiz moddasın. Ayarlar'dan yeniden giriş yap."
        "calisan_kapali" -> "Çalışan hesabın kapalı; ücretsiz moddasın (yalnız OpenStreetMap)."
        "cihaz_siniri" -> "Davet kodu başka cihazlarda kullanılıyor (cihaz sınırı dolu); ücretsiz moddasın."
        else -> FREE_TEXT
    }

    private fun codeError(n: String?): String = when (n) {
        "kod_yok" -> "Davet kodu bulunamadı. Kodu kontrol et."
        "kod_kapali" -> "Bu davet kodu yönetici tarafından kapatıldı."
        "kod_bitti" -> "Bu davet kodunun süresi dolmuş."
        "cihaz_siniri" -> "Bu davet kodu başka cihazlarda kullanılıyor (cihaz sınırı dolu)."
        "cihaz_engelli" -> "Bu cihazın erişimi yönetici tarafından kapatıldı."
        else -> "Kod kabul edilmedi."
    }

    /** Sunucuya sor. [newCode] verilirse o denenir (kod girişi). Dönen metin: hata; null = tamam. */
    suspend fun check(newCode: String? = null): String? {
        if (!enabled) return null
        val p = prefs() ?: return null
        checking = true
        val kod = (newCode ?: code)?.trim()?.uppercase()?.ifBlank { null }
        try {
            val res = withContext(Dispatchers.IO) {
                Firma.rpc("erisim_kontrol", JSONObject().put("p_token", Firma.employeeToken ?: JSONObject.NULL)
                    .put("p_kod", kod ?: JSONObject.NULL).put("p_cihaz", TLog.deviceId.ifBlank { JSONObject.NULL })
                    .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()))
            }
            val o = JSONObject(res)
            p.edit().putBoolean("kurulu", true).apply()
            if (o.optBoolean("izin")) {
                val t = o.optString("tur")
                type = t
                lockReason = null; notice = null
                dailyLimit = if (o.isNull("gunluk")) null else o.optInt("gunluk")
                usedToday = o.optInt("bugun"); quotaDay = today()
                quotaFull = dailyLimit?.let { usedToday >= it } ?: false
                if (quotaFull) notice = QUOTA_TEXT
                val e = p.edit().remove("kilit").putLong("son_onay", System.currentTimeMillis()).putString("tur", t)
                    .apply { if (dailyLimit == null) remove("sinir") else putInt("sinir", dailyLimit!!) }
                if (t == "kod" || t == "firma") {
                    guestName = o.optString("ad").ifBlank { null }
                    e.putString("kod", kod).putString("ad", guestName)
                    deviceToken = if (t == "firma") o.optString("token").ifBlank { null } else null
                    if (deviceToken != null) e.putString("cihaz_anahtar", deviceToken) else e.remove("cihaz_anahtar")
                    if (newCode != null) TLog.i("erisim", if (t == "firma") "Firma cihazı koduyla açıldı" else "Davet koduyla açıldı")
                } else {
                    deviceToken = null; e.remove("cihaz_anahtar")
                }
                e.apply()
                return null
            }
            val n = o.optString("neden")
            if (newCode != null && n != "cihaz_engelli") {
                // Girilen kod olmadı: önceki durum değişmez
                return codeError(n)
            }
            when (n) {
                "cihaz_engelli", "calisan_pasif", "kod_kapali" -> {
                    lockReason = lockText(n)
                    p.edit().putString("kilit", lockReason).apply()
                    TLog.w("erisim", "Erişim kapatıldı: $n")
                    return lockReason
                }
                "oturum_bitti" -> if (System.currentTimeMillis() - p.getLong("son_onay", 0L) < EMPLOYEE_GRACE_MS && type == "calisan") {
                    lockReason = null
                    notice = "Çalışan girişinin süresi doldu. Ayarlar'dan yeniden giriş yap (7 gün içinde)."
                    return null
                }
            }
            // Ücretsiz mod
            lockReason = null
            type = ""; deviceToken = null; dailyLimit = null
            notice = freeText(n)
            p.edit().remove("kilit").putString("tur", "").remove("cihaz_anahtar")
                .apply { if (n == "kod_bitti" || n == "kod_yok") remove("kod") }.apply()
            return null
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("Could not find the function", true)) {
                // Sunucuda erişim sistemi kurulu değil: eski davranış (kimse kilitlenmez)
                lockReason = null
                if (type == "") type = if (Firma.employeeToken != null) "calisan" else ""
                return null
            }
            decideOffline()
            return if (newCode != null) "Bağlantı kurulamadı: $m" else null
        } finally {
            checking = false
        }
    }

    /** Kilit ekranında "Tekrar dene" / kod girişi. */
    suspend fun retry(newCode: String?): String? {
        prefs()?.edit()?.remove("kilit")?.apply()
        val r = check(newCode)
        if (r != null && newCode != null) check()   // kod olmadı: sunucunun asıl kararını al (gerekirse yine kilit)
        return r
    }

    // ---------------- Google hakkı ----------------

    private const val QUOTA_TEXT = "Bugünkü Google hakkın doldu, rota ücretsiz haritadan; trafik bilgisi yarın."
    val quotaMessage: String get() = if (freeMode) "Ücretsiz mod: Google kapalı" else QUOTA_TEXT

    val quotaText: String? get() = when {
        !enabled -> null
        freeMode -> "Ücretsiz mod · yalnız OpenStreetMap"
        dailyLimit != null -> "Bugün $usedToday / $dailyLimit Google isteği (rota + trafik + arama)"
        else -> "Bugün $usedToday Google isteği (sınırsız)"
    }

    /**
     * Her Google isteğinden hemen önce (IO iş parçacığında): sunucuda hak sayılır.
     * false → istek yapılmaz, uygulama OpenStreetMap'e geçer. İnternet/sunucu sorununda telefondaki sayaçla karar verilir.
     */
    fun googleGate(kind: String): Boolean {
        if (!enabled) return true
        if (kind == "oneri") return suggestGate()
        if (freeMode || quotaFullToday()) return false
        return try {
            val res = Firma.rpc("google_hak", JSONObject().put("p_token", Firma.employeeToken ?: JSONObject.NULL)
                .put("p_kod", if (type == "kod" || type == "firma") code ?: JSONObject.NULL else JSONObject.NULL)
                .put("p_cihaz", TLog.deviceId).put("p_tur", kind))
            val o = JSONObject(res)
            if (o.has("sinir")) dailyLimit = if (o.isNull("sinir")) null else o.optInt("sinir")
            if (o.has("bugun")) usedToday = o.optInt("bugun")
            if (o.optBoolean("izin")) true
            else {
                if (o.optString("neden") == "sinir") {
                    quotaFull = true; notice = QUOTA_TEXT
                    TLog.i("erisim", "Günlük Google hakkı doldu ($usedToday)")
                } else scope.launch { check() }   // hak değil erişim sorunu: durumu yenile
                false
            }
        } catch (e: IOException) {
            if ((e.message ?: "").contains("Could not find the function", true)) return true   // sunucu henüz kurulmadı
            // Bağlantı yok: telefondaki sayaç
            val lim = dailyLimit
            if (lim != null && usedToday >= lim) { quotaFull = true; notice = QUOTA_TEXT; false }
            else { usedToday++; true }
        } catch (_: Exception) {
            true
        }
    }

    /** Yazarken öneri: rota/trafik/arama hakkından ayrı günlük sınır (sunucuda); sınır dolunca öneri yalnız kayıtlı yerlerden. */
    private var suggestFullDay = ""
    private fun suggestGate(): Boolean {
        if (freeMode || isGuest || suggestFullDay == today()) return false
        return try {
            val o = JSONObject(Firma.rpc("google_hak", JSONObject().put("p_token", Firma.employeeToken ?: JSONObject.NULL)
                .put("p_kod", if (type == "firma") code ?: JSONObject.NULL else JSONObject.NULL)
                .put("p_cihaz", TLog.deviceId).put("p_tur", "oneri")))
            if (!o.optBoolean("izin") && o.optString("neden") == "oneri_sinir") suggestFullDay = today()
            o.optBoolean("izin")
        } catch (_: Exception) {
            false   // bağlantı yoksa öneri de gelmez
        }
    }

    // ---------------- Kullanım ----------------

    fun countTrip(km: Double) {
        if (!enabled) return
        pendingTrips++; pendingKm += km
        scope.launch { flushUsage() }
    }

    /** Km/yolculuk ve "çevrimiçi" bilgisi (uygulama açıkken 10 dk'da bir). */
    private suspend fun flushUsage() {
        if (pendingTrips == 0 && pendingKm == 0.0 && Engine.state.value.mode == "idle" && !AppState.foreground) return
        val k = pendingKm; val t = pendingTrips
        pendingKm = 0.0; pendingTrips = 0
        try {
            val res = withContext(Dispatchers.IO) {
                Firma.rpc("kullanim_ekle", JSONObject().put("p_token", Firma.employeeToken ?: JSONObject.NULL)
                    .put("p_kod", if (type == "kod" || type == "firma") code ?: JSONObject.NULL else JSONObject.NULL)
                    .put("p_cihaz", TLog.deviceId)
                    .put("p_rota", 0).put("p_arama", 0).put("p_km", Math.round(k * 10) / 10.0).put("p_yolculuk", t))
            }
            runCatching { JSONObject(res) }.getOrNull()?.let { o ->
                if (o.has("bugun")) usedToday = maxOf(usedToday, o.optInt("bugun"))
            }
        } catch (e: Exception) {
            if ((e.message ?: "").contains("Could not find the function", true)) return
            pendingKm += k; pendingTrips += t
        }
    }
}
