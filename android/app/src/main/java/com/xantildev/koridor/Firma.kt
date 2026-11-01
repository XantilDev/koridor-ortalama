package com.xantildev.koridor

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Uygulama ayarları: ses seviyesi ve gece modu. */
object AppSettings {
    private lateinit var prefs: SharedPreferences
    /** 0 = her şey, 1 = sadece koridor, 2 = kapalı */
    var voiceLevel by mutableStateOf(0)
        private set
    /** "oto" (20:00–07:00 arası), "acik", "kapali" */
    var night by mutableStateOf("oto")
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ayar", Context.MODE_PRIVATE)
        voiceLevel = prefs.getInt("ses", 0)
        night = prefs.getString("gece", "oto") ?: "oto"
    }

    fun setVoice(v: Int) { voiceLevel = v; prefs.edit().putInt("ses", v).apply() }
    fun chooseNight(v: String) { night = v; prefs.edit().putString("gece", v).apply() }

    fun isNight(): Boolean = when (night) {
        "acik" -> true
        "kapali" -> false
        else -> java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it >= 20 || it < 7 }
    }
}

/**
 * AYSOFT Personel bağlantısı (yalnız "firma" sürümünde; store sürümünde tamamen kapalıdır).
 * Cihaza birden çok çalışan bağlanabilir (ortak araç tableti); sürüş başında sürücü seçilir.
 * Yolculuk "İş" ise: son konum yöneticiye gider, yolculuk km raporuna işlenir.
 * "Şahsi" ise: firmaya HİÇBİR şey gönderilmez (konum da, yolculuk da).
 */
object Firma {
    val enabled: Boolean = BuildConfig.FIRMA
    private const val SB_URL = "https://advcgkmlfrnzwqbtplba.supabase.co"
    private const val SB_KEY = "sb_publishable_wur9wh2sFeQ7Tsb2r9qdvw_IQFQcxx6"
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Account(val name: String, val token: String)

    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    /** Sürücü (aktif çalışan) */
    var driver by mutableStateOf<String?>(null)
        private set
    /** "is" ya da "sahsi" */
    var tripType by mutableStateOf("sahsi")
        private set
    var lastSent by mutableStateOf(0L)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private val token: String? get() = accounts.firstOrNull { it.name == driver }?.token
    val connected: Boolean get() = enabled && token != null
    /** İş yolculuğu: firma sürümü + şirket aracı + "İş" seçili. Şahsi araçta asla. */
    val isWork: Boolean get() = enabled && VehicleStore.active.company && tripType == "is"
    /** Şu an konum paylaşılıyor mu (bağlı + İş yolculuğu) */
    val active: Boolean get() = connected && isWork

    private var lastMode = ""
    private var sending = false
    private var wasActive = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("firma", Context.MODE_PRIVATE)
        if (!enabled) return
        accounts = try {
            val a = JSONArray(prefs.getString("hesaplar", "[]"))
            (0 until a.length()).map { Account(a.getJSONObject(it).getString("ad"), a.getJSONObject(it).getString("t")) }
        } catch (_: Exception) {
            emptyList()
        }
        driver = prefs.getString("surucu", null)?.takeIf { d -> accounts.any { it.name == d } } ?: accounts.firstOrNull()?.name
        tripType = prefs.getString("amac", "sahsi") ?: "sahsi"
    }

    private fun saveAccounts() {
        val a = JSONArray()
        accounts.forEach { a.put(JSONObject().put("ad", it.name).put("t", it.token)) }
        prefs.edit().putString("hesaplar", a.toString()).putString("surucu", driver).apply()
    }

    private fun rpc(fn: String, body: JSONObject): String {
        val c = URL("$SB_URL/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("apikey", SB_KEY)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val m = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw IOException(m?.ifBlank { null } ?: "Sunucu hatası ($code)")
        }
        return text
    }

    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        val a = JSONArray(rpc("calisan_adlari", JSONObject()))
        (0 until a.length()).map { a.getString(it) }
    }

    /** Çalışanı bu cihaza ekler ve sürücü yapar; hata varsa mesajını döner. */
    suspend fun login(ad: String, pin: String): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext "Bu sürümde firma bağlantısı yok."
        try {
            val o = JSONObject(rpc("giris", JSONObject().put("p_ad", ad).put("p_pin", pin)))
            if (o.has("hata")) return@withContext o.getString("hata")
            val acc = Account(o.optString("ad", ad), o.getString("token"))
            withContext(Dispatchers.Main) {
                accounts = accounts.filter { it.name != acc.name } + acc
                driver = acc.name
                status = null; lastMode = ""
                saveAccounts()
            }
            null
        } catch (e: Exception) {
            e.message ?: "Bağlantı hatası"
        }
    }

    fun selectDriver(name: String) {
        if (driver == name) return
        close()  // önceki sürücünün konumu yönetici ekranından kalksın
        driver = name
        lastMode = ""
        saveAccounts()
    }

    fun chooseTripType(t: String) {
        if (!enabled || t == tripType) return
        tripType = t
        lastMode = ""
        prefs.edit().putString("amac", t).apply()
    }

    private fun close(t: String? = token) {
        t ?: return
        scope.launch(Dispatchers.IO) { runCatching { rpc("konum_kapat", JSONObject().put("p_token", t)) } }
    }

    fun remove(name: String) {
        val acc = accounts.firstOrNull { it.name == name } ?: return
        close(acc.token)
        accounts = accounts.filter { it.name != name }
        if (driver == name) driver = accounts.firstOrNull()?.name
        saveAccounts()
    }

    /** Takip servisi her durum değişiminde çağırır. Şahsi yolculukta hiçbir şey göndermez. */
    fun onState(s: UiState) {
        if (!active) {
            // Şahsiye / şahsi araca geçildi ya da bağlantı kesildi: son iş konumu "kapalı" olarak kalır, yenisi gönderilmez
            if (wasActive) { wasActive = false; close(); lastMode = "" }
            return
        }
        wasActive = true
        if (sending) return
        val t = token ?: return
        val p = s.pos ?: return
        val mode = if (s.driving) "surus" else "hazir"
        val gap = when {
            s.driving && (s.speedKmh ?: 0) >= 5 -> 30_000L
            s.driving -> 60_000L
            else -> 120_000L
        }
        val now = System.currentTimeMillis()
        if (mode == lastMode && now - lastSent < gap) return
        lastMode = mode
        val inC = s.confirmed && s.corridor != null
        val v = VehicleStore.active
        val body = JSONObject()
            .put("p_token", t).put("p_lat", p.lat).put("p_lon", p.lon)
            .put("p_hiz", s.speedKmh ?: JSONObject.NULL)
            .put("p_durum", mode)
            .put("p_koridor", if (inC) s.corridor?.name else JSONObject.NULL)
            .put("p_ortalama", if (inC) s.avg?.roundToInt() ?: JSONObject.NULL else JSONObject.NULL)
            .put("p_limit", if (inC) s.corridor?.limit ?: JSONObject.NULL else JSONObject.NULL)
            .put("p_hedef", RouteSession.info?.target?.name ?: JSONObject.NULL)
            .put("p_arac", v.type.label).put("p_plaka", v.plate.ifBlank { v.name })
            .put("p_km", if (s.driving) Math.round(s.trip.dist / 100) / 10.0 else JSONObject.NULL)
            .put("p_dogruluk", JSONObject.NULL)
        sending = true
        lastSent = now
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { rpc("konum_gonder", body) }.exceptionOrNull() }
            sending = false
            status = when {
                err == null -> null
                (err.message ?: "").contains("Oturum") -> { driver?.let { remove(it) }; "Firma oturumu kapandı, tekrar bağlan." }
                else -> "Konum gönderilemedi (internet?). Tekrar denenecek."
            }
            if (err != null) lastSent = System.currentTimeMillis() - gap + 30_000
        }
    }

    /** Takip tamamen kapanınca yöneticideki son konum silinir. */
    fun onTrackingOff() {
        if (wasActive) close()
        wasActive = false
        lastMode = ""
    }

    /** Biten İş yolculuğunu km raporuna işler (şahsi yolculuk hiç gönderilmez). */
    fun uploadTrip(r: TripRecord) {
        if (!enabled || r.purpose != "is" || r.sample) return   // şahsi yolculuk / şahsi araç: hiçbir şey gönderilmez
        val t = accounts.firstOrNull { it.name == r.driver }?.token ?: return
        val body = JSONObject()
            .put("p_token", t).put("p_istemci", r.id)
            .put("p_baslangic", iso(r.start)).put("p_bitis", iso(r.end))
            .put("p_km", Math.round(r.distM / 100) / 10.0)
            .put("p_sure_dk", (r.timeS / 60).roundToInt())
            .put("p_arac", r.vehicleType).put("p_plaka", r.plate.ifBlank { r.vehicleName })
            .put("p_hedef", r.target ?: JSONObject.NULL)
            .put("p_koridor", r.passes.size)
            .put("p_asim", r.passes.count { p -> p.limit != null && p.avg > p.limit })
        scope.launch(Dispatchers.IO) {
            val err = runCatching { rpc("yolculuk_ekle", body) }.exceptionOrNull()
            if (err != null) withContext(Dispatchers.Main) { status = "Yolculuk rapora işlenemedi: ${err.message}" }
        }
    }

    /** İş yolculuğundan AYSOFT Personel saha kaydı; hata varsa mesajını döner. */
    suspend fun addFieldRecord(r: TripRecord, firma: String, il: String, ilce: String, aciklama: String): String? = withContext(Dispatchers.IO) {
        val t = accounts.firstOrNull { it.name == r.driver }?.token ?: token ?: return@withContext "Firma bağlantısı yok."
        val d0 = day(r.start)
        val d1 = day(r.end)
        try {
            rpc("kayit_ekle", JSONObject().put("p_token", t).put("p_baslangic", d0).put("p_bitis", d1)
                .put("p_firma", firma).put("p_il", il).put("p_ilce", ilce).put("p_arkadaslar", "")
                .put("p_aciklama", aciklama).put("p_makine", false).put("p_gec_saat", JSONObject.NULL))
            null
        } catch (e: Exception) {
            e.message ?: "Gönderilemedi"
        }
    }

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date(ms))
    private fun day(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
}
