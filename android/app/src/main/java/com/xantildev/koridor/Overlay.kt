package com.xantildev.koridor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Her ekranda görünen küçük gösterge (uygulama arka plandayken).
 * Koridorda: ortalama + limit (renkli); yaklaşırken: kalan mesafe + limit; normalde: anlık hız.
 * Dokun: uygulamayı aç. Basılı tutup sürükle: yerini değiştir.
 */
object Overlay {
    /** Uygulama ekranda mı (MainActivity onStart/onStop) */
    var appVisible = false

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var big: TextView
    private lateinit var small: TextView
    private lateinit var sign: TextView
    private var dp = 1f

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Takip servisinden ve uygulama ön/arka plana geçince çağrılır. */
    fun update(ctx: Context, s: UiState) {
        val want = AppSettings.overlay && !appVisible && s.mode != "idle" && allowed(ctx)
        if (!want) { hide(); return }
        val portrait = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (root != null && portrait != compact) hide()
        if (root == null) create(ctx.applicationContext)
        render(s)
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm?.removeView(r) }
        root = null
    }

    private var compact = false
    private lateinit var unit: TextView

    private fun pill(color: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = 40 * dp
        setColor(color)
        setStroke((2 * dp).toInt(), stroke)
    }

    /** EDS dışı: koyu laciverde geçişli zemin, mavi çerçeve. */
    private fun calm(radius: Float, oval: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(20, 38, 66), Color.rgb(12, 18, 24))
    ).apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
        setStroke((3 * dp).toInt(), Color.rgb(76, 141, 255))
    }

    private fun ring(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke((4 * dp).toInt(), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(ctx: Context) {
        if (root != null) return
        dp = ctx.resources.displayMetrics.density
        // Dikey ekran (telefon): yuvarlak tek sayı; yatay (araç tableti): uzun gösterge
        compact = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val prefs = ctx.getSharedPreferences("ayar", Context.MODE_PRIVATE)
        val lay = LinearLayout(ctx).apply { elevation = 8 * dp }
        sign = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(17, 17, 17))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((5 * dp).toInt(), Color.rgb(214, 43, 43))
            }
        }
        big = TextView(ctx).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); includeFontPadding = false; gravity = Gravity.CENTER }
        unit = TextView(ctx).apply { setTextColor(Color.rgb(170, 190, 210)); includeFontPadding = false; gravity = Gravity.CENTER }
        small = TextView(ctx).apply { textSize = 12f; setTextColor(Color.rgb(150, 168, 180)); includeFontPadding = false }
        if (compact) {
            lay.orientation = LinearLayout.VERTICAL
            lay.gravity = Gravity.CENTER
            big.textSize = 24f
            unit.textSize = 10f
            lay.addView(big)
            lay.addView(unit)
        } else {
            lay.orientation = LinearLayout.HORIZONTAL
            lay.gravity = Gravity.CENTER_VERTICAL
            lay.setPadding((8 * dp).toInt(), (6 * dp).toInt(), (18 * dp).toInt(), (6 * dp).toInt())
            lay.addView(sign, LinearLayout.LayoutParams((50 * dp).toInt(), (50 * dp).toInt()).apply { rightMargin = (10 * dp).toInt() })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            big.textSize = 28f
            unit.textSize = 13f
            line.addView(big)
            line.addView(unit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (4 * dp).toInt(); bottomMargin = (2 * dp).toInt() })
            col.addView(line)
            col.addView(small)
            lay.addView(col)
        }

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val size = if (compact) (74 * dp).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
        val p = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val k = if (compact) "ovc" else "ov"
            x = prefs.getInt(k + "_x", (24 * dp).toInt())
            y = prefs.getInt(k + "_y", (90 * dp).toInt())
        }
        params = p
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        lay.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { p.x = px + dx.toInt(); p.y = py + dy.toInt(); runCatching { w.updateViewLayout(v, p) } }
                }
                MotionEvent.ACTION_UP -> {
                    val k = if (compact) "ovc" else "ov"
                    if (moved) prefs.edit().putInt(k + "_x", p.x).putInt(k + "_y", p.y).apply()
                    else ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            true
        }
        try {
            w.addView(lay, p)
            root = lay
        } catch (_: Exception) {
            root = null
        }
    }

    private fun render(s: UiState) {
        val r = root ?: return
        val c = s.corridor
        val inC = s.confirmed && c != null
        val ok = Color.rgb(47, 191, 113); val warn = Color.rgb(242, 182, 50); val over = Color.rgb(255, 90, 79)
        val dark = Color.argb(240, 13, 18, 20)
        var lim: Int? = null
        when {
            inC -> {
                lim = c?.limit
                val a = s.avg
                val col = when { a == null || lim == null -> Color.WHITE; a > lim -> over; a > lim * 0.95 -> warn; else -> ok }
                big.text = a?.roundToInt()?.toString() ?: "—"
                big.setTextColor(col)
                unit.text = if (compact) "EDS ort." else "km/s"
                small.text = "EDS ortalaması · kalan ${Geo.fmtKm(s.remaining)}" + if (s.partial) " · kısmi" else ""
                val edge = if (col == Color.WHITE) ok else col
                r.background = if (compact) ring(dark, edge) else pill(dark, edge)
            }
            s.approachC != null && s.approachD != null -> {
                lim = s.approachC.limit
                big.text = if (compact) Geo.km1(s.approachD) else Geo.fmtKm(s.approachD)
                big.setTextColor(warn)
                unit.text = if (compact) "km EDS" else ""
                small.text = "sonra EDS" + (lim?.let { " · limit $it" } ?: "") + " · ${s.speedKmh ?: "—"} km/s"
                r.background = if (compact) ring(dark, warn) else pill(dark, warn)
            }
            else -> {
                lim = s.roadLimit
                val sp = s.speedKmh
                big.text = sp?.toString() ?: "—"
                big.setTextColor(if (lim != null && (sp ?: 0) > lim) over else Color.WHITE)
                unit.text = "km/s"
                small.text = if (s.driving) "anlık hız · yolculuk ort. ${s.tripAvg?.roundToInt() ?: "—"} · ${Geo.fmtKm(s.trip.dist)}" else "anlık hız · hazır"
                r.background = calm(40 * dp, compact)
            }
        }
        if (!compact) {
            sign.visibility = if (lim != null) View.VISIBLE else View.GONE
            sign.text = lim?.toString() ?: ""
        }
    }
}
