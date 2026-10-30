package com.xantildev.koridor

/** EGM EDS haritasındaki koordinatlı OHTS giriş–çıkış çiftleri (30.10.2026). Limit yayınlanmıyor. */
object EgmData {
    private class E(val n: String, val a: Double, val b: Double, val c: Double, val d: Double)

    private val raw = listOf(
        E("İstanbul · Ataköy", 40.975891, 28.86431, 40.971031, 28.844084),
        E("İstanbul · Florya", 40.97786, 28.80433, 40.986492, 28.798702),
        E("İstanbul · Bağdat Cad.", 40.962441, 29.076555, 40.971105, 29.060581),
        E("İstanbul · Sarıyer Kazıklıyol", 41.162079, 29.04746, 41.157308, 29.03672),
        E("İstanbul · Hava Harp Okulu", 40.96983, 28.841113, 40.974848, 28.87821),
        E("İstanbul · Mahmutbey- Habipler", 41.075419, 28.838614, 41.105158, 28.850203),
        E("İstanbul · Habipler-Mahmutbey", 41.109705, 28.848524, 41.075546, 28.738463),
        E("İstanbul · Unkapanı", 41.020852, 28.959619, 41.025512, 28.968127),
        E("İstanbul · Zincirlikuyu-Haliç", 41.067022, 29.006827, 41.051226, 28.949551),
        E("İstanbul · Cevizlibağ-Haliç", 41.021537, 28.919284, 41.05003, 28.948179),
        E("İstanbul · Avcılar Haramidere", 40.98937, 28.716272, 41.004109, 28.685841),
        E("İstanbul · Beylikdüzü Haramidere", 41.018735, 28.632734, 41.006064, 28.669256),
        E("İstanbul · Avcılar Küçükçekmece", 40.977924, 28.73632, 40.98307, 28.759648),
        E("İstanbul · Uzunçayır Altunizade", 41.002216, 29.05883, 41.019188, 29.05112),
        E("İstanbul · Sultançiftliği Habipler", 41.092262, 28.862106, 41.107951, 28.850287),
        E("İstanbul · Habipler-Arnavutköy", 41.115154, 28.845171, 41.13457, 28.824506),
        E("İstanbul · Habipler-Arnavutköy", 41.155463, 28.787416, 41.161422, 28.775),
        E("İstanbul · Arnavutköy-Habipler", 41.133839, 28.824874, 41.11509, 28.844995),
        E("İstanbul · Hasdal Kemerburgaz Yolu", 41.120691, 28.946457, 41.134801, 28.933229),
        E("İstanbul · Hasdal Kemerburgaz Yolu", 41.137813, 28.920945, 41.155876, 28.897685),
        E("İstanbul · Hasdal Kemerburgaz Yolu", 41.155798, 28.897507, 41.120675, 28.946251),
        E("İstanbul · Ümraniye Çekmeköy Şile Yolu", 41.031268, 29.135586, 41.025011, 29.17627),
        E("İstanbul · Çekmeköy Ümraniye Şile Yolu", 41.025362, 29.176119, 41.031634, 29.135854),
        E("İstanbul · Çetin Emeç Bulvarı", 40.963987, 29.065162, 40.95749, 29.075552),
        E("İstanbul · Çetin Emeç Bulvarı", 40.957537, 29.075541, 40.952604, 29.09071),
        E("İstanbul · Bağdat Caddesi", 40.955134, 29.091543, 40.962452, 29.076574),
        E("İstanbul · Formula Yolu", 40.934711, 29.357807, 40.922449, 29.374394),
        E("İstanbul · Formula Yolu", 40.942277, 29.372781, 40.935073, 29.359058),
        E("İstanbul · Kağıthane Piyalepaşa Tünel", 41.063359, 28.95465, 41.054292, 28.969337),
        E("İstanbul · Piyalepaşa Kağıthane Tünel", 41.054469, 28.969628, 41.063457, 28.954939),
        E("İstanbul · Bomonti Dolmabahçe Tünel", 41.058333, 28.97637, 41.041914, 28.992599),
        E("İstanbul · Dolmabahçe Bomonti Tünel", 41.042168, 28.992593, 41.058509, 28.976531),
        E("İstanbul · Sarıyer Çayırbaşı Tünel", 41.1734, 29.038153, 41.157912, 29.026416),
        E("İstanbul · Çayırbaşı Sarıyer Tünel", 41.157986, 29.026708, 41.173489, 29.038807),
        E("İstanbul · Tarlabaşı Taksim Tünel", 41.037515, 28.984044, 41.039604, 28.986113),
        E("İstanbul · Taksim Tarlabaşı Tünel", 41.039647, 28.986005, 41.037587, 28.983978),
        E("İstanbul · Kadıköy Yenikapı Avrasya Tünel", 41.005671, 29.027229, 41.002466, 28.969458),
        E("İstanbul · Halit Ulukurt Tüneli Elmalı", 41.122702, 29.131779, 41.138143, 29.121792),
        E("İstanbul · Elmalı Halit Ulukurt Tüneli", 41.138018, 29.121737, 41.122785, 29.131574),
        E("İstanbul · Operatör Cemil Topuzlu Cad.", 40.967302, 29.056412, 40.963992, 29.065068),
        E("Kayseri · Kocasinan Bulvarı Karayolları Bölge Müdürlüğü Köprü → İlçe Jandarma Komutanlığı", 38.725402, 35.461091, 38.738915, 35.509945),
        E("Kayseri · Kocasinan Bulvarı Eski Sanayi Katılım → İlçe Jandarma Komutanlığı", 38.727911, 35.473989, 38.738915, 35.509945),
        E("Kayseri · Kocasinan Bulvarı İlçe Jandarma Komutanlığı → Küçükçalık Anadolu Lisesi Karşısı", 38.739023, 35.509755, 38.732345, 35.487518),
        E("Kayseri · Kocasinan Bulvarı Kocasinan İlçe Jandarma Komutanlığı → Gazi Osman Paşa Mahallesi", 38.739023, 35.509755, 38.727853, 35.473098),
        E("Kayseri · Kocasinan Bulvarı Kocasinan İlçe Jandarma Komutanlığı → Karayolları Bölge Müdürlüğü Köprü", 38.739023, 35.509755, 38.725618, 35.461321),
        E("Kayseri · Mustafa Kemal Paşa Bulvarı Gelincik Sokak → Yeşilırmak Cadde", 38.716857, 35.501055, 38.729741, 35.496071),
        E("Kayseri · Mustafa Kemal Paşa Bulvarı Gelincik Sokak → Kaski", 38.716857, 35.501055, 38.739087, 35.491561),
        E("Kayseri · Mustafa Kemal Paşa Bulvarı Kaski → Acıbadem Hastanesi", 38.738396, 35.491658, 38.715381, 35.500624),
        E("Kayseri · Kerkük Bulvarı Erciyes Üniversitesi Rektörlük → Talas", 38.709968, 35.532018, 38.697499, 35.53774),
        E("Kayseri · Kerkük Bulvarı Çimenli Caddesi (Tekden Koleji) → Erciyes Üniversitesi Rektörlük", 38.697069, 35.538117, 38.710868, 35.532091),
    )

    fun list(): List<Corridor> = raw.mapIndexed { i, e ->
        val f = LatLng(e.a, e.b)
        val t = LatLng(e.c, e.d)
        Corridor("egm$i", e.n, f, t, null, Geo.dist(f, t) * 1.1, "egm")
    }
}
