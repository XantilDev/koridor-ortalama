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
class MapHolder(ctx: Context) {
    val map = MapView(ctx)
    var onUserPan: () -> Unit = {}

    private val trail = Polyline(map)
    private val you = Marker(map)
    private val corr = FolderOverlay()
    private var drawnKey = -1
    private var hasFix = false
    private var lastTrackSize = -1

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(39.0, 35.0))
        trail.outlinePaint.color = 0xFFF2B632.toInt()
        trail.outlinePaint.strokeWidth = 12f
        you.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        you.icon = dot(0xFFF2B632.toInt(), Color.WHITE, 44)
        you.setInfoWindow(null)
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

    fun update(s: UiState, ver: Int, follow: Boolean) {
        val sim = s.mode == "sim"
        val key = ver * 2 + if (sim) 1 else 0
        if (key != drawnKey) {
            drawnKey = key
            drawCorridors(sim)
        }
        if (s.track.size != lastTrackSize) {
            lastTrackSize = s.track.size
            trail.setPoints(s.track.map { GeoPoint(it.lat, it.lon) })
        }
        val p = s.pos
        if (p != null) {
            val gp = GeoPoint(p.lat, p.lon)
            you.position = gp
            if (!map.overlays.contains(you)) map.overlays.add(you)
            if (!hasFix) {
                hasFix = true
                map.controller.setZoom(15.0)
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

    fun recenter(s: UiState) {
        val p = s.pos ?: return
        map.controller.setZoom(max(map.zoomLevelDouble, 15.0))
        map.controller.setCenter(GeoPoint(p.lat, p.lon))
    }

    private fun drawCorridors(sim: Boolean) {
        corr.items.clear()
        val list = CorridorStore.all() + if (sim) listOf(CorridorStore.withOverride(Engine.DEMO)) else emptyList()
        for (c in list) {
            if (c.path.size >= 2) {
                val pl = Polyline(map)
                pl.setPoints(c.path.map { GeoPoint(it.lat, it.lon) })
                pl.outlinePaint.color = 0xAAD62B2B.toInt()
                pl.outlinePaint.strokeWidth = 9f
                corr.add(pl)
            }
            val m = Marker(map)
            m.position = GeoPoint(c.from.lat, c.from.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = dot(Color.WHITE, 0xFFD62B2B.toInt(), 34)
            m.title = c.name + " · " + (c.limit?.let { "$it km/s" } ?: "limit bilinmiyor")
            corr.add(m)
        }
    }
}
