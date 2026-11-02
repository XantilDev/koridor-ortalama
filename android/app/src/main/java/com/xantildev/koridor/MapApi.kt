package com.xantildev.koridor

import android.content.Context
import android.view.View

/** Canlı harita: OpenStreetMap (osmdroid) ya da Google haritası aynı işleri yapar. */
interface LiveMapView {
    val view: View
    var onUserPan: () -> Unit
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

    fun create(ctx: Context): LiveMapView = if (useGoogle(ctx)) {
        try { GMapHolder(ctx) } catch (_: Throwable) { MapHolder(ctx) }
    } else MapHolder(ctx)
}
