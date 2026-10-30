package com.xantildev.koridor

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import org.osmdroid.config.Configuration
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                getSharedPreferences("hata", Context.MODE_PRIVATE).edit()
                    .putString("son", sw.toString().take(4000)).commit()
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
        VehicleStore.init(this)
        TripStore.init(this)
        CorridorStore.init(this)
        Speaker.init(this)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
    }
}

/** Türkçe sesli bildirim. Navigasyon sesi olarak çalar, Android Auto'da arabanın hoparlöründen gelir. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        t.language = Locale("tr", "TR")
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
    }

    fun say(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "k" + System.nanoTime())
    }
}
