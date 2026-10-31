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
                if (Engine.tracking) {
                    Engine.say("Sürüş bitiriliyor.")
                    if (Engine.state.value.mode == "gps") TrackingService.stop(ctx) else Engine.stop()
                } else Engine.say("Şu an süren bir sürüş yok.")
            }
            has("devam", "başlat", "başla") && words.size <= 3 -> {
                if (!Engine.tracking) {
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
                    Nav.tab = 1
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
                        Nav.tab = 1
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
            Nav.tab = 1
            RouteSession.go(ctx, p)
        }
    }
}

/** Konuşmayı başlatan fonksiyonu verir (telefonun Google ses tanıması kullanılır). */
@Composable
fun rememberVoice(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said != null) Voice.handle(ctx, said)
    }
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Söyle: bitir, devam, ev, iş, kayıtlı yer ya da il adı")
        try {
            launcher.launch(i)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Telefonda ses tanıma bulunamadı (Google uygulaması gerekli).", Toast.LENGTH_LONG).show()
        }
    }
}
