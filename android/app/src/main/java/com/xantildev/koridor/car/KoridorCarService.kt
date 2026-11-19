package com.xantildev.koridor.car

import android.Manifest
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xantildev.koridor.CorridorStore
import com.xantildev.koridor.Directions
import com.xantildev.koridor.Engine
import com.xantildev.koridor.GMapHolder
import com.xantildev.koridor.Geo
import com.xantildev.koridor.LiveMapView
import com.xantildev.koridor.MapChoice
import com.xantildev.koridor.OurRadar
import com.xantildev.koridor.Places
import com.xantildev.koridor.R
import com.xantildev.koridor.RadarMap
import com.xantildev.koridor.RouteSession
import com.xantildev.koridor.TLog
import com.xantildev.koridor.TrackingService
import com.xantildev.koridor.Traffic
import com.xantildev.koridor.UiState
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Android Auto (navigasyon kategorisi). Telefondaki sürüş ekranının araç hâli:
 * tam ekran Google haritası (rota, EDS koridorları, bizim radar noktalarımız), dönüş kartı, varış tahmini,
 * köşede koridor ortalaması / limit / anlık hız ve ileri uyarılar; "Nereye?" listesi; radar / çevirme işaretleme.
 * Harita, araç ekranının yüzeyine sanal ekran üzerinden çizilir (telefondaki haritanın aynısı).
 */
class KoridorCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = KoridorSession()
}

class KoridorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val api = runCatching { carContext.carAppApiLevel }.getOrDefault(-1)
        TLog.i("auto", "Android Auto oturumu açıldı (araç API $api)")
        return NavCarScreen(carContext)
    }
}

/** Android Auto'daki hatanın nedeni loglara ve araç ekranına yazılsın. */
internal fun carError(where: String, e: Throwable): String {
    val st = android.util.Log.getStackTraceString(e)
    TLog.e("auto", "Android Auto hatası ($where): ${e.javaClass.simpleName}: ${e.message}",
        org.json.JSONObject().put("yigin", st.take(3500)))
    return "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)
}

private fun icon(ctx: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

/**
 * Araç ekranındaki harita: sanal bir ekrana telefondaki Google haritası (aynı rota/EDS/radar çizimleri)
 * ve üstüne koridor ortalaması paneli konur.
 */
class CarMap(private val car: CarContext) : SurfaceCallback {
    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    var holder: LiveMapView? = null
        private set
    private var overlay: CarOverlay? = null
    private var width = 0
    var error: String? = null
        private set
    /** Kullanıcı haritayı kaydırınca araç takibi durur; ◎ ile geri gelir. */
    var follow = true
    /** Haritaya dokunulan simgeler (sabit düğmeler): radar, çevirme, yakınlaştır, uzaklaştır, aracıma dön. */
    var onButton: (String) -> Unit = {}

    override fun onSurfaceAvailable(sc: SurfaceContainer) {
        try {
            release()
            val surface = sc.surface ?: return
            width = sc.width
            val dm = car.getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("koridor-auto", sc.width, sc.height, sc.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            vd = d
            val p = Presentation(car, d.display)
            val h = MapChoice.create(p.context)
            h.navMode = true
            val ov = CarOverlay(p.context)
            val root = FrameLayout(p.context)
            root.addView(h.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            p.setContentView(root)
            p.show()
            h.resume()
            pres = p; holder = h; overlay = ov
            error = null
            TLog.i("auto", "Araç haritası açıldı ${sc.width}×${sc.height} (${sc.dpi} dpi, ${h.javaClass.simpleName})")
            update(Engine.state.value)
        } catch (e: Throwable) {
            error = carError("harita", e)
        }
    }

    override fun onSurfaceDestroyed(sc: SurfaceContainer) = release()

    private fun release() {
        runCatching { holder?.pause() }
        runCatching { pres?.dismiss() }
        runCatching { vd?.release() }
        holder = null; pres = null; vd = null; overlay = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Şablonun kartları (dönüş kartı, düğmeler) haritanın bir kısmını kapatır: araç, görünen alanın ortasında kalsın
        val h = holder
        h?.rightInsetPx = (width - visibleArea.right).coerceAtLeast(0)
        (h as? GMapHolder)?.leftInsetPx = visibleArea.left.coerceAtLeast(0)
        overlay?.area = visibleArea
        overlay?.invalidate()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    fun update(s: UiState) {
        try {
            (holder as? GMapHolder)?.nightOverride = car.isDarkMode
            overlay?.follow = follow
            holder?.update(s, CorridorStore.version.value, follow)
            overlay?.let { it.state = s; it.invalidate() }
        } catch (e: Throwable) {
            TLog.throttled("auto-guncelle", 60_000) { carError("harita güncelleme", e) }
        }
    }

    fun zoomIn() = holder?.zoomIn()
    fun zoomOut() = holder?.zoomOut()
    fun recenter() { follow = true; holder?.recenter(Engine.state.value); update(Engine.state.value) }

    // Araç ekranındaki dokunmalar (Android Auto haritaya kaydırma / yakınlaştırma / dokunma iletir)
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val h = holder as? GMapHolder ?: return
        follow = false
        h.panBy(distanceX, distanceY)
        overlay?.let { it.follow = false; it.invalidate() }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {}

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val h = holder as? GMapHolder ?: return
        if (scaleFactor == 2f && focusX < 0) { zoomIn(); return }   // çift dokunma
        follow = false
        h.scaleBy(scaleFactor, focusX, focusY)
    }

    override fun onClick(x: Float, y: Float) {
        overlay?.hit(x, y)?.let { onButton(it) }
    }
}

/** Haritanın görünen alanının sol altında: koridor ortalaması (renkli), limit levhası, anlık hız ve ileri uyarı. */
class CarOverlay(ctx: Context) : View(ctx) {
    var state: UiState? = null
    var area: Rect? = null
    var follow = true
    /** Sabit düğmeler (Android Auto'nun kendi düğmeleri birkaç saniye sonra gizlenir; bunlar hep görünür). */
    private val buttons = LinkedHashMap<String, RectF>()

    fun hit(x: Float, y: Float): String? = buttons.entries.firstOrNull { it.value.contains(x, y) }?.key
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = state ?: return
        val a = area ?: Rect(0, 0, width, height)
        val corridor = s.corridor
        val inC = s.confirmed && corridor != null
        val lim = if (inC) corridor?.limit else null
        val big = if (inC) s.avg else s.tripAvg
        val col = when {
            big == null -> 0xFF8C9A97.toInt()
            lim == null -> 0xFFE9EEEC.toInt()
            big > lim * 1.1 -> 0xFFFF5A4F.toInt()
            big > lim -> 0xFFF2B632.toInt()
            else -> 0xFF2FBF71.toInt()
        }
        val warn = OurRadar.aheadText ?: RadarMap.currentText ?: Traffic.ahead ?: s.approach
        val w = 190 * dp
        val h = (if (warn != null) 128 else 96) * dp
        val left = a.left + 10 * dp
        val top = a.bottom - h - 10 * dp
        p.color = 0xE60D1214.toInt()
        c.drawRoundRect(RectF(left, top, left + w, top + h), 16 * dp, 16 * dp, p)
        t.color = 0xFF8C9A97.toInt(); t.textSize = 11 * dp; t.typeface = Typeface.DEFAULT_BOLD
        c.drawText(if (inC) "KORİDOR ORTALAMASI" else "YOLCULUK ORTALAMASI", left + 12 * dp, top + 20 * dp, t)
        t.color = col; t.textSize = 44 * dp
        c.drawText(big?.roundToInt()?.toString() ?: "—", left + 12 * dp, top + 66 * dp, t)
        t.color = 0xFFE9EEEC.toInt(); t.textSize = 13 * dp; t.typeface = Typeface.DEFAULT
        c.drawText("Anlık ${s.speedKmh ?: "—"} km/s" + if (inC) " · kalan ${Geo.fmtKm(s.remaining)}" else "", left + 12 * dp, top + 86 * dp, t)
        if (lim != null) {
            val r = 22 * dp; val cx = left + w - r - 12 * dp; val cy = top + 44 * dp
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5 * dp; p.color = 0xFFD62B2B.toInt(); c.drawCircle(cx, cy, r - 2.5f * dp, p)
            p.style = Paint.Style.FILL
            t.color = 0xFF111111.toInt(); t.textSize = 18 * dp; t.typeface = Typeface.DEFAULT_BOLD; t.textAlign = Paint.Align.CENTER
            c.drawText("$lim", cx, cy + 6.5f * dp, t)
            t.textAlign = Paint.Align.LEFT
        }
        // Panelin üstünde sabit düğmeler: 📷 radar, 👮 çevirme, + , − , (takip durduysa) ◎
        buttons.clear()
        val keys = ArrayList<Pair<String, String>>()
        if (OurRadar.enabled && s.driving) { keys.add("radar" to "📷"); keys.add("cevirme" to "👮") }
        keys.add("plus" to "+"); keys.add("minus" to "−")
        if (!follow) keys.add("center" to "◎")
        val bs = 44 * dp; val gap = 8 * dp
        var bx = left
        val by = top - bs - 8 * dp
        for ((k, label) in keys) {
            val r = RectF(bx, by, bx + bs, by + bs)
            p.color = if (k == "radar") 0xF07F1D1D.toInt() else if (k == "cevirme") 0xF01E3A8A.toInt() else 0xE60D1214.toInt()
            c.drawRoundRect(r, 12 * dp, 12 * dp, p)
            t.color = 0xFFFFFFFF.toInt(); t.textSize = if (label.length > 1 || k == "radar" || k == "cevirme") 22 * dp else 28 * dp
            t.typeface = Typeface.DEFAULT_BOLD; t.textAlign = Paint.Align.CENTER
            val fm = t.fontMetrics
            c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, t)
            t.textAlign = Paint.Align.LEFT
            buttons[k] = RectF(r.left - 4 * dp, r.top - 4 * dp, r.right + 4 * dp, r.bottom + 4 * dp)
            bx += bs + gap
        }
        if (warn != null) {
            t.color = 0xFFF2B632.toInt(); t.textSize = 13 * dp; t.typeface = Typeface.DEFAULT_BOLD
            var txt: String = warn
            while (txt.length > 6 && t.measureText(txt) > w - 24 * dp) txt = txt.dropLast(2)
            if (txt != warn) txt = txt.dropLast(1) + "…"
            c.drawText(txt, left + 12 * dp, top + 114 * dp, t)
        }
    }
}

class NavCarScreen(ctx: CarContext) : Screen(ctx) {
    private val map = CarMap(ctx)
    private var lastKey = ""
    private var initError: String? = null
    private var navStarted = false
    private var startWhenReady: Places.Place? = null
    private val nav: NavigationManager? = runCatching { ctx.getCarService(NavigationManager::class.java) }.getOrNull()

    init {
        map.onButton = { k ->
            when (k) {
                "radar" -> CarToast.makeText(carContext, OurRadar.mark("radar"), CarToast.LENGTH_SHORT).show()
                "cevirme" -> CarToast.makeText(carContext, OurRadar.mark("cevirme"), CarToast.LENGTH_SHORT).show()
                "plus" -> map.zoomIn()
                "minus" -> map.zoomOut()
                "center" -> { map.recenter(); invalidate() }
            }
        }
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)
        } catch (e: Throwable) {
            initError = carError("yüzey", e)
        }
        runCatching {
            nav?.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    // Araçtan (ör. başka navigasyon açılınca) rota bırakıldı
                    RouteSession.clear()
                    navStarted = false
                    invalidate()
                }
            })
        }.onFailure { carError("navigasyon yöneticisi", it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Engine.state.collect { s ->
                    map.update(s)
                    // Araçtan hedef seçildiyse rota gelince önizlemeyi atla, hemen başlat
                    startWhenReady?.let { p ->
                        val inf = RouteSession.info
                        if (inf != null && inf.target == p) {
                            startWhenReady = null
                            RouteSession.startNav(carContext)
                        }
                    }
                    syncNavigation(s)
                    val key = templateKey(s)
                    if (key != lastKey) { lastKey = key; invalidate() }
                }
            }
        }
    }

    private fun syncNavigation(s: UiState) {
        val active = RouteSession.info != null && !RouteSession.previewing
        if (active && !navStarted) runCatching { nav?.navigationStarted(); navStarted = true }
        if (!active && navStarted) runCatching { nav?.navigationEnded(); navStarted = false }
    }

    /** Şablon yalnız gerektiğinde yenilenir (araç ekranı sık yenilemeyi kısıtlar). */
    private fun templateKey(s: UiState): String {
        val n = Directions.next
        val inf = RouteSession.info
        val bucket = n?.let { if (it.distM > 1000) (it.distM / 500).toInt() else (it.distM / 50).toInt() }
        return "${map.follow}|${s.driving}|${inf?.target?.name}|${RouteSession.previewing}|${n?.step?.text}|$bucket|${s.arrived != null}|${map.error}|${(System.currentTimeMillis() / 60_000)}"
    }

    override fun onGetTemplate(): Template = try {
        initError?.let { error(it) }
        buildTemplate()
    } catch (e: Throwable) {
        val msg = carError("ekran", e)
        androidx.car.app.model.MessageTemplate.Builder("Koridor Ortalama açılamadı:\n$msg").setTitle("Koridor Ortalama").build()
    }

    private fun buildTemplate(): Template {
        val s = Engine.state.value
        val inf = RouteSession.info
        // Navigasyon şablonunda yazılı (başlıklı) düğme en fazla 1 tane; diğerleri simge
        val strip = ActionStrip.Builder()
        if (s.driving) {
            strip.addAction(Action.Builder().setTitle(if (s.arrived != null) "Vardım · Bitir" else "Bitir")
                .setOnClickListener { TrackingService.end(carContext); RouteSession.clear() }.build())
            if (OurRadar.enabled) {
                strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_radar))
                    .setOnClickListener { CarToast.makeText(carContext, OurRadar.mark("radar"), CarToast.LENGTH_SHORT).show() }.build())
                strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_police))
                    .setOnClickListener { CarToast.makeText(carContext, OurRadar.mark("cevirme"), CarToast.LENGTH_SHORT).show() }.build())
            }
            strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_flag))
                .setOnClickListener { screenManager.push(DestinationScreen(carContext) { pick(it) }) }.build())
        } else {
            strip.addAction(Action.Builder().setTitle("Nereye?")
                .setOnClickListener { screenManager.push(DestinationScreen(carContext) { pick(it) }) }.build())
        }
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)   // kaydırma modu: araç haritayı parmakla kaydırmaya izin verir
            .addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_plus)).setOnClickListener { map.zoomIn() }.build())
            .addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_minus)).setOnClickListener { map.zoomOut() }.build())
            .addAction(Action.Builder().setIcon(icon(carContext, R.drawable.car_recenter)).setOnClickListener { map.recenter() }.build())
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(strip.build()).setMapActionStrip(mapStrip)
            .setPanModeListener { panning -> if (panning) map.follow = false }

        val n = Directions.next
        val pos = s.pos
        when {
            map.error != null -> b.setNavigationInfo(MessageInfo.Builder("Harita açılamadı").setText(map.error!!).build())
            inf != null && RouteSession.previewing -> b.setNavigationInfo(MessageInfo.Builder(inf.target.name).setText("Rota hazırlanıyor…").build())
            inf != null && n != null -> {
                val cur = step(n.step)
                val ri = RoutingInfo.Builder().setCurrentStep(cur, dist(n.distM))
                n.after?.let { a -> if (n.afterGapM != null && n.afterGapM < 300) ri.setNextStep(step(a)) }
                b.setNavigationInfo(ri.build())
            }
            inf != null -> b.setNavigationInfo(MessageInfo.Builder(inf.target.name).setText("Rotayı takip et").build())
            else -> b.setNavigationInfo(MessageInfo.Builder(if (s.driving) "Hedefsiz sürüş" else "Koridor Ortalama")
                .setText(if (s.driving) "EDS koridorları ve radarlar haritada" else "Hedef seçmek için “Nereye?”").build())
        }
        if (inf != null && pos != null && !RouteSession.previewing) {
            val rem = (inf.totalM - inf.project(pos).first).coerceAtLeast(0.0)
            val secs = inf.etaS(rem).toLong().coerceAtLeast(0)
            b.setDestinationTravelEstimate(TravelEstimate.Builder(dist(rem), ZonedDateTime.now().plusSeconds(secs))
                .setRemainingTimeSeconds(secs).build())
        }
        return b.build()
    }

    private fun pick(p: Places.Place) {
        startWhenReady = p
        if (!Engine.state.value.driving && ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CarToast.makeText(carContext, "Önce telefonda konum iznini ver", CarToast.LENGTH_LONG).show()
            return
        }
        RouteSession.go(carContext, p)
        TLog.i("auto", "Araçtan hedef seçildi", org.json.JSONObject().put("hedef", p.name))
        invalidate()
    }

    private fun dist(m: Double): Distance = when {
        m >= 10_000 -> Distance.create((m / 1000).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
        m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create(((m / 50).roundToInt() * 50).toDouble(), Distance.UNIT_METERS)
        else -> Distance.create((((m / 10).roundToInt() * 10).coerceAtLeast(10)).toDouble(), Distance.UNIT_METERS)
    }

    private fun step(st: Directions.Step): Step {
        val (type, res) = when (st.code) {
            "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT to R.drawable.turn_left
            "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.turn_right
            "slight_left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.turn_slight_left
            "slight_right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.turn_slight_right
            "sharp_left" -> Maneuver.TYPE_TURN_SHARP_LEFT to R.drawable.turn_left
            "sharp_right" -> Maneuver.TYPE_TURN_SHARP_RIGHT to R.drawable.turn_right
            "uturn" -> Maneuver.TYPE_U_TURN_LEFT to R.drawable.turn_uturn
            "roundabout" -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW to R.drawable.turn_roundabout
            "merge" -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED to R.drawable.turn_straight
            "ramp_left" -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT to R.drawable.turn_slight_left
            "ramp_right" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT to R.drawable.turn_slight_right
            "fork_left" -> Maneuver.TYPE_FORK_LEFT to R.drawable.turn_slight_left
            "fork_right" -> Maneuver.TYPE_FORK_RIGHT to R.drawable.turn_slight_right
            "arrive" -> Maneuver.TYPE_DESTINATION to R.drawable.car_flag
            else -> Maneuver.TYPE_STRAIGHT to R.drawable.turn_straight
        }
        return Step.Builder(st.text.ifBlank { "Devam edin" })
            .setManeuver(Maneuver.Builder(type).setIcon(icon(carContext, res)).build())
            .build()
    }
}

/** "Nereye?": Ev, İş ve kayıtlı yerler (araçta yazı yazmak kısıtlı olduğu için liste). */
class DestinationScreen(ctx: CarContext, private val onPick: (Places.Place) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template = try {
        val items = ItemList.Builder()
        val here = Engine.state.value.pos
        fun sub(p: Places.Place) = here?.let { "${Geo.fmtKm(Geo.dist(it, p.ll))} · ${p.name}" } ?: p.name
        var n = 0
        Places.loadSlot(carContext, "ev")?.let { p ->
            items.addItem(Row.Builder().setTitle("Ev").addText(sub(p)).setImage(icon(carContext, R.drawable.car_home))
                .setOnClickListener { onPick(p); screenManager.pop() }.build()); n++
        }
        Places.loadSlot(carContext, "is")?.let { p ->
            items.addItem(Row.Builder().setTitle("İş").addText(sub(p)).setImage(icon(carContext, R.drawable.car_work))
                .setOnClickListener { onPick(p); screenManager.pop() }.build()); n++
        }
        for (p in Places.savedList(carContext)) {
            if (n >= 6) break
            items.addItem(Row.Builder().setTitle(p.name).addText(here?.let { Geo.fmtKm(Geo.dist(it, p.ll)) } ?: "Kayıtlı yer")
                .setImage(icon(carContext, R.drawable.car_place)).setOnClickListener { onPick(p); screenManager.pop() }.build()); n++
        }
        if (n == 0) items.setNoItemsMessage("Kayıtlı yer yok. Telefonda Ev/İş kaydet ya da yer ekle.")
        ListTemplate.Builder().setTitle("Nereye?").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    } catch (e: Throwable) {
        androidx.car.app.model.MessageTemplate.Builder("Liste açılamadı:\n${carError("hedef listesi", e)}").setTitle("Nereye?")
            .setHeaderAction(Action.BACK).build()
    }
}
