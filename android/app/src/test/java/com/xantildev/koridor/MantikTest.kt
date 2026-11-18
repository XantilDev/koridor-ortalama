package com.xantildev.koridor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Yayından önce derlemede çalışan testler: biri bozulursa APK oluşmaz. */
class LocPickTest {
    private val konya = LatLng(37.8746, 32.4932)
    private val istanbul = LatLng(41.0082, 28.9784)
    private val now = 10_000_000_000L

    @Test fun kabaAgKonumuElenir() {
        // Operatör IP'sinden gelen "İstanbul" (doğruluk ±20 km)
        assertNotNull(LocPick.reject(LocFix(istanbul, 20_000f, now, false), null, now))
    }

    @Test fun dogruluguBilinmeyenAgKonumuElenir() {
        assertNotNull(LocPick.reject(LocFix(istanbul, null, now, false), null, now))
    }

    @Test fun sonGpsKonumunaUzakAgKonumuElenir() {
        // Doğruluğu iyi görünse bile, 10 dk önce Konya'da GPS varken İstanbul olamaz
        val trusted = LocFix(konya, 8f, now - 10 * 60_000, true)
        assertNotNull(LocPick.reject(LocFix(istanbul, 900f, now, false), trusted, now))
    }

    @Test fun yakindakiAgKonumuKabulEdilir() {
        val trusted = LocFix(konya, 8f, now - 10 * 60_000, true)
        assertNull(LocPick.reject(LocFix(LatLng(37.88, 32.50), 600f, now, false), trusted, now))
    }

    @Test fun gpsAgdanOnceSecilir() {
        val gps = LocFix(LatLng(37.8750, 32.4930), 15f, now - 2000, true)
        val net = LocFix(LatLng(37.8746, 32.4932), 40f, now, false)
        assertEquals(gps, LocPick.best(listOf(net, gps), null, now))
    }

    @Test fun hicGecerliYoksaBos() {
        assertNull(LocPick.best(listOf(LocFix(istanbul, 25_000f, now, false)), null, now))
    }
}

class IlceTest {
    private val list = listOf(
        DistrictFinder.Ilce(1839, 42, "Konya", "Selçuklu", 37.887789, 32.495061),
        DistrictFinder.Ilce(1, 42, "Konya", "Meram", 37.85, 32.43),
        DistrictFinder.Ilce(2, 42, "Konya", "Karatay", 37.87, 32.52),
        DistrictFinder.Ilce(3, 68, "Aksaray", "Merkez", 38.37, 34.03),
        DistrictFinder.Ilce(4, 68, "Aksaray", "Eskil", 38.40, 33.41),
        DistrictFinder.Ilce(5, 42, "Konya", "Sultanhanı?", 38.25, 33.55),
    )

    @Test fun enYakinIlceMerkezi() {
        assertEquals(1839, DistrictFinder.pick(list, LatLng(37.89, 32.49), null)?.id)
        assertEquals(3, DistrictFinder.pick(list, LatLng(38.36, 34.0), null)?.id)
    }

    @Test fun ilBiliniyorsaOIlinIlceleri() {
        // Eskil'e yakın ama il "Konya" denmişse Konya ilçelerinden seçilir
        val p = LatLng(38.40, 33.42)
        assertEquals(4, DistrictFinder.pick(list, p, null)?.id)
        assertEquals("Konya", DistrictFinder.pick(list, p, "Konya")?.il)
        assertEquals("Konya", DistrictFinder.pick(list, p, "Konya İli")?.il)
    }

    @Test fun bilinmeyenIlTumListeden() {
        assertEquals(3, DistrictFinder.pick(list, LatLng(38.36, 34.0), "Atlantis")?.id)
    }

    @Test fun supabaseBiciminiOkur() {
        val rows = (0 until 600).joinToString(",") { "[$it,42,\"Konya\",\"İlçe $it\",37.${it % 9},32.5]" }
        val parsed = DistrictFinder.parse("{\"surum\":1,\"ilceler\":[$rows]}")
        assertEquals(600, parsed?.size)
        assertEquals("İlçe 5", parsed?.get(5)?.ad)
        assertNull(DistrictFinder.parse("{\"surum\":1,\"ilceler\":[]}"))   // boş liste kabul edilmez
        assertNull(DistrictFinder.parse("bozuk"))
    }
}

class HeadingTest {
    /** Düz yol 45 km/s, sonra 90° sağa dönüş. GPS yönü ±8° oynuyor, konum ±3 m. */
    private fun drive(seed: Long, gpsNoise: Double): Triple<Int, Double, Int> {
        val r = Random(seed)
        val h = HeadingTracker("test")
        var t = 1_000_000L
        var lat = 38.0; var lon = 32.5; val v = 12.5
        var changes = 0; var last: Float? = null; var maxDev = 0.0; var lag = -1
        for (i in 0 until 240) {
            t += 1000
            val truth = if (i < 120) 90.0 else 180.0
            lat += v * cos(Math.toRadians(truth)) / 111320
            lon += v * sin(Math.toRadians(truth)) / (111320 * cos(Math.toRadians(lat)))
            val p = LatLng(lat + r.nextGaussian() * 3 / 111320, lon + r.nextGaussian() * 3 / (111320 * cos(Math.toRadians(lat))))
            val d = h.step(p, 45, t, (truth + r.nextGaussian() * gpsNoise).toFloat())
            if (i in 15..119 && d != null) {
                if (last != null && d != last) changes++
                maxDev = maxOf(maxDev, abs(((d - truth + 540) % 360) - 180))
            }
            last = d
            if (i >= 120 && lag < 0 && d != null && abs(((d - truth + 540) % 360) - 180) < 15) lag = i - 120
        }
        return Triple(changes, maxDev, lag)
    }

    @Test fun duzYoldaSallanmaz() {
        for (seed in 1..10L) {
            val (changes, maxDev, _) = drive(seed, 8.0)
            assertTrue("düz yolda $changes yön değişimi (seed $seed)", changes <= 8)
            assertTrue("düz yolda en çok sapma $maxDev° (seed $seed)", maxDev < 20)
        }
    }

    @Test fun donustaGecikmez() {
        for (seed in 1..10L) {
            val (_, _, lag) = drive(seed, 8.0)
            assertTrue("dönüşe $lag sn'de uydu (seed $seed)", lag in 0..9)
        }
    }

    @Test fun durarakDonmez() {
        val h = HeadingTracker("test")
        var t = 0L
        h.step(LatLng(38.0, 32.5), 50, t, 90f)
        val before = h.deg
        repeat(30) { t += 1000; h.step(LatLng(38.0 + it * 1e-5, 32.5), 5, t, 200f) }
        assertEquals(before, h.deg)
    }
}

class OurRadarTest {
    private val day = 86_400_000L
    private fun m(id: Int, lat: Double, lon: Double, yon: Int?, d: Int, tur: String = "radar") =
        OurRadar.Mark("t$id", tur, LatLng(lat, lon), yon, 1_700_000_000_000L + d * day)

    @Test fun yakinIsaretlerBirlesir() {
        // Aynı yerde (100 m içinde), aynı yönde, farklı günlerde üç işaret → tek nokta, 3 kez, 3 gün
        val s = OurRadar.cluster(listOf(m(1, 38.3000, 33.5000, 90, 0), m(2, 38.3005, 33.5008, 95, 30), m(3, 38.2998, 33.4995, 85, 400)))
        assertEquals(1, s.size)
        assertEquals(3, s[0].count)
        assertEquals(3, s[0].days)
    }

    @Test fun karsiSeritAyriNokta() {
        // Aynı yerde ama ters yöne gidenler ayrı nokta (karşı şeritteki radar bizi ilgilendirmez)
        val s = OurRadar.cluster(listOf(m(1, 38.3, 33.5, 90, 0), m(2, 38.3001, 33.5001, 270, 1)))
        assertEquals(2, s.size)
    }

    @Test fun uzakIsaretlerAyri() {
        val s = OurRadar.cluster(listOf(m(1, 38.30, 33.50, 90, 0), m(2, 38.30, 33.52, 90, 0)))   // ~1,7 km
        assertEquals(2, s.size)
    }

    @Test fun cevirmeVeRadarSayilir() {
        val s = OurRadar.cluster(listOf(m(1, 38.3, 33.5, 90, 0, "cevirme"), m(2, 38.3, 33.5, 90, 1, "cevirme"), m(3, 38.3, 33.5, 90, 2)))
        assertEquals(1, s.size); assertEquals(2, s[0].cevirme); assertEquals(1, s[0].radar); assertEquals("cevirme", s[0].tur)
    }

    @Test fun rotaUzerindekiNoktaBulunur() {
        // Doğuya giden düz rota; üstünde doğuya bakan bir radar, kenarda (500 m) bir radar, ters yönde bir radar
        val line = (0..100).map { LatLng(38.3, 33.40 + it * 0.002) }   // ~17 km
        val spots = OurRadar.cluster(listOf(m(1, 38.3002, 33.50, 90, 0), m(2, 38.3045, 33.52, 90, 0), m(3, 38.3, 33.56, 270, 0)))
        val on = OurRadar.onLine(spots, line, 0.0, Double.MAX_VALUE)
        assertEquals(1, on.size)
        assertTrue("rota üstündeki mesafe ${on[0].second}", on[0].second in 8_000.0..9_500.0)
    }

    @Test fun yonsuzIsaretHerYoneUyar() {
        val line = (0..50).map { LatLng(38.3, 33.40 + it * 0.002) }
        val spots = OurRadar.cluster(listOf(m(1, 38.3, 33.45, null, 0)))
        assertEquals(1, OurRadar.onLine(spots, line, 0.0, Double.MAX_VALUE).size)
    }

    @Test fun rotasizGidisYonundeIleride() {
        val spots = OurRadar.cluster(listOf(m(1, 38.3, 33.51, 90, 0)))   // ~870 m doğuda
        val p = LatLng(38.3, 33.50)
        assertNotNull(OurRadar.aheadByHeading(spots, p, 90, 60))
        assertNull(OurRadar.aheadByHeading(spots, p, 270, 60))   // arkamızda
        assertNull(OurRadar.aheadByHeading(spots, p, 90, 10))    // çok yavaş
    }
}
