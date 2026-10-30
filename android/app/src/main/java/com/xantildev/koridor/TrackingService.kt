package com.xantildev.koridor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ekran kapalıyken de konumu izleyen ön plan servisi. */
class TrackingService : Service() {

    companion object {
        private const val CH = "takip"
        private const val ID = 7
        private const val ACTION_STOP = "dur"
        private const val EXTRA_RESUME = "devam"
        var running = false
            private set

        /** resumeId verilirse o yarım kalan yolculuğa devam edilir. */
        fun start(ctx: Context, resumeId: String? = null) {
            val i = Intent(ctx, TrackingService::class.java)
            if (resumeId != null) i.putExtra(EXTRA_RESUME, resumeId)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
            else Engine.stop()
        }
    }

    private var lm: LocationManager? = null
    private var scope: CoroutineScope? = null
    private var lastNotif = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Engine.onFix(
                Fix(
                    loc.latitude, loc.longitude, loc.time,
                    if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    if (loc.hasAccuracy()) loc.accuracy else null
                )
            )
        }

        override fun onProviderDisabled(provider: String) {
            Engine.gpsStatus("Konum kapalı — telefonun konumunu aç")
        }

        override fun onProviderEnabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startFg()
        if (!running) {
            running = true
            // intent null ise sistem servisi yeniden başlattı: yarım kalan yolculuktan devam et
            val resumeId = intent?.getStringExtra(EXTRA_RESUME) ?: if (intent == null) TripStore.activeId() else null
            val resume = TripStore.get(resumeId)
            Engine.startTracking("gps", resume)
            Engine.say(if (resume != null) "Yolculuğa devam ediliyor." else "Takip başladı.")
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            try {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Engine.gpsStatus("Konum izni yok")
                shutdown()
                return START_NOT_STICKY
            }
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = sc
            sc.launch {
                Engine.state.collect { s ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotif > 3000) {
                        lastNotif = now
                        val text = if (s.arrived != null) {
                            "Hedefe vardın: ${s.arrived} · bitirmek için “Sürüşü bitir”"
                        } else if (s.confirmed && s.corridor != null) {
                            "Koridor: ${s.avg?.roundToInt() ?: "—"} km/s · limit ${s.corridor.limit ?: "?"} · kalan ${Geo.fmtKm(s.remaining)}"
                        } else {
                            "Yolculuk ortalaması ${s.tripAvg?.roundToInt() ?: "—"} km/s · ${Geo.fmtKm(s.trip.dist)}"
                        }
                        getSystemService(NotificationManager::class.java).notify(ID, build(text))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Sürüşü bitir", stop
        ).build()
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Koridor Ortalama takipte")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Sürüş takibi", NotificationManager.IMPORTANCE_LOW))
        val n = build("Konum bekleniyor…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(ID, n)
        }
    }

    private fun shutdown() {
        lm?.removeUpdates(listener)
        lm = null
        scope?.cancel()
        scope = null
        if (running) Engine.say("Takip durdu.")
        running = false
        Engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lm?.removeUpdates(listener)
        scope?.cancel()
        if (running) {
            running = false
            Engine.stop()
        }
        super.onDestroy()
    }
}
