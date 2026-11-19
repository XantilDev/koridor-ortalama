package com.xantildev.koridor

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Teknik kayıtlar (hata ayıklama için): her olay kuyruğa yazılır, dakikada bir Supabase'e toplu gönderilir.
 * İnternet yoksa telefonda birikir (en çok 2000 kayıt). Yalnız firma sürümünde ve Ayarlar'dan açıksa.
 * Gönderim aynı zamanda "cihaz çevrimiçi" bilgisidir (panelde son görülme).
 */
object TLog {
    private const val MAX_QUEUE = 2000
    private const val BATCH = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val queue = ArrayDeque<JSONObject>()
    private var file: File? = null
    var deviceId = ""
        private set
    private var dirty = false
    private var started = false

    var lastSentAt by mutableStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var pending by mutableStateOf(0)
        private set

    /** Misafirlerden (davet kodu) teknik kayıt alınmaz. */
    val enabled: Boolean get() = Firma.enabled && AppSettings.techLog && !Access.isGuest

    fun init(ctx: Context) {
        if (!Firma.enabled) return
        val prefs = ctx.getSharedPreferences("tlog", Context.MODE_PRIVATE)
        deviceId = prefs.getString("cihaz", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("cihaz", it).apply() }
        file = File(ctx.filesDir, "tlog.jsonl")
        runCatching {
            file?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_QUEUE)?.forEach { l -> runCatching { queue.addLast(JSONObject(l)) } }
        }
        pending = queue.size
        // Önceki açılışta çökme olduysa (App'teki yakalayıcı kaydetti) gönder
        val crash = ctx.getSharedPreferences("hata", Context.MODE_PRIVATE)
        crash.getString("son", null)?.let { st ->
            if (crash.getString("gonderildi", null) != st) {
                e("cokme", st.lineSequence().firstOrNull()?.take(200) ?: "çökme", JSONObject().put("yigin", st.take(3500)))
                crash.edit().putString("gonderildi", st).apply()
            }
        }
        ExitInfo.last?.let { t ->
            val p = ctx.getSharedPreferences("tlog", Context.MODE_PRIVATE)
            if (p.getString("kapanma", null) != t) { w("uygulama", t); p.edit().putString("kapanma", t).apply() }
        }
        i("uygulama", "Açıldı", JSONObject().put("model", model()).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        start()
    }

    private fun model() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun i(olay: String, m: String, v: JSONObject? = null) = add("bilgi", olay, m, v)
    fun w(olay: String, m: String, v: JSONObject? = null) = add("uyari", olay, m, v)
    fun e(olay: String, m: String, v: JSONObject? = null) = add("hata", olay, m, v)

    /** Aynı mesajın sık tekrarını önlemek için: aynı anahtar en çok [everyMs] aralıkla yazılır. */
    private val lastKey = HashMap<String, Long>()
    fun throttled(key: String, everyMs: Long, block: () -> Unit) {
        val n = System.currentTimeMillis()
        synchronized(lastKey) {
            if (n - (lastKey[key] ?: 0L) < everyMs) return
            lastKey[key] = n
        }
        try { block() } catch (_: Throwable) {}
    }

    private fun add(tur: String, olay: String, m: String, v: JSONObject?) {
        try { addUnsafe(tur, olay, m, v) } catch (_: Throwable) {}   // kayıt yazmak uygulamayı asla düşürmesin
    }

    private fun addUnsafe(tur: String, olay: String, m: String, v: JSONObject?) {
        if (!Firma.enabled) return
        if ((!AppSettings.techLog || Access.isGuest) && olay != "cokme") return
        if (Access.isGuest) return
        val st = Engine.state.value
        val o = JSONObject().put("t", System.currentTimeMillis()).put("tur", if (olay == "cokme") "cokme" else tur)
            .put("olay", olay).put("m", m.take(1000)).put("sahsi", !Firma.isWork)
        if (v != null) o.put("v", v)
        st.pos?.let { o.put("lat", Math.round(it.lat * 1e6) / 1e6).put("lon", Math.round(it.lon * 1e6) / 1e6) }
        st.speedKmh?.let { o.put("hiz", it) }
        synchronized(queue) {
            queue.addLast(o)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            pending = queue.size
            dirty = true
        }
    }

    private fun start() {
        if (started) return
        started = true
        scope.launch {
            var tick = 0
            while (true) {
                delay(15_000)
                tick++
                saveIfDirty()
                // Dakikada bir gönder (sürüşte de); kuyruk büyüdüyse beklemeden
                if (tick % 4 == 0 || pending >= BATCH) runCatching { send() }
            }
        }
    }

    private fun saveIfDirty() {
        val f = file ?: return
        val lines = synchronized(queue) {
            if (!dirty) return
            dirty = false
            queue.joinToString("\n") { it.toString() }
        }
        runCatching { f.writeText(lines) }
    }

    private fun deviceInfo(): JSONObject {
        val st = Engine.state.value
        val durum = when {
            st.driving -> "sürüşte"
            st.mode == "bekle" -> "hazır"
            st.mode == "gps" -> "konum açık"
            else -> "açık"
        } + (if (AppState.foreground) "" else " · arka planda")
        val o = JSONObject().put("id", deviceId).put("model", model()).put("android", Build.VERSION.RELEASE)
            .put("surum", BuildConfig.VERSION_CODE).put("surum_ad", BuildConfig.VERSION_NAME).put("durum", durum)
        st.pos?.let { o.put("lat", it.lat).put("lon", it.lon) }
        return o
    }

    /** Kuyruğu gönderir; dönen metin ekranda gösterilir. */
    suspend fun send(): String = lock.withLock { sendLocked() }

    private suspend fun sendLocked(): String {
        if (!enabled) return "Teknik kayıt kapalı"
        val token = Firma.logToken ?: return "Cihaz firmaya bağlı değil (çalışan girişi yok)".also { lastError = it }
        var sent = 0
        try {
            do {
                val batch = synchronized(queue) { queue.take(BATCH) }
                val arr = JSONArray().also { a -> batch.forEach { a.put(it) } }
                withContext(Dispatchers.IO) {
                    Firma.rpc("log_ekle", JSONObject().put("p_token", token).put("p_cihaz", deviceInfo()).put("p_kayitlar", arr))
                }
                synchronized(queue) {
                    repeat(batch.size) { if (queue.isNotEmpty()) queue.removeFirst() }
                    pending = queue.size
                    dirty = true
                }
                sent += batch.size
            } while (batch.size == BATCH)
            lastSentAt = System.currentTimeMillis()
            lastError = null
            saveIfDirty()
            return if (sent == 0) "Bağlantı tamam, gönderilecek yeni kayıt yok" else "$sent kayıt gönderildi"
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            saveIfDirty()
            return "Gönderilemedi: $lastError (kayıtlar telefonda bekliyor: $pending)"
        }
    }

    /** "Logları şimdi gönder" düğmesi: o anki durumu da yazar, hemen gönderir. */
    fun sendNow(note: String?, done: (String) -> Unit) {
        i("elle", "Kullanıcı logları gönderdi" + (note?.let { ": $it" } ?: ""), JSONObject()
            .put("rota", RouteSession.diagRoute()).put("egm", RadarMap.diag()).put("harita", MapDiag.text)
            .put("konum", Engine.state.value.gpsText))
        scope.launch {
            val r = send()
            withContext(Dispatchers.Main) { done(r) }
        }
    }
}

/** Uygulama ön planda mı (log durumu ve arka plan geçişleri için). */
object AppState {
    @Volatile var foreground = false
    private var since = 0L
    fun set(fg: Boolean) {
        if (fg == foreground) return
        val n = System.currentTimeMillis()
        val dur = if (since > 0) (n - since) / 1000 else 0
        foreground = fg
        since = n
        TLog.i("uygulama", if (fg) "Ön plana geldi (arka planda ${dur} sn kaldı)" else "Arka plana geçti")
    }
}
