package com.xantildev.koridor.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xantildev.koridor.Engine
import com.xantildev.koridor.Geo
import com.xantildev.koridor.TrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Android Auto girişi. Kendi kullanımın için: Auto ayarlarında "Bilinmeyen kaynaklar" açık olmalı. */
class KoridorCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = KoridorSession()
}

class KoridorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MainCarScreen(carContext)
}

/**
 * Arabanın ekranı. Satır başlıkları sabit tutulur, yalnızca metinler değişir;
 * böylece Android Auto güncellemeleri "yenileme" sayar ve şablon sınırına takılmaz.
 */
class MainCarScreen(ctx: CarContext) : Screen(ctx) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var seen = -1L
                while (true) {
                    val t = Engine.state.value.tick
                    if (t != seen) {
                        seen = t
                        invalidate()
                    }
                    delay(2000)
                }
            }
        }
    }

    private fun startTracking() {
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            CarToast.makeText(carContext, "Önce telefonda konum iznini ver", CarToast.LENGTH_LONG).show()
            return
        }
        try {
            TrackingService.start(carContext)
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Takibi telefondan başlat", CarToast.LENGTH_LONG).show()
        }
    }

    override fun onGetTemplate(): Template {
        val s = Engine.state.value
        val c = s.corridor
        val inC = s.confirmed && c != null

        val avgText = when {
            s.mode == "idle" -> "Takip kapalı"
            inC -> "${s.avg?.roundToInt() ?: "—"} km/s koridor ortalaması · limit ${c?.limit ?: "?"}"
            else -> "${s.tripAvg?.roundToInt() ?: "—"} km/s yolculuk ortalaması · anlık ${s.speedKmh ?: "—"}"
        }
        val corrText = when {
            s.mode == "idle" -> "Başlatınca koridorlar otomatik tanınır"
            inC && c != null -> "Kalan ${Geo.fmtKm(s.remaining)} · pay ${s.slack?.let { Geo.fmtSlack(it) } ?: "—"} · ${c.name}"
            c != null -> "Girişte, yön doğrulanıyor · ${c.name}"
            else -> "Koridor dışında · ${Geo.fmtKm(s.trip.dist)} · ${Geo.fmtT(s.trip.time)}"
        }
        val stateText = s.approach ?: s.note ?: s.markText

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Ortalama hız").addText(avgText).build())
            .addRow(Row.Builder().setTitle("Koridor").addText(corrText).build())
            .addRow(Row.Builder().setTitle("Durum").addText(stateText).build())

        if (s.mode == "idle") {
            pane.addAction(
                Action.Builder().setTitle("Takibi başlat").setBackgroundColor(CarColor.YELLOW)
                    .setOnClickListener { startTracking() }.build()
            )
        } else if (s.markActive) {
            pane.addAction(
                Action.Builder().setTitle("Koridor sonu").setBackgroundColor(CarColor.YELLOW)
                    .setOnClickListener {
                        Engine.markEnd()
                        val pm = Engine.state.value.pendingMark
                        if (pm != null) Engine.saveMark(pm.limit, null)
                    }.build()
            )
        } else {
            pane.addAction(
                Action.Builder().setTitle("Koridor başı")
                    .setOnClickListener { Engine.markStart() }.build()
            )
        }
        pane.addAction(
            Action.Builder().setTitle("Durdur")
                .setOnClickListener { TrackingService.stop(carContext) }.build()
        )

        return PaneTemplate.Builder(pane.build())
            .setTitle("Koridor Ortalama")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
