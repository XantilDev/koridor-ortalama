package com.xantildev.koridor.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xantildev.koridor.Engine
import com.xantildev.koridor.Geo
import com.xantildev.koridor.TrackingService
import com.xantildev.koridor.UiState
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Android Auto girişi (navigasyon kategorisi). Kendi kullanımın için: Auto'da "Bilinmeyen kaynaklar" açık olmalı. */
class KoridorCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = KoridorSession()
}

class KoridorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val api = runCatching { carContext.carAppApiLevel }.getOrDefault(-1)
        com.xantildev.koridor.TLog.i("auto", "Android Auto oturumu açıldı (araç API $api)")
        return MainCarScreen(carContext)
    }
}

/** Android Auto'daki hatanın nedeni loglara ve araç ekranına yazılsın. */
private fun carError(where: String, e: Throwable): String {
    val st = android.util.Log.getStackTraceString(e)
    com.xantildev.koridor.TLog.e("auto", "Android Auto hatası ($where): ${e.javaClass.simpleName}: ${e.message}",
        org.json.JSONObject().put("yigin", st.take(3500)))
    return "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)
}

/** Arabanın ekranına büyük ortalama göstergesi ve limit levhası çizer. */
class Gauge : SurfaceCallback {
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null

    private val bg = 0xFF0D1214.toInt()
    private val fg = 0xFFE9EEEC.toInt()
    private val muted = 0xFF8C9A97.toInt()
    private val ok = 0xFF2FBF71.toInt()
    private val warn = 0xFFF2B632.toInt()
    private val over = 0xFFFF5A4F.toInt()
    private val red = 0xFFD62B2B.toInt()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        draw(Engine.state.value)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        draw(Engine.state.value)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    fun draw(s: UiState) {
        val sc = container ?: return
        val surface = sc.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        } ?: return
        try {
            paint(canvas, s, visible ?: Rect(0, 0, sc.width, sc.height), sc.width, sc.height)
        } catch (_: Exception) {
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    private fun line(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, maxW: Float = Float.MAX_VALUE) {
        text.textSize = size
        text.color = color
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        var s = t
        while (s.length > 4 && text.measureText(s) > maxW) s = s.dropLast(2)
        if (s != t) s = s.dropLast(1) + "…"
        c.drawText(s, x, y, text)
    }

    private fun paint(c: Canvas, s: UiState, area: Rect, w: Int, h: Int) {
        c.drawColor(bg)
        val left = area.left + area.width() * 0.05f
        val right = area.right - area.width() * 0.04f
        val top = area.top.toFloat()
        val ah = area.height().toFloat()
        val unit = min(ah, area.width() * 0.6f)

        val corridor = s.corridor
        val inC = s.confirmed && corridor != null
        val lim = if (inC) corridor?.limit else null
        val big = if (inC) s.avg else s.tripAvg
        val col = when {
            big == null -> muted
            !inC || lim == null -> fg
            big > lim -> over
            big > lim * 0.95 -> warn
            else -> ok
        }

        // Limit levhası (sağda)
        val r = unit * 0.2f
        val cx = right - r
        val cy = top + ah * 0.38f
        if (inC) {
            shape.style = Paint.Style.FILL
            shape.color = if (lim != null) 0xFFFFFFFF.toInt() else 0xFF151C1F.toInt()
            c.drawCircle(cx, cy, r, shape)
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = r * 0.22f
            shape.color = if (lim != null) red else muted
            c.drawCircle(cx, cy, r - shape.strokeWidth / 2, shape)
            text.textAlign = Paint.Align.CENTER
            line(c, lim?.toString() ?: "?", cx, cy + r * 0.32f, r * 0.9f, if (lim != null) 0xFF111111.toInt() else muted, bold = true)
            text.textAlign = Paint.Align.LEFT
        }
        val textW = (if (inC) cx - r * 1.3f else right) - left

        // Başlık
        val label = when {
            s.mode == "bekle" -> "HAZIR · hareket edince sürüş başlar"
            s.mode == "idle" -> "TAKİP KAPALI · “Başlat”a dokun"
            inC -> "KORİDOR ORTALAMAN · KM/S"
            corridor != null -> "KORİDOR GİRİŞİ · yön doğrulanıyor"
            else -> "YOLCULUK ORTALAMAN · KM/S"
        }
        line(c, label, left, top + ah * 0.12f, unit * 0.06f, muted, maxW = textW)

        // Büyük sayı
        line(c, big?.roundToInt()?.toString() ?: "—", left, top + ah * 0.55f, unit * 0.42f, col, bold = true, maxW = textW)

        // Alt satırlar
        val l1: String
        val l2: String
        if (inC && corridor != null) {
            l1 = corridor.name
            l2 = "Kalan ${Geo.fmtKm(s.remaining)} · pay ${s.slack?.let { Geo.fmtSlack(it) } ?: "—"} · EDS içi bekleme ${Geo.fmtT(s.corridorStop)}"
        } else {
            l1 = "Anlık ${s.speedKmh ?: "—"} km/s · ${Geo.fmtKm(s.trip.dist)} · ${Geo.fmtT(s.trip.time)}"
            l2 = "EDS dışı bekleme ${Geo.fmtT(s.trip.stopOut)} · EDS içi ${Geo.fmtT(s.trip.stopIn)}"
        }
        line(c, l1, left, top + ah * 0.70f, unit * 0.075f, fg, bold = true, maxW = right - left)
        line(c, l2, left, top + ah * 0.80f, unit * 0.06f, muted, maxW = right - left)
        val note = s.arrived?.let { "Hedefe vardın: $it · bitirmek için “Bitir”" } ?: s.approach ?: s.note
        if (note != null) line(c, note, left, top + ah * 0.92f, unit * 0.06f, warn, maxW = right - left)
    }
}

class MainCarScreen(ctx: CarContext) : Screen(ctx) {
    private val gauge = Gauge()
    private var lastKey = ""

    private var initError: String? = null

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(gauge)
        } catch (e: Throwable) {
            initError = carError("yüzey", e)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Engine.state.collect { s ->
                    gauge.draw(s)
                    val key = "${s.mode}|${s.markActive}|${s.arrived != null}"
                    if (key != lastKey) {
                        lastKey = key
                        invalidate()
                    }
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
        } catch (_: Exception) {
            CarToast.makeText(carContext, "Takibi telefondan başlat", CarToast.LENGTH_LONG).show()
        }
    }

    override fun onGetTemplate(): Template = try {
        initError?.let { error(it) }
        buildTemplate()
    } catch (e: Throwable) {
        val msg = carError("ekran", e)
        // Hata olursa bile araçta boş ekran yerine nedenini göster
        androidx.car.app.model.MessageTemplate.Builder("Koridor Ortalama açılamadı:\n$msg")
            .setTitle("Koridor Ortalama").build()
    }

    private fun buildTemplate(): Template {
        val s = Engine.state.value
        // Navigasyon şablonunda yazılı (başlıklı) düğme en fazla 1 tane olabilir; fazlası şablonu reddettirir
        val strip = ActionStrip.Builder()
        if (!s.driving) {
            strip.addAction(Action.Builder().setTitle("Başlat").setOnClickListener { startTracking() }.build())
        } else {
            strip.addAction(Action.Builder().setTitle(if (s.arrived != null) "Vardım · Bitir" else "Bitir").setOnClickListener { TrackingService.end(carContext) }.build())
        }
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .build()
    }
}
