package com.xantildev.koridor

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import java.util.Locale

/** Mikrofon düğmesi: dokun, konuş. "bitir", "devam", "ev", "iş", kayıtlı yer adı ya da il/yer adı. */
object Voice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val TR = Locale("tr", "TR")
    private fun norm(s: String) = s.lowercase(TR).replace(Regex("[^a-zçğıöşü0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun handle(ctx: Context, said: String) {
        val t = norm(said)
        if (t.isBlank()) return
        val words = t.split(" ")
        fun has(vararg w: String) = w.any { x -> words.any { it.startsWith(x) } }
        when {
            has("bitir", "durdur", "kapat", "sonlandır") || t == "dur" -> {
                if (Engine.state.value.driving) TrackingService.end(ctx)
                else Engine.say("Şu an süren bir sürüş yok.")
            }
            has("devam", "başlat", "başla") && words.size <= 3 -> {
                if (!Engine.state.value.driving) {
                    if (hasLocation(ctx)) TrackingService.start(ctx) else Engine.say("Önce konum izni vermen gerekiyor.")
                } else Engine.say("Sürüş devam ediyor.")
            }
            t == "ev" || t == "eve" || t == "eve git" || t == "ev rotası" || t == "eve gidiyorum" -> slot(ctx, "ev", "Ev")
            t == "iş" || t == "işe" || t == "işe git" || t == "iş rotası" || t == "işe gidiyorum" -> slot(ctx, "is", "İş")
            else -> {
                val q = t.removeSuffix(" rotası").removeSuffix(" rota").removeSuffix(" git").removeSuffix("e git").trim()
                val saved = Places.savedList(ctx).firstOrNull { norm(it.name) == q }
                    ?: Places.savedList(ctx).firstOrNull { q.contains(norm(it.name)) || norm(it.name).contains(q) }
                if (saved != null) {
                    Engine.say("${saved.name} rotası hazırlanıyor.")
                    Nav.tab = 0; Nav.big = true
                    RouteSession.go(ctx, saved)
                    return
                }
                Engine.say("$said aranıyor.")
                scope.launch {
                    val hit = try {
                        Places.search(q, Places.currentLocation(ctx)).firstOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (hit == null) {
                        Engine.say("$said bulunamadı.")
                        RouteSession.query = said
                        Nav.tab = 1
                    } else {
                        Nav.tab = 0; Nav.big = true
                        RouteSession.go(ctx, hit)
                    }
                }
            }
        }
    }

    private fun slot(ctx: Context, key: String, label: String) {
        val p = Places.loadSlot(ctx, key)
        if (p == null) {
            Engine.say("$label adresi kayıtlı değil. Nereye sekmesinden kaydedebilirsin.")
            Nav.tab = 1
        } else {
            Engine.say("$label rotası hazırlanıyor.")
            Nav.tab = 0; Nav.big = true
            RouteSession.go(ctx, p)
        }
    }
}

/**
 * İnternetsiz Türkçe ses tanıma (Vosk). Google ses tanıması olmayan cihazlarda (BYD araç ekranı) kullanılır.
 * Türkçe model (~35 MB) ilk kullanımda bir kez indirilir.
 */
object OfflineVoice {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
    /** "" yok · "sor" indirme sorusu · "indir" indiriliyor · "dinle" dinliyor · "hata" */
    var phase by androidx.compose.runtime.mutableStateOf("")
    var progress by androidx.compose.runtime.mutableStateOf(0f)
    var heard by androidx.compose.runtime.mutableStateOf("")
    var error by androidx.compose.runtime.mutableStateOf("")
    private var model: org.vosk.Model? = null
    private var svc: org.vosk.android.SpeechService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "vosk-tr")
    fun ready(ctx: Context) = java.io.File(dir(ctx), "tamam").exists()

    /** Google ses tanıması bu cihazda var mı? */
    fun googleAvailable(ctx: Context): Boolean =
        android.speech.SpeechRecognizer.isRecognitionAvailable(ctx) &&
            ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

    fun start(ctx: Context) {
        if (!ready(ctx)) { phase = "sor"; return }
        listen(ctx)
    }

    fun download(ctx: Context) {
        phase = "indir"; progress = 0f; error = ""
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val d = dir(ctx)
                    d.deleteRecursively(); d.mkdirs()
                    val c = java.net.URL(MODEL_URL).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: 36_000_000L
                    var read = 0L
                    val counting = object : java.io.FilterInputStream(c.inputStream) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n > 0) { read += n; progress = (read.toFloat() / total).coerceIn(0f, 1f) }
                            return n
                        }
                    }
                    java.util.zip.ZipInputStream(counting.buffered()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            // Zip içindeki ilk klasör (vosk-model-small-tr-0.3/) atlanır
                            val rel = e.name.substringAfter('/', "")
                            if (rel.isBlank() || rel.contains("..")) continue
                            val f = java.io.File(d, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                        }
                    }
                    c.disconnect()
                    java.io.File(d, "tamam").writeText("1")
                    true
                } catch (e: Exception) {
                    error = "Ses paketi indirilemedi: ${e.message ?: "bağlantı hatası"}"
                    false
                }
            }
            if (ok) listen(ctx) else phase = "hata"
        }
    }

    private fun listen(ctx: Context) {
        phase = "dinle"; heard = ""; error = ""
        scope.launch {
            try {
                val m = model ?: withContext(Dispatchers.IO) { org.vosk.Model(dir(ctx).path) }.also { model = it }
                val rec = org.vosk.Recognizer(m, 16000f)
                val s = org.vosk.android.SpeechService(rec, 16000f)
                svc = s
                s.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(h: String?) { heard = text(h, "partial") }
                    override fun onResult(h: String?) { finish(ctx, text(h, "text")) }
                    override fun onFinalResult(h: String?) { finish(ctx, text(h, "text")) }
                    override fun onError(e: Exception?) { stop(); error = "Mikrofon hatası: ${e?.message}"; phase = "hata" }
                    override fun onTimeout() { finish(ctx, heard) }
                }, 7000)
            } catch (e: Exception) {
                error = "Ses tanıma başlatılamadı: ${e.message}"
                phase = "hata"
            }
        }
    }

    private fun text(h: String?, key: String) = runCatching { org.json.JSONObject(h ?: "{}").optString(key) }.getOrDefault("").trim()

    private fun finish(ctx: Context, said: String) {
        if (phase != "dinle") return
        if (said.isBlank()) return   // sessizlik: süre dolana kadar dinlemeye devam
        stop()
        phase = ""
        Voice.handle(ctx, said)
    }

    fun stop() {
        runCatching { svc?.stop(); svc?.shutdown() }
        svc = null
        if (phase == "dinle") phase = ""
    }
}

/** Çevrimdışı ses tanımanın soruları ve "Dinliyorum…" penceresi (ana ekranda bir kez çizilir). */
@Composable
fun OfflineVoiceHost() {
    val ctx = LocalContext.current
    val ov = OfflineVoice
    when (ov.phase) {
        "sor" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { ov.phase = "" },
            title = { androidx.compose.material3.Text("Sesli komut paketi") },
            text = { androidx.compose.material3.Text("Bu cihazda Google ses tanıması yok. İnternetsiz çalışan Türkçe ses paketi (yaklaşık 35 MB) bir kez indirilecek. İndirelim mi?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ov.download(ctx) }) { androidx.compose.material3.Text("İndir") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { ov.phase = "" }) { androidx.compose.material3.Text("Vazgeç") } }
        )
        "indir" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { androidx.compose.material3.Text("Ses paketi indiriliyor") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.LinearProgressIndicator(progress = { ov.progress }, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
                    androidx.compose.material3.Text("%${(ov.progress * 100).toInt()}")
                }
            },
            confirmButton = { }
        )
        "dinle" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { ov.stop() },
            title = { androidx.compose.material3.Text("🎤 Dinliyorum…") },
            text = { androidx.compose.material3.Text(ov.heard.ifBlank { "Söyle: ev, iş, kayıtlı yer, il adı ya da bitir" }) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ov.stop() }) { androidx.compose.material3.Text("Kapat") } }
        )
        "hata" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { ov.phase = "" },
            title = { androidx.compose.material3.Text("Sesli komut") },
            text = { androidx.compose.material3.Text(ov.error) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ov.phase = "" }) { androidx.compose.material3.Text("Tamam") } }
        )
    }
}

/** Konuşmayı başlatan fonksiyonu verir: Google ses tanıması varsa o, yoksa internetsiz Türkçe ses tanıma. */
@Composable
fun rememberVoice(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said != null) Voice.handle(ctx, said)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) OfflineVoice.start(ctx) else Toast.makeText(ctx, "Sesli komut için mikrofon izni gerekiyor.", Toast.LENGTH_LONG).show()
    }
    return {
        if (OfflineVoice.googleAvailable(ctx)) {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Söyle: bitir, devam, ev, iş, kayıtlı yer ya da il adı")
            try {
                launcher.launch(i)
            } catch (_: Exception) {
                OfflineVoice.start(ctx)
            }
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            OfflineVoice.start(ctx)
        } else mic.launch(android.Manifest.permission.RECORD_AUDIO)
    }
}
