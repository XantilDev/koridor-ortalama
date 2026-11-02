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
        if (root == null) create(ctx.applicationContext)
        render(s)
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm?.removeView(r) }
        root = null
    }

    private fun pill(color: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = 40 * dp
        setColor(color)
        setStroke((2 * dp).toInt(), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(ctx: Context) {
        dp = ctx.resources.displayMetrics.density
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val prefs = ctx.getSharedPreferences("ayar", Context.MODE_PRIVATE)
        val lay = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            background = pill(Color.argb(235, 13, 18, 20), Color.rgb(37, 48, 47))
            elevation = 8 * dp
        }
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
        lay.addView(sign, LinearLayout.LayoutParams((50 * dp).toInt(), (50 * dp).toInt()).apply { rightMargin = (10 * dp).toInt() })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        big = TextView(ctx).apply { textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); includeFontPadding = false }
        small = TextView(ctx).apply { textSize = 12f; setTextColor(Color.rgb(140, 154, 151)); includeFontPadding = false }
        col.addView(big)
        col.addView(small)
        lay.addView(col)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ov_x", (24 * dp).toInt())
            y = prefs.getInt("ov_y", (90 * dp).toInt())
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
                    if (moved) prefs.edit().putInt("ov_x", p.x).putInt("ov_y", p.y).apply()
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
        if (root == null) return
        val c = s.corridor
        val inC = s.confirmed && c != null
        val ok = Color.rgb(47, 191, 113); val warn = Color.rgb(242, 182, 50); val over = Color.rgb(255, 90, 79)
        val lim: Int?
        when {
            inC -> {
                lim = c?.limit
                val a = s.avg
                big.text = a?.roundToInt()?.toString() ?: "—"
                big.setTextColor(when { a == null || lim == null -> Color.WHITE; a > lim -> over; a > lim * 0.95 -> warn; else -> ok })
                small.text = "EDS ort. · kalan ${Geo.fmtKm(s.remaining)}" + if (s.partial) " · kısmi" else ""
                root?.background = pill(Color.argb(235, 13, 18, 20), if (a != null && lim != null && a > lim) over else ok)
            }
            s.approachC != null && s.approachD != null -> {
                lim = s.approachC.limit
                big.text = Geo.fmtKm(s.approachD)
                big.setTextColor(warn)
                small.text = "sonra EDS · ${s.speedKmh ?: "—"} km/s"
                root?.background = pill(Color.argb(235, 13, 18, 20), warn)
            }
            else -> {
                lim = s.roadLimit
                big.text = "${s.speedKmh ?: "—"}"
                big.setTextColor(if (lim != null && (s.speedKmh ?: 0) > lim) over else Color.WHITE)
                small.text = if (s.driving) "km/s · yolculuk ort. ${s.tripAvg?.roundToInt() ?: "—"}" else "km/s · hazır"
                root?.background = pill(Color.argb(235, 13, 18, 20), Color.rgb(37, 48, 47))
            }
        }
        sign.visibility = if (lim != null) View.VISIBLE else View.GONE
        sign.text = lim?.toString() ?: ""
    }
}
