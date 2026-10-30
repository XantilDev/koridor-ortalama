package com.xantildev.koridor

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

data class Corridor(
    val id: String,
    val name: String,
    val from: LatLng,
    val to: LatLng,
    val limit: Int?,
    val lengthM: Double,
    val source: String,
    val path: List<LatLng> = emptyList()
) {
    val key: String = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", from.lat, from.lon, to.lat, to.lon)

    val sourceLabel: String
        get() = source.split("+").joinToString(" + ") {
            when (it) {
                "tg" -> "trafik.gov.tr"
                "egm" -> "EGM"
                "user" -> "Senin işaretin"
                "demo" -> "Örnek"
                else -> it
            }
        }
}

object Geo {
    private const val R = 6371000.0
    private val TR = Locale("tr", "TR")

    fun dist(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(s))
    }

    fun pathLength(p: List<LatLng>): Double {
        var t = 0.0
        for (i in 1 until p.size) t += dist(p[i - 1], p[i])
        return t
    }

    fun fmtKm(m: Double): String =
        if (m < 10000) String.format(TR, "%.2f km", m / 1000) else String.format(TR, "%.1f km", m / 1000)

    fun km1(m: Double): String = String.format(TR, "%.1f", m / 1000)

    fun fmtT(sec: Double): String {
        val s = max(0, sec.roundToInt())
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Noktanın bir çizgiye (rota) en kısa uzaklığı, metre. */
    fun distToLine(p: LatLng, line: List<LatLng>): Double {
        if (line.size < 2) return if (line.isEmpty()) Double.MAX_VALUE else dist(p, line[0])
        val kx = cos(Math.toRadians(p.lat)) * 111320.0
        val ky = 110540.0
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = (line[i].lon - p.lon) * kx
            val ay = (line[i].lat - p.lat) * ky
            val bx = (line[i + 1].lon - p.lon) * kx
            val by = (line[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = (if (l2 > 0) -(ax * dx + ay * dy) / l2 else 0.0).coerceIn(0.0, 1.0)
            val d = sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            if (d < best) best = d
        }
        return best
    }

    fun fmtSlack(sec: Double): String = (if (sec >= 0) "+" else "−") + fmtT(kotlin.math.abs(sec))
}
