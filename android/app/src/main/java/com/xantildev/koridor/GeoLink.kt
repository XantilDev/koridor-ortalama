package com.xantildev.koridor

/**
 * geo: ve google.navigation: linklerini okur (saf mantık, testli).
 *  geo:38.25,33.55 · geo:38.25,33.55?q=38.25,33.55(Kampüs AVM) · geo:0,0?q=Kampüs AVM Konya
 *  google.navigation:q=38.25,33.55 · google.navigation:q=Kampüs+AVM+Konya&mode=d
 */
data class GeoLink(val ll: LatLng?, val query: String?, val label: String?) {
    companion object {
        private val reLL = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)")

        private fun dec(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s.replace('+', ' ')).trim()

        private fun ll(s: String): LatLng? {
            val m = reLL.find(s) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
            return LatLng(lat, lon)
        }

        private fun param(q: String, name: String): String? =
            q.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

        fun parse(text: String): GeoLink? {
            val t = text.trim()
            return when {
                t.startsWith("geo:", ignoreCase = true) -> {
                    val body = t.substring(4)
                    val path = body.substringBefore('?')
                    val query = if (body.contains('?')) body.substringAfter('?') else ""
                    val q = param(query, "q")?.let { dec(it) }
                    // q=lat,lon(Etiket) ya da q=adres; yoksa yoldaki lat,lon
                    val label = q?.let { Regex("\\(([^)]*)\\)").find(it)?.groupValues?.get(1)?.trim()?.ifBlank { null } }
                    val qLL = q?.let { ll(it) }
                    val pLL = ll(dec(path))
                    when {
                        qLL != null -> GeoLink(qLL, null, label)
                        pLL != null -> GeoLink(pLL, null, label ?: q?.takeIf { it.isNotBlank() })
                        !q.isNullOrBlank() -> GeoLink(null, q.substringBefore('(').trim().ifBlank { q }, label)
                        else -> null
                    }
                }
                t.startsWith("google.navigation:", ignoreCase = true) -> {
                    val body = t.substringAfter(':')
                    val q = (param(body, "q") ?: param(body, "ll"))?.let { dec(it) } ?: return null
                    ll(q)?.let { GeoLink(it, null, null) } ?: q.takeIf { it.isNotBlank() }?.let { GeoLink(null, it, null) }
                }
                else -> null
            }
        }
    }
}
