package com.xantildev.koridor

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Canlı harita: OpenStreetMap (osmdroid) ya da Google haritası aynı işleri yapar. */
interface LiveMapView {
    val view: View
    var onUserPan: () -> Unit
    /** Büyük sürüş ekranı: gidiş yönü yukarıda, araç ekranın alt kısmında; sağdaki panelin genişliği (px). */
    var navMode: Boolean
    var rightInsetPx: Int
    fun update(s: UiState, ver: Int, follow: Boolean)
    fun recenter(s: UiState)
    fun zoomIn()
    fun zoomOut()
    fun resume()
    fun pause()
}

object MapChoice {
    /** Google haritası kullanılabilir mi: anahtar var, ayar "google" ve telefonda Google hizmetleri (ya da microG) var. */
    fun useGoogle(ctx: Context): Boolean {
        if (!GoogleApi.enabled || AppSettings.mapProvider != "google") return false
        return try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }
    }

    /** Web haritası anahtar hatası verdiyse bu açılışta OpenStreetMap kullanılır. */
    var webFailed by androidx.compose.runtime.mutableStateOf(false)

    /** Google Play Hizmetleri yoksa (araç ekranı) Google haritası web üzerinden açılır. */
    fun create(ctx: Context): LiveMapView = when {
        useGoogle(ctx) -> try { GMapHolder(ctx) } catch (_: Throwable) { MapHolder(ctx) }
        AppSettings.mapProvider == "google" && !webFailed -> try { WebMapHolder(ctx) } catch (_: Throwable) { MapHolder(ctx) }
        else -> MapHolder(ctx)
    }
}

/** Konum değişiminden gidiş yönü (derece, kuzey = 0). 12 m'den kısa hareketler ve 5 km/s altı yok sayılır. */
class HeadingTracker {
    private var prev: LatLng? = null
    var deg: Float? = null
        private set

    fun feed(p: LatLng, speedKmh: Int?): Float? {
        val q = prev
        if (q == null) { prev = p; return deg }
        val d = Geo.dist(q, p)
        if (d >= 12) {
            if ((speedKmh ?: 99) >= 5) {
                val b = bearing(q, p)
                val old = deg
                deg = if (old == null) b else {
                    var diff = b - old
                    while (diff > 180) diff -= 360
                    while (diff < -180) diff += 360
                    ((old + diff * 0.6f) % 360 + 360) % 360
                }
            }
            prev = p
        } else if (d > 300) prev = p
        return deg
    }

    private fun bearing(a: LatLng, b: LatLng): Float {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(dl) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
    }
}

/** Yön oku (araç simgesi): yukarıyı gösterir, döndürülerek kullanılır. */
fun arrowBitmap(density: Float, sizeDp: Int = 34): android.graphics.Bitmap {
    val px = (sizeDp * density).toInt()
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val path = android.graphics.Path().apply {
        moveTo(px / 2f, px * 0.06f)
        lineTo(px * 0.88f, px * 0.92f)
        lineTo(px / 2f, px * 0.72f)
        lineTo(px * 0.12f, px * 0.92f)
        close()
    }
    p.color = 0xFFFFFFFF.toInt(); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = px * 0.1f; p.strokeJoin = android.graphics.Paint.Join.ROUND
    c.drawPath(path, p)
    p.color = 0xFF2F7BFF.toInt(); p.style = android.graphics.Paint.Style.FILL
    c.drawPath(path, p)
    return b
}
