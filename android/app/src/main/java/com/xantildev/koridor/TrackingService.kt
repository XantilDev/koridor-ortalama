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
        private const val CH_ALERT = "konum_uyari"
        private const val ID_ALERT = 8
        private const val ID = 7
        private const val ACTION_STOP = "dur"
        private const val ACTION_END = "bitir"
        private const val ACTION_REFRESH = "tazele"
        private const val EXTRA_RESUME = "devam"
        private const val EXTRA_STANDBY = "hazir"
        var running = false
            private set

        /**
         * Takibi başlatır. standby: konum açılır ama yolculuk kaydı araç hareket edince başlar.
         * resumeId verilirse o yarım kalan yolculuğa devam edilir. Servis zaten açıksa ve
         * standby değilse hazır durumdan sürüşe geçilir.
         */
        fun start(ctx: Context, resumeId: String? = null, standby: Boolean = false) {
            if (running) {
                if (!standby) Engine.beginTrip()
                return
            }
            val i = Intent(ctx, TrackingService::class.java)
            if (resumeId != null) i.putExtra(EXTRA_RESUME, resumeId)
            if (standby) i.putExtra(EXTRA_STANDBY, true)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Sürüşü bitirir (kaydeder), konum açık kalır; servis yoksa motoru durdurur. */
        fun end(ctx: Context) {
            if (running) Engine.endTrip() else Engine.stop()
        }

        /**
         * Uygulama öne gelince: servis zaten açıksa ön plan servisi olarak tazelenir ve konum dinleyicisi yeniden kurulur.
         * (Servis arka planda yeniden başlatıldıysa Android ona yalnız uygulama öndeyken konum verir; öndeyken tazelemek bunu düzeltir.)
         */
        fun refresh(ctx: Context) {
            if (!running) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_REFRESH)) }
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
            else Engine.stop()
        }
    }

    private var lm: LocationManager? = null
    private var scope: CoroutineScope? = null
    private var lastNotif = 0L

    @Volatile private var lastFixWall = 0L
    private var lastRearm = 0L
    private var wake: android.os.PowerManager.WakeLock? = null

    /** Konum gelmez olursa (bazı araç ekranları arka planda GPS dinleyicisini durdurabiliyor) dinleyiciyi yeniden kur. */
    private fun rearm() {
        val m = lm ?: return
        lastRearm = System.currentTimeMillis()
        try {
            m.removeUpdates(listener)
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    /** Sürüşte ham konumlar 10'arlı paketler hâlinde loga yazılır (sorunlu bölümü sonradan aynen oynatmak için). */
    private val fixBuf = org.json.JSONArray()
    private fun fixLog(loc: Location) {
        // Sürüşte her konum; sürüş kapalıyken de araç hareket ediyorsa (≥ 15 km/s) yazılır, sorunlu anlar kaybolmasın
        val st = Engine.state.value
        val moving = loc.hasSpeed() && loc.speed > 4.2f
        if (st.mode != "gps" && !(st.mode == "bekle" && moving)) { if (fixBuf.length() > 0) flushFixes(); return }
        fixBuf.put(org.json.JSONArray().put(loc.time).put(Math.round(loc.latitude * 1e6) / 1e6).put(Math.round(loc.longitude * 1e6) / 1e6)
            .put(if (loc.hasSpeed()) Math.round(loc.speed * 10) / 10.0 else -1).put(if (loc.hasBearing()) loc.bearing.toInt() else -1)
            .put(if (loc.hasAccuracy()) loc.accuracy.toInt() else -1).put(if (loc.provider == LocationManager.GPS_PROVIDER) "g" else "a"))
        if (fixBuf.length() >= 10) flushFixes()
    }
    private fun flushFixes() {
        TLog.i("iz", "${fixBuf.length()} konum", org.json.JSONObject().put("n", org.json.JSONArray(fixBuf.toString()))
            .put("alan", "t,lat,lon,hiz_ms,yon,dogruluk,kaynak"))
        while (fixBuf.length() > 0) fixBuf.remove(0)
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // GPS varken ağ konumu yalnız GPS sustuğunda kullanılır
            if (loc.provider != LocationManager.GPS_PROVIDER && System.currentTimeMillis() - lastFixWall < 5000) return
            val gap = System.currentTimeMillis() - lastFixWall
            if (lastFixWall > 0 && gap > 20_000) {
                TLog.w("konum", "Konum ${gap / 1000} sn sonra geri geldi (${loc.provider})")
                clearGpsAlert()
            }
            lastFixWall = System.currentTimeMillis()
            fixLog(loc)
            // GPS'in kendi gidiş yönü (hareketten hesaplanandan çok daha kararlı); yalnız 10 km/s üstünde
            if (loc.provider == LocationManager.GPS_PROVIDER && loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.8f)
                GpsHeading.set(loc.bearing)
            if (loc.provider == LocationManager.GPS_PROVIDER)
                Places.noteGps(LatLng(loc.latitude, loc.longitude), if (loc.hasAccuracy()) loc.accuracy else null, System.currentTimeMillis())
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
        if (intent?.action == ACTION_END) {
            Engine.endTrip()
            return START_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            startFg()   // startForegroundService ile gelindi: 5 sn içinde ön plana geçmek zorunlu
            if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            rearm()
            TLog.i("konum", "Uygulama öne geldi: konum servisi tazelendi")
            return START_STICKY
        }
        startFg()
        if (!running) {
            running = true
            // intent null ise sistem servisi yeniden başlattı: yarım kalan yolculuktan devam et
            val resumeId = intent?.getStringExtra(EXTRA_RESUME) ?: if (intent == null) TripStore.activeId() else null
            val resume = TripStore.get(resumeId)
            val standby = resume == null && (intent == null || intent.getBooleanExtra(EXTRA_STANDBY, false))
            Engine.onStandbyTimeout = { shutdown() }
            Engine.startTracking(if (standby) "bekle" else "gps", resume)
            if (!standby) Engine.say(if (resume != null) "Yolculuğa devam ediliyor." else "Sürüş başladı.")
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            try {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Engine.gpsStatus("Konum izni yok")
                shutdown()
                return START_NOT_STICKY
            }
            lastFixWall = System.currentTimeMillis()
            // Ekran kapalı / arka planda işlemci uyumasın (sürüş boyunca)
            runCatching {
                wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "koridor:takip").apply { acquire(12 * 3600_000L) }
            }
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = sc
            // Bekçi: 20 sn konum gelmezse dinleyiciyi yeniden kur ve ekranda bildir
            sc.launch {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    // Hazır modda 1 saat hareket yoksa (konum hiç gelmese de) tamamen kapan
                    if (Engine.standbyExpired()) {
                        TLog.i("uygulama", "Hazır modda 1 saat hareket yok: konum kapatıldı")
                        shutdown()
                        break
                    }
                    val quiet = System.currentTimeMillis() - lastFixWall
                    if (quiet > 20_000) {
                        TLog.throttled("konum-yok", 60_000) { TLog.w("konum", "Konum alınamıyor (${quiet / 1000} sn), dinleyici yeniden kuruluyor") }
                        if (System.currentTimeMillis() - lastRearm > 30_000) rearm()
                        Engine.gpsStatus("Konum alınamıyor (${quiet / 1000} sn) — yeniden deneniyor")
                        // Sürüşte konum kesildiyse (çoğunlukla araç tableti arka plandaki uygulamaya konum vermiyor) uyar
                        if (Engine.state.value.mode == "gps") gpsAlert(quiet)
                    }
                }
            }
            sc.launch {
                Engine.state.collect { s ->
                    Firma.onState(s)
                    Overlay.update(this@TrackingService, s)
                    val now = System.currentTimeMillis()
                    if (now - lastNotif > 3000) {
                        lastNotif = now
                        val text = if (s.mode == "bekle") {
                            "Hazır · hareket edince sürüş başlar"
                        } else if (s.arrived != null) {
                            "Hedefe vardın: ${s.arrived} · bitirmek için “Sürüşü bitir”"
                        } else if (s.confirmed && s.corridor != null) {
                            "Koridor: ${s.avg?.roundToInt() ?: "—"} km/s · limit ${s.corridor.limit ?: "?"} · kalan ${Geo.fmtKm(s.remaining)}"
                        } else {
                            "Yolculuk ortalaması ${s.tripAvg?.roundToInt() ?: "—"} km/s · ${Geo.fmtKm(s.trip.dist)}"
                        }
                        getSystemService(NotificationManager::class.java).notify(ID, build(text, s.mode == "bekle"))
                    }
                }
            }
        }
        return START_STICKY
    }

    // ---------------- Konum kesildi uyarısı ----------------
    private var lastGpsAlert = 0L
    private var gpsAlertShown = false

    /**
     * Sürüşte konum 20 sn'den uzun gelmezse: sesli uyarı + dokununca uygulamayı açan bildirim (3 dk'da bir tekrar).
     * Uygulama öndeyse yalnız ekrandaki "Konum alınamıyor" yazısı yeter, ses 3 dk'da bir.
     */
    private fun gpsAlert(quiet: Long) {
        val now = System.currentTimeMillis()
        if (now - lastGpsAlert < 3 * 60_000L) return
        lastGpsAlert = now
        val fg = AppState.foreground
        Engine.say(if (fg) "Konum alınamıyor." else "Konum alınamıyor. Ölçüm için uygulamayı öne alın.", key = "konum-kesildi", gap = 0, corridor = true)
        TLog.w("konum", "Konum kesildi uyarısı verildi (${quiet / 1000} sn, " + (if (fg) "ön planda" else "arka planda") + ")")
        if (fg) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Konum uyarısı", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(ID_ALERT, Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Konum alınamıyor")
            .setContentText("Ortalama hız ölçülemiyor. Dokun, uygulamayı öne al.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .build())
        gpsAlertShown = true
    }

    private fun clearGpsAlert() {
        lastGpsAlert = 0L
        if (!gpsAlertShown) return
        gpsAlertShown = false
        runCatching { getSystemService(NotificationManager::class.java).cancel(ID_ALERT) }
    }

    private fun build(text: String, standby: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // "Kapat": konumu ve uygulamayı tamamen durdurur (süren yolculuk kaydedilir)
        val close = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Kapat", close
        ).build()
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (standby) "Koridor Ortalama hazır" else "Koridor Ortalama takipte")
            .setContentText(text)
            .setContentIntent(open)
        if (!standby) {
            val end = PendingIntent.getService(
                this, 2, Intent(this, TrackingService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Sürüşü bitir", end
            ).build())
        }
        return b
            .addAction(closeAction)
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
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        lm?.removeUpdates(listener)
        lm = null
        scope?.cancel()
        scope = null
        if (running) Engine.say("Takip durdu.")
        running = false
        Engine.stop()
        Firma.onTrackingOff()
        Overlay.hide()
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
