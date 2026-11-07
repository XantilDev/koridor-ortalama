package com.xantildev.koridor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

/** osmdroid haritası: konum, geçilen iz ve koridorlar. */
@SuppressLint("ClickableViewAccessibility")
class MapHolder(ctx: Context) : LiveMapView {
    val map = MapView(ctx)
    override val view: android.view.View get() = map
    override var onUserPan: () -> Unit = {}
    override fun zoomIn() { map.controller.zoomIn() }
    override fun zoomOut() { map.controller.zoomOut() }
    override fun resume() { map.onResume() }
    override fun pause() { map.onPause() }

    private val trail = Polyline(map)
    private val routeLine = Polyline(map)
    private val routeEdge = Polyline(map)
    private val corrUnder = FolderOverlay()
    private var drawnRoute: RouteSession.Info? = null
    private var fittedTarget: Places.Place? = null
    private val altLines = ArrayList<Polyline>()
    private var drawnAlts: Any? = null
    private var drawnAltIndex = -1

    /** Seçilmeyen rota seçenekleri gri; dokununca o rota seçilir. */
    private fun drawAlts() {
        val alts = RouteSession.shownAlts
        if (alts === drawnAlts && RouteSession.altIndex == drawnAltIndex) return
        drawnAlts = alts; drawnAltIndex = RouteSession.altIndex
        altLines.forEach { map.overlays.remove(it) }
        altLines.clear()
        alts.forEachIndexed { i, a ->
            if (i == RouteSession.altIndex) return@forEachIndexed
            val pl = Polyline(map)
            pl.setPoints(a.line.map { GeoPoint(it.lat, it.lon) })
            pl.outlinePaint.color = 0xCC8A96A3.toInt()
            pl.outlinePaint.strokeWidth = 12f
            pl.setOnClickListener { _, _, _ -> RouteSession.selectAlt(i); true }
            altLines.add(pl)
            map.overlays.add(0, pl)
        }
    }
    private val startMark = Marker(map)
    private val endMark = Marker(map)
    private val you = Marker(map)
    private val corr = FolderOverlay()
    private var drawnKey = -1
    private var hasFix = false
    override var navMode = false
    override var rightInsetPx = 0
    private val heading = HeadingTracker()
    private var arrowOn = false
    private var lastTrackSize = -1

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(39.0, 35.0))
        trail.outlinePaint.color = 0x99F2B632.toInt()
        trail.outlinePaint.strokeWidth = 8f
        you.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        you.icon = dot(0xFFF2B632.toInt(), Color.WHITE, 44)
        you.setInfoWindow(null)
        val dn = ctx.resources.displayMetrics.density
        routeEdge.outlinePaint.color = 0xFF0B57D0.toInt()
        routeEdge.outlinePaint.strokeWidth = 12 * dn
        routeEdge.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        routeEdge.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        routeLine.outlinePaint.color = 0xFF4A8CFF.toInt()
        routeLine.outlinePaint.strokeWidth = 8 * dn
        routeLine.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        routeLine.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        map.overlays.add(corrUnder)
        map.overlays.add(routeEdge)
        map.overlays.add(routeLine)
        map.overlays.add(corr)
        map.overlays.add(trail)
        map.setOnTouchListener { v, ev ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (ev.action == MotionEvent.ACTION_MOVE) onUserPan()
            false
        }
    }

    private fun dot(fill: Int, stroke: Int, px: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(px / 6, stroke)
        setSize(px, px)
    }

    override fun update(s: UiState, ver: Int, follow: Boolean) {
        val sim = s.mode == "sim"
        val key = (ver * 2 + if (sim) 1 else 0) * 31 + System.identityHashCode(RouteSession.info)
        if (key != drawnKey) {
            drawnKey = key
            drawCorridors(sim)
        }
        drawAlts()
        val ri = RouteSession.info
        if (ri !== drawnRoute) {
            drawnRoute = ri
            val pts = ri?.line?.map { GeoPoint(it.lat, it.lon) } ?: emptyList()
            routeLine.setPoints(pts)
            routeEdge.setPoints(pts)
            map.overlays.remove(startMark)
            map.overlays.remove(endMark)
            if (ri != null && pts.size >= 2) {
                startMark.position = GeoPoint(ri.start.lat, ri.start.lon)
                startMark.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                startMark.icon = dot(0xFF2FBF71.toInt(), Color.WHITE, 40)
                startMark.title = "Başlangıç"
                startMark.snippet = ri.startLabel
                endMark.position = GeoPoint(ri.target.lat, ri.target.lon)
                endMark.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                endMark.icon = dot(0xFF4DA3FF.toInt(), Color.WHITE, 44)
                endMark.title = "Hedef: " + ri.target.name
                endMark.snippet = ri.targetLabel
                map.overlays.add(startMark)
                map.overlays.add(endMark)
                if (fittedTarget != ri.target) { fittedTarget = ri.target; fitWhenReady(org.osmdroid.util.BoundingBox.fromGeoPoints(pts), 0) }
            }
        }
        if (s.track.size != lastTrackSize) {
            lastTrackSize = s.track.size
            trail.setPoints(s.track.map { GeoPoint(it.lat, it.lon) })
        }
        val p = s.pos
        if (p != null) {
            val gp = GeoPoint(p.lat, p.lon)
            val h = heading.feed(p, s.speedKmh)
            val nav = navMode && AppSettings.headingUp && s.driving
            you.position = gp
            if (h != null && !arrowOn) {
                arrowOn = true
                you.icon = android.graphics.drawable.BitmapDrawable(map.resources, arrowBitmap(map.resources.displayMetrics.density))
                you.isFlat = true
            }
            if (h != null) you.rotation = -h
            if (!map.overlays.contains(you)) map.overlays.add(you)
            // Navigasyon: gidiş yönü yukarıda, araç ekranın alt kısmında ve sağ panelin solunda
            map.setMapCenterOffset(-rightInsetPx / 2, if (nav) (map.height * 0.2f).toInt() else 0)
            map.mapOrientation = if (nav) (h?.let { -it } ?: map.mapOrientation) else 0f
            if (!hasFix) {
                hasFix = true
                map.controller.setZoom(if (nav) 17.0 else 15.0)
                map.controller.setCenter(gp)
            } else if (follow) {
                map.controller.setCenter(gp)
            }
        } else {
            map.overlays.remove(you)
            hasFix = false
        }
        map.invalidate()
    }

    /** Harita ölçüsü belli olmadan yakınlaştırma yapılırsa osmdroid çöker; boyut gelene kadar bekle. */
    private fun fitWhenReady(box: org.osmdroid.util.BoundingBox, tries: Int) {
        if (map.width > 0 && map.height > 0) {
            try {
                map.zoomToBoundingBox(box, false, 80)
            } catch (_: Exception) {
            }
        } else if (tries < 20) {
            map.postDelayed({ fitWhenReady(box, tries + 1) }, 150)
        }
    }

    override fun recenter(s: UiState) {
        val p = s.pos ?: return
        map.controller.setZoom(max(map.zoomLevelDouble, 15.0))
        map.controller.setCenter(GeoPoint(p.lat, p.lon))
    }

    private fun drawCorridors(sim: Boolean) {
        corr.items.clear()
        corrUnder.items.clear()
        // Rota seçiliyse yalnızca rotadaki koridorlar; değilse hepsi (tanıma için saklananlar dahil)
        val ri = RouteSession.info
        val base = if (ri != null) {
            CorridorStore.all().filter { c ->
                ri.corridors.any { it.key == c.key } || (Geo.distToLine(c.from, ri.line) < 400 && Geo.distToLine(c.to, ri.line) < 400)
            }
        } else CorridorStore.all()
        val list = base + if (sim) listOf(CorridorStore.withOverride(Engine.DEMO)) else emptyList()
        val dn = map.resources.displayMetrics.density
        for (c in list) {
            val sl = ri?.slice(c.from, c.to)
            if (sl != null) {
                val pl = Polyline(map)
                pl.setPoints(sl.map { GeoPoint(it.lat, it.lon) })
                pl.outlinePaint.color = 0xFFE5322D.toInt()
                pl.outlinePaint.strokeWidth = 17 * dn
                pl.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                pl.title = c.name
                corrUnder.add(pl)
            } else if (c.path.size >= 2) {
                val pl = Polyline(map)
                pl.setPoints(c.path.map { GeoPoint(it.lat, it.lon) })
                pl.outlinePaint.color = 0xAAD62B2B.toInt()
                pl.outlinePaint.strokeWidth = 9f
                pl.title = c.name
                pl.snippet = (c.limit?.let { "Limit $it km/s" } ?: "Limit bilinmiyor") + " · " + Geo.fmtKm(c.lengthM) + " · " + c.sourceLabel
                corr.add(pl)
            }
            val m = Marker(map)
            m.position = GeoPoint(c.from.lat, c.from.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = dot(Color.WHITE, 0xFFD62B2B.toInt(), 34)
            m.title = c.name
            m.snippet = "Koridor başı · " + (c.limit?.let { "limit $it km/s" } ?: "limit bilinmiyor") + " · " + Geo.fmtKm(c.lengthM)
            corr.add(m)
        }
    }
}
