package com.xantildev.koridor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.LatLng as GLatLng

/** Google haritası: konum, geçilen iz, rota ve koridorlar (osmdroid sürümünün aynısı). */
class GMapHolder(ctx: Context) : LiveMapView {
    private val mv = MapView(ctx, GoogleMapOptions().compassEnabled(true).mapToolbarEnabled(false).zoomControlsEnabled(false).rotateGesturesEnabled(false))
    override val view: View get() = mv
    override var onUserPan: () -> Unit = {}

    private var gm: GoogleMap? = null
    private var last: Triple<UiState, Int, Boolean>? = null
    private var drawnKey = -1
    private var drawnRoute: RouteSession.Info? = null
    private var fittedTarget: Places.Place? = null
    private val altLines = ArrayList<Polyline>()
    private var drawnAlts: Any? = null
    private var drawnAltIndex = -1

    /** Seçilmeyen rota seçenekleri gri; dokununca o rota seçilir. */
    private fun drawAlts(m: GoogleMap) {
        val alts = RouteSession.shownAlts
        if (alts === drawnAlts && RouteSession.altIndex == drawnAltIndex) return
        drawnAlts = alts; drawnAltIndex = RouteSession.altIndex
        altLines.forEach { it.remove() }
        altLines.clear()
        alts.forEachIndexed { i, a ->
            if (i == RouteSession.altIndex) return@forEachIndexed
            val pl = m.addPolyline(PolylineOptions().addAll(a.line.map { g(it) }).color(0xCC8A96A3.toInt()).width(9 * density / 1.5f).zIndex(0.5f).clickable(true))
            pl.tag = i
            altLines.add(pl)
        }
    }
    private val corrObjs = ArrayList<Any>()
    private var routeLine: Polyline? = null
    private var startM: Marker? = null
    private var endM: Marker? = null
    private var trail: Polyline? = null
    private var you: Marker? = null
    private var hasFix = false
    private var routeEdge: Polyline? = null
    override var onLongPress: (LatLng) -> Unit = {}
    private var pin: Marker? = null
    private var pinAt: LatLng? = null
    override var navMode = false
    override var rightInsetPx = 0
    private val heading = HeadingTracker()
    private var arrowOn = false
    private var padKey = ""
    private var lastCam: GLatLng? = null
    private var lastH: Float? = null
    private var lastTrackSize = -1
    private var night: Boolean? = null
    private val density = ctx.resources.displayMetrics.density

    init {
        mv.onCreate(null)
        mv.getMapAsync { m ->
            gm = m
            m.uiSettings.isZoomControlsEnabled = false
            m.uiSettings.isMapToolbarEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(GLatLng(39.0, 35.0), 6f))
            m.setOnCameraMoveStartedListener { r -> if (r == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) onUserPan() }
            m.setOnPolylineClickListener { pl -> (pl.tag as? Int)?.let { RouteSession.selectAlt(it) } }
            m.setOnMapLongClickListener { ll -> onLongPress(LatLng(ll.latitude, ll.longitude)) }
            pinAt?.let { setPin(it) }
            last?.let { (s, v, f) -> update(s, v, f) }
        }
        mv.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
    }

    private fun g(p: LatLng) = GLatLng(p.lat, p.lon)

    override fun setPin(p: LatLng?) {
        pinAt = p
        val m = gm ?: return
        pin?.remove(); pin = null
        if (p != null) pin = m.addMarker(MarkerOptions().position(g(p)).zIndex(30f))
    }

    private fun dot(fill: Int, stroke: Int, dp: Int): BitmapDescriptor {
        val px = (dp * density).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = stroke; c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        p.color = fill; c.drawCircle(px / 2f, px / 2f, px / 2f - px / 6f, p)
        return BitmapDescriptorFactory.fromBitmap(b)
    }

    override fun update(s: UiState, ver: Int, follow: Boolean) {
        last = Triple(s, ver, follow)
        val m = gm ?: return
        val n = AppSettings.isNight()
        if (n != night) {
            night = n
            runCatching { m.setMapStyle(if (n) MapStyleOptions(DARK) else null) }
        }
        val sim = s.mode == "sim"
        val key = (ver * 2 + if (sim) 1 else 0) * 31 + System.identityHashCode(RouteSession.info)
        if (key != drawnKey) {
            drawnKey = key
            drawCorridors(m, sim)
        }
        drawAlts(m)
        val ri = RouteSession.info
        if (ri !== drawnRoute) {
            drawnRoute = ri
            routeLine?.remove(); routeEdge?.remove(); startM?.remove(); endM?.remove()
            routeLine = null; routeEdge = null; startM = null; endM = null
            if (ri != null && ri.line.size >= 2) {
                // Google Haritalar gibi: koyu kenarlı mavi rota; EDS koridorları rotanın etrafında kırmızı kenar
                val pts = ri.line.map { g(it) }
                routeEdge = m.addPolyline(PolylineOptions().addAll(pts).color(0xFF0B57D0.toInt()).width(12 * density).zIndex(1f)
                    .startCap(com.google.android.gms.maps.model.RoundCap()).endCap(com.google.android.gms.maps.model.RoundCap())
                    .jointType(com.google.android.gms.maps.model.JointType.ROUND))
                routeLine = m.addPolyline(PolylineOptions().addAll(pts).color(0xFF4A8CFF.toInt()).width(8 * density).zIndex(1.2f)
                    .startCap(com.google.android.gms.maps.model.RoundCap()).endCap(com.google.android.gms.maps.model.RoundCap())
                    .jointType(com.google.android.gms.maps.model.JointType.ROUND))
                startM = m.addMarker(MarkerOptions().position(g(ri.start)).icon(dot(0xFF2FBF71.toInt(), 0xFFFFFFFF.toInt(), 16)).anchor(0.5f, 0.5f).title("Başlangıç").snippet(ri.startLabel))
                endM = m.addMarker(MarkerOptions().position(g(ri.target.ll)).icon(dot(0xFF4DA3FF.toInt(), 0xFFFFFFFF.toInt(), 18)).anchor(0.5f, 0.5f).title("Hedef: " + ri.target.name).snippet(ri.targetLabel))
                val bb = LatLngBounds.builder()
                ri.line.forEach { bb.include(g(it)) }
                if (fittedTarget != ri.target) { fittedTarget = ri.target; fit(m, bb.build(), 0) }
            }
        }
        if (s.track.size != lastTrackSize) {
            lastTrackSize = s.track.size
            val pts = s.track.map { g(it) }
            if (trail == null) trail = m.addPolyline(PolylineOptions().color(0x99F2B632.toInt()).width(4 * density).zIndex(3f))
            trail?.points = pts
        }
        val p = s.pos
        if (p != null) {
            val gp = g(p)
            val h = heading.feed(p, s.speedKmh)
            val nav = navMode && AppSettings.headingUp && s.driving
            val y = you ?: m.addMarker(MarkerOptions().position(gp).icon(dot(0xFFF2B632.toInt(), 0xFFFFFFFF.toInt(), 18)).anchor(0.5f, 0.5f).zIndex(5f)).also { you = it }
            y?.position = gp
            if (h != null && !arrowOn) {
                arrowOn = true
                y?.setIcon(BitmapDescriptorFactory.fromBitmap(arrowBitmap(density)))
                y?.isFlat = true
            }
            if (h != null) y?.rotation = h
            // Navigasyon görünümü: araç ekranın altına yakın, sağ panelin altında kalmasın
            val pk = "$nav/$rightInsetPx/${mv.height}"
            if (pk != padKey) {
                padKey = pk
                m.setPadding(0, if (nav) (mv.height * 0.4f).toInt() else 0, rightInsetPx, 0)
            }
            if (!hasFix) {
                hasFix = true
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(gp, if (nav) NavZoom.of(s.speedKmh) else 17f))
            } else if (follow && nav && (gp != lastCam || h != lastH)) {
                lastCam = gp; lastH = h
                val z = NavZoom.of(s.speedKmh)
                m.animateCamera(CameraUpdateFactory.newCameraPosition(
                    com.google.android.gms.maps.model.CameraPosition.Builder().target(gp).zoom(z).bearing(h ?: m.cameraPosition.bearing).tilt(45f).build()
                ), 900, null)
            } else if (follow) {
                if (m.cameraPosition.bearing != 0f || m.cameraPosition.tilt != 0f) {
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(
                        com.google.android.gms.maps.model.CameraPosition.Builder().target(gp).zoom(m.cameraPosition.zoom).bearing(0f).tilt(0f).build()))
                } else m.moveCamera(CameraUpdateFactory.newLatLng(gp))
            }
        } else {
            you?.remove(); you = null
            hasFix = false
        }
    }

    /** Harita boyutu belli olmadan sınırlara yakınlaştırma hata verir; boyut gelene kadar bekle. */
    private fun fit(m: GoogleMap, b: LatLngBounds, tries: Int) {
        if (mv.width > 0 && mv.height > 0) {
            runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, mv.width, mv.height, (60 * density).toInt())) }
        } else if (tries < 20) {
            mv.postDelayed({ fit(m, b, tries + 1) }, 150)
        }
    }

    private fun drawCorridors(m: GoogleMap, sim: Boolean) {
        corrObjs.forEach { o -> when (o) { is Polyline -> o.remove(); is Marker -> o.remove() } }
        corrObjs.clear()
        val ri = RouteSession.info
        val base = if (ri != null) {
            CorridorStore.all().filter { c ->
                ri.corridors.any { it.key == c.key } || (Geo.distToLine(c.from, ri.line) < 400 && Geo.distToLine(c.to, ri.line) < 400)
            }
        } else CorridorStore.all()
        val list = base + if (sim) listOf(CorridorStore.withOverride(Engine.DEMO)) else emptyList()
        val icon = dot(0xFFFFFFFF.toInt(), 0xFFD62B2B.toInt(), 14)
        for (c in list) {
            val sl = ri?.slice(c.from, c.to)
            if (sl != null) {
                corrObjs.add(m.addPolyline(PolylineOptions().addAll(sl.map { g(it) }).color(0xFFE5322D.toInt()).width(17 * density).zIndex(0.9f)
                    .jointType(com.google.android.gms.maps.model.JointType.ROUND)))
            } else if (c.path.size >= 2) {
                corrObjs.add(m.addPolyline(PolylineOptions().addAll(c.path.map { g(it) }).color(0xAAD62B2B.toInt()).width(7 * density / 1.5f).zIndex(2f)))
            }
            m.addMarker(
                MarkerOptions().position(g(c.from)).icon(icon).anchor(0.5f, 0.5f).title(c.name)
                    .snippet("Koridor başı · " + (c.limit?.let { "limit $it km/s" } ?: "limit bilinmiyor") + " · " + Geo.fmtKm(c.lengthM))
            )?.let { corrObjs.add(it) }
        }
    }

    override fun recenter(s: UiState) {
        val p = s.pos ?: return
        val m = gm ?: return
        val nav = navMode && AppSettings.headingUp && s.driving
        m.moveCamera(CameraUpdateFactory.newCameraPosition(
            com.google.android.gms.maps.model.CameraPosition.Builder().target(g(p)).zoom(if (nav) { NavZoom.bias = 0f; NavZoom.of(s.speedKmh) } else maxOf(m.cameraPosition.zoom, 17f))
                .bearing(if (nav) heading.deg ?: 0f else 0f).tilt(if (nav) 45f else 0f).build()))
    }

    override fun zoomIn() { if (navMode) NavZoom.bias += 1f; gm?.animateCamera(CameraUpdateFactory.zoomIn()) }
    override fun zoomOut() { if (navMode) NavZoom.bias -= 1f; gm?.animateCamera(CameraUpdateFactory.zoomOut()) }
    override fun resume() { mv.onStart(); mv.onResume() }
    override fun pause() { mv.onPause(); mv.onStop() }

    companion object {
        /** Gece görünümü (koyu harita). */
        private const val DARK = """[
{"elementType":"geometry","stylers":[{"color":"#1d2330"}]},
{"elementType":"labels.text.fill","stylers":[{"color":"#9aa6b2"}]},
{"elementType":"labels.text.stroke","stylers":[{"color":"#1d2330"}]},
{"featureType":"poi","elementType":"geometry","stylers":[{"color":"#232b38"}]},
{"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#1f3027"}]},
{"featureType":"road","elementType":"geometry","stylers":[{"color":"#38414e"}]},
{"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#212a37"}]},
{"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#56606e"}]},
{"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#d6dbe0"}]},
{"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2f3948"}]},
{"featureType":"water","elementType":"geometry","stylers":[{"color":"#0f1a2a"}]}
]"""
    }
}
