package com.xantildev.koridor

/**
 * "İleride ne var?": rotada önümüzdeki [rangeM] metrede EDS koridorları, EGM'nin ilçe arası radar/kontrol sayıları,
 * bizim tahmini radar/çevirme noktalarımız (kimin, ne zaman işaretlediği) ve trafik sıkışıklıkları; yakından uzağa.
 * Android Auto'daki "İleride" ekranı ve telefondaki detay bunu kullanır.
 */
object Ahead {
    enum class Kind { EDS, EGM, OURS, TRAFFIC }
    class Item(val kind: Kind, val title: String, val text: String, val atM: Double)

    fun items(inf: RouteSession.Info, pos: LatLng?, rangeM: Double = 200_000.0): List<Item> {
        val me = if (pos != null) inf.project(pos).first else 0.0
        val end = me + rangeM
        val out = ArrayList<Item>()
        fun d(a: Double) = if (a <= me + 30) "şu an" else "${Geo.fmtKm(a - me)} ileride"

        for (c in inf.corridors) {
            val a0 = inf.project(c.from).first
            val a1 = inf.project(c.to).first.coerceAtLeast(a0)
            if (a1 < me || a0 > end) continue
            val lim = c.limit?.let { "limit $it km/s" } ?: "limit bilinmiyor"
            val where = if (a0 <= me) "içindesin · kalan ${Geo.fmtKm(a1 - me)}" else "${d(a0)} · ${Geo.fmtKm(c.lengthM)}"
            out.add(Item(Kind.EDS, "EDS · ${c.name}", "$where · $lim", maxOf(a0, me)))
        }
        for (g in RadarMap.segs) {
            if (g.info.target != inf.target) continue
            if (g.radar <= 0 && g.other <= 0) continue
            val a0 = inf.project(g.info.pointAt(g.startAlong)).first
            val a1 = inf.project(g.info.pointAt(g.endAlong)).first.coerceAtLeast(a0)
            if (a1 < me || a0 > end) continue
            val what = listOfNotNull(g.radar.takeIf { it > 0 }?.let { "$it radar" }, g.other.takeIf { it > 0 }?.let { "$it kontrol" }).joinToString(", ")
            val ours = OurRadar.inSegment(inf, a0, a1)
            out.add(Item(Kind.EGM, "${g.fromName} – ${g.toName} arası",
                "EGM: $what · " + (if (a0 <= me) "bu bölümdesin" else d(a0)) + (if (ours > 0) " · bizde $ours tahmini nokta" else ""), maxOf(a0, me)))
        }
        for ((s, a) in OurRadar.onLine(OurRadar.spots, inf.line, me + 30, end)) {
            out.add(Item(Kind.OURS, "Tahmini ${s.label} (bizim kayıt)", "${d(a)} · ${OurRadar.seenText(s)}", a))
        }
        val tr = Traffic.of(inf.line)
        for (sg in tr) {
            val a0 = inf.alongAt(sg.from); val a1 = inf.alongAt(sg.to)
            if (a1 < me || a0 > end) continue
            out.add(Item(Kind.TRAFFIC, if (sg.level >= 2) "Trafik sıkışıklığı" else "Yavaş trafik", "${d(a0)} · ${Geo.fmtKm(a1 - a0)} boyunca", maxOf(a0, me)))
        }
        return out.sortedBy { it.atM }
    }

    /** Rota yokken: yakındaki (20 km) bizim noktalarımız, uzaklığa göre. */
    fun nearby(pos: LatLng, rangeM: Double = 20_000.0): List<Item> =
        OurRadar.spots.map { it to Geo.dist(pos, it.ll) }.filter { it.second <= rangeM }.sortedBy { it.second }
            .map { (s, d) -> Item(Kind.OURS, "Tahmini ${s.label} (bizim kayıt)", "${Geo.fmtKm(d)} uzakta · ${OurRadar.seenText(s)}", d) }
}
