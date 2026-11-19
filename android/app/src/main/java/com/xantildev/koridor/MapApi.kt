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
    /** Haritaya uzun basınca (yol tarifi için nokta seçme). */
    var onLongPress: (LatLng) -> Unit
    /** Seçilen noktayı iğne ile göster (null: kaldır). */
    fun setPin(p: LatLng?)
    /** Uygulama arka plandan öne geldi (awayMs: ne kadar arkada kaldı). */
    fun foreground(awayMs: Long) {}
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
    /** Web haritasının çizim süreci kapanırsa artar: harita baştan kurulur. */
    var webGen by androidx.compose.runtime.mutableIntStateOf(0)

    /** Google Play Hizmetleri yoksa (araç ekranı) Google haritası web üzerinden açılır. */
    fun create(ctx: Context): LiveMapView = when {
        useGoogle(ctx) -> try { GMapHolder(ctx) } catch (_: Throwable) { MapHolder(ctx) }
        AppSettings.mapProvider == "google" && !webFailed -> try { WebMapHolder(ctx) } catch (_: Throwable) { MapHolder(ctx) }
        else -> MapHolder(ctx)
    }
}

/** Konum değişiminden gidiş yönü (derece, kuzey = 0). 12 m'den kısa hareketler ve 5 km/s altı yok sayılır. */
/** Teşhis için: haritanın son durumu. */
object MapDiag {
    @Volatile var text: String = ""
}

/** GPS alıcısının verdiği son gidiş yönü (derece) ve zamanı. */
object GpsHeading {
    @Volatile var deg: Float? = null
        private set
    @Volatile var at = 0L
        private set
    fun set(d: Float) { deg = d; at = System.currentTimeMillis() }
    fun fresh(): Float? = if (System.currentTimeMillis() - at < 3000) deg else null
}

/**
 * Haritanın döneceği gidiş yönü.
 * Aday yön: son 20 sn'deki konumlardan, en az 50 m geriden şimdiki konuma çizilen yön (kısa adımlardaki GPS
 * oynaması yönü sallamasın) ile GPS'in kendi gidiş yönünün ortası; biri yoksa öteki. Aday yumuşatılır (%40),
 * gösterilen yön ancak 10°'den fazla ayrılınca döner, bir seferde en çok 35°. 10 km/s altında dönmez.
 * (Simülasyonda eski yönteme göre düz yolda ~6 kat daha az dönüş, en büyük sapma yarıya iner; virajda gecikme ~1 sn artar.)
 */
class HeadingTracker(private val tag: String = "harita") {
    /** Teşhis: son 1 dakikadaki yön değişimleri ve kaynağı. */
    val turns = ArrayDeque<Long>()
    var source = "—"
        private set
    fun turnsPerMin(): Int { val n = System.currentTimeMillis(); while (turns.isNotEmpty() && n - turns.first() > 60_000) turns.removeFirst(); return turns.size }
    private val hist = ArrayDeque<Pair<Long, LatLng>>()
    private var smooth: Float? = null
    var deg: Float? = null
        private set

    fun feed(p: LatLng, speedKmh: Int?): Float? =
        step(p, speedKmh, System.currentTimeMillis(), GpsHeading.fresh()) { RouteSession.bearingAt(p) }

    /**
     * Saf hesap (testlerde zaman ve GPS yönü dışarıdan verilir).
     * [route]: araç rotanın üstündeyse rotanın o noktadaki yönü (35 m ileriye). Google Haritalar gibi:
     * rotadayken yön GPS'ten değil yolun kendisinden alınır, GPS oynaması haritayı sallamaz.
     */
    fun step(p: LatLng, speedKmh: Int?, now: Long, gps: Float?, route: () -> Float? = { null }): Float? {
        // Harita her ekran güncellemesinde çağırır; yalnız yeni konumda hesaplanır (aynı nokta yumuşatmayı hızlandırmasın)
        val moved = hist.isEmpty() || Geo.dist(hist.last().second, p) >= 2
        if (moved) hist.addLast(now to p)
        while (hist.size > 1 && now - hist.first().first > 20_000) hist.removeFirst()
        if (!moved && smooth != null) return deg
        if ((speedKmh ?: 0) < 10) return deg
        val base = hist.lastOrNull { Geo.dist(it.second, p) >= BASE_M }
        val mv = base?.let { bearing(it.second, p) }
        val rt = route()
        val cand = when {
            rt != null -> rt
            mv != null && gps != null -> norm(mv + diff(gps, mv) / 2)
            else -> mv ?: gps
        } ?: return deg
        source = when { rt != null -> "rotadan"; mv != null && gps != null -> "GPS + hareket"; mv != null -> "hareketten"; else -> "GPS yönü" }
        val s0 = smooth
        val sm = if (s0 == null) cand else norm(s0 + diff(cand, s0) * (if (rt != null) 0.6f else ALPHA))
        smooth = sm
        val old = deg ?: run { deg = sm; return sm }
        val d = diff(sm, old)
        if (kotlin.math.abs(d) < (if (rt != null) 5f else DEAD)) return old
        val nd = norm(old + d.coerceIn(-35f, 35f))
        turns.addLast(now)
        deg = nd
        val src = source
        TLog.throttled("yon-$tag", 1500) {
            TLog.i("yon", "Yön ${old.toInt()}→${nd.toInt()} ($src, fark ${d.toInt()}°)", org.json.JSONObject()
                .put("harita", tag).put("eski", old.toInt()).put("yeni", nd.toInt()).put("aday", cand.toInt()).put("kaynak", src)
                .put("gps_yon", gps?.toInt()).put("hareket_yon", mv?.toInt()).put("hiz", speedKmh).put("dk_donus", turns.size))
        }
        return nd
    }

    companion object {
        const val BASE_M = 50.0
        const val ALPHA = 0.4f
        const val DEAD = 10f
        fun diff(a: Float, b: Float): Float { var d = a - b; while (d > 180) d -= 360; while (d < -180) d += 360; return d }
        fun norm(a: Float) = ((a % 360) + 360) % 360
        fun bearing(a: LatLng, b: LatLng): Float {
            val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lon - a.lon)
            val y = Math.sin(dl) * Math.cos(la2)
            val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
            return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
        }
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

/**
 * Navigasyonda otomatik yakınlık: şehir içinde ve dönüşe yaklaşırken çok yakın (5–6 sokak),
 * yolda hız arttıkça biraz uzak. Kullanıcı +/− ile kaydırabilir; ◎ ile sıfırlanır.
 */
object NavZoom {
    var bias = 0f
    private var cur = 0f
    private var changedAt = 0L
    /** Teşhis: son 1 dakikadaki yakınlık değişimleri. */
    val changes = ArrayDeque<Long>()

    /**
     * Hıza göre yakınlık; eşiklerde gidip gelmesin diye 8 km/s pay ve en az 8 sn arayla değişir.
     * (40–50 km/s'de 45 eşiğinde her saniye yakınlaşıp uzaklaşıyordu.)
     */
    fun of(kmh: Int?): Float {
        val turn = Directions.next?.let { it.step.code != "straight" && it.distM < 300 } == true
        val v = kmh ?: 0
        fun level(x: Int) = when { turn || x < 45 -> 18.3f; x < 75 -> 17.6f; x < 100 -> 16.9f; else -> 16.3f }
        val target = level(v)
        val now = System.currentTimeMillis()
        if (cur == 0f) cur = target
        else if (target != cur && now - changedAt > 8000) {
            // Pay: hız eşiğin 8 km/s ötesine geçmedikçe aynı seviyede kal
            val confirmed = if (target > cur) level(v + 8) == target || turn else level(v - 8) == target
            if (confirmed) {
                TLog.i("harita", "Yakınlık ${cur}→$target (hız $v km/s${if (turn) ", dönüş yakın" else ""})")
                cur = target; changedAt = now; changes.addLast(now)
            }
        }
        while (changes.isNotEmpty() && now - changes.first() > 60_000) changes.removeFirst()
        return (cur + bias).coerceIn(13f, 20f)
    }
}

/** Haritadaki rota etiketi ("A · 7 dk"): seçili olan mavi, diğerleri beyaz. */
fun labelBitmap(text: String, selected: Boolean, density: Float): android.graphics.Bitmap {
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = if (selected) 0xFFFFFFFF.toInt() else 0xFF111827.toInt()
    }
    val w = (tp.measureText(text) + 26 * density).toInt()
    val h = (36 * density).toInt()
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) 0xFF1A73E8.toInt() else 0xFFFFFFFF.toInt() }
    val r = android.graphics.RectF(1f, 1f, w - 1f, h - 1f)
    c.drawRoundRect(r, 12 * density, 12 * density, bg)
    if (!selected) {
        val st = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * density; color = 0xFF9AA4B2.toInt()
        }
        c.drawRoundRect(r, 12 * density, 12 * density, st)
    }
    c.drawText(text, 13 * density, h / 2f - (tp.descent() + tp.ascent()) / 2, tp)
    return b
}

/** Haritadaki bizim radar/çevirme noktası simgesi: beyaz daire, kırmızı kenar, ortada emoji. */
private val pointIcons = HashMap<String, android.graphics.Bitmap>()
fun pointBitmap(icon: String, density: Float): android.graphics.Bitmap = pointIcons.getOrPut("$icon/$density") {
    val px = (30 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(px / 2f, px / 2f, px / 2f - 1, p)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3 * density; p.color = 0xFFDC2626.toInt()
    c.drawCircle(px / 2f, px / 2f, px / 2f - 2 * density, p)
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * density; textAlign = android.graphics.Paint.Align.CENTER }
    val fm = tp.fontMetrics
    c.drawText(icon, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2, tp)
    bmp
}
