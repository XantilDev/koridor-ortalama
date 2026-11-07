package com.xantildev.koridor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Google Play Hizmetleri olmayan cihazlarda (ör. BYD araç ekranı) Google haritası:
 * Maps JavaScript API bir WebView içinde. Panelin web anahtarı kullanılır; sayfa panel adresinden
 * yüklenmiş gibi açıldığı için anahtarın site kısıtı geçerlidir. Her açılış 1 harita yüklemesi sayılır.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class WebMapHolder(ctx: Context) : LiveMapView {
    private val wv = WebView(ctx)
    override val view: android.view.View get() = wv
    override var onUserPan: () -> Unit = {}
    override var navMode = false
    override var rightInsetPx = 0

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val heading = HeadingTracker()
    private var ready = false
    private var last: Triple<UiState, Int, Boolean>? = null

    // Çizilenlerin anahtarları (değişmeyeni tekrar göndermemek için)
    private var drawnRoute: Any? = null
    private var fittedTarget: Places.Place? = null
    private var corrKey = -1
    private var altsKey: Any? = null
    private var altIdx = -1
    private var trailSize = 0
    private var posKey = ""
    private var nightKey: Boolean? = null
    private var hadFix = false

    inner class Bridge {
        @JavascriptInterface fun pan() { main.post { onUserPan() } }
        @JavascriptInterface fun alt(i: Int) { main.post { RouteSession.selectAlt(i) } }
        @JavascriptInterface fun ready() { main.post { ready = true; reset(); last?.let { (s, v, f) -> update(s, v, f) } } }
        @JavascriptInterface fun fail() { main.post { MapChoice.webFailed = true } }
    }

    init {
        // Harita bütün alanı kaplasın (AndroidView içinde WebView kendi içeriği kadar kalmasın)
        wv.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        wv.loadDataWithBaseURL("https://personel.aysoftbilisim.com/arac-harita/", HTML.replace("__KEY__", KEY), "text/html", "utf-8", null)
    }

    private fun reset() {
        drawnRoute = null; corrKey = -1; altsKey = null; altIdx = -1; trailSize = 0; posKey = ""; nightKey = null; hadFix = false
    }

    private fun js(code: String) { if (ready) wv.evaluateJavascript(code, null) }

    private fun pts(l: List<LatLng>): String {
        val sb = StringBuilder("[")
        l.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(String.format(java.util.Locale.US, "%.5f,%.5f", p.lat, p.lon)).append(']')
        }
        return sb.append(']').toString()
    }

    private fun q(t: String) = org.json.JSONObject.quote(t)

    override fun update(s: UiState, ver: Int, follow: Boolean) {
        last = Triple(s, ver, follow)
        if (!ready) return
        val n = AppSettings.isNight()
        if (n != nightKey) { nightKey = n; js("setNight($n)") }
        // Rota
        val ri = RouteSession.info
        if (ri !== drawnRoute) {
            drawnRoute = ri
            if (ri != null && ri.line.size >= 2) {
                val fit = fittedTarget != ri.target
                fittedTarget = ri.target
                js("setRoute(${pts(ri.line)},${pts(listOf(ri.start))},${pts(listOf(ri.target.ll))},$fit)")
            } else js("setRoute([],null,null,false)")
        }
        // Koridorlar (rotadakiler rotanın etrafında kırmızı kenar)
        val sim = s.mode == "sim"
        val ck = (ver * 2 + if (sim) 1 else 0) * 31 + System.identityHashCode(ri)
        if (ck != corrKey) {
            corrKey = ck
            val base = if (ri != null) CorridorStore.all().filter { c ->
                ri.corridors.any { it.key == c.key } || (Geo.distToLine(c.from, ri.line) < 400 && Geo.distToLine(c.to, ri.line) < 400)
            } else CorridorStore.all()
            val list = base + if (sim) listOf(CorridorStore.withOverride(Engine.DEMO)) else emptyList()
            val sb = StringBuilder("[")
            list.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                val sl = ri?.slice(c.from, c.to)
                sb.append("{\"f\":").append(pts(listOf(c.from)).removePrefix("[").removeSuffix("]"))
                if (sl != null) sb.append(",\"s\":").append(pts(sl)) else if (c.path.size >= 2) sb.append(",\"p\":").append(pts(c.path))
                sb.append(",\"t\":").append(q(c.name + " · " + (c.limit?.let { "limit $it" } ?: "limit ?"))).append('}')
            }
            js("setCorr(${sb.append(']')})")
        }
        // Rota seçenekleri
        val alts = RouteSession.alts
        if (alts !== altsKey || RouteSession.altIndex != altIdx) {
            altsKey = alts; altIdx = RouteSession.altIndex
            js("setAlts([${alts.joinToString(",") { pts(it.line) }}],${RouteSession.altIndex})")
        }
        // Geçilen iz (yalnız yeni noktalar)
        val tr = s.track
        if (tr.size != trailSize) {
            if (tr.size < trailSize || trailSize == 0) js("setTrail(${pts(tr)},true)")
            else js("setTrail(${pts(tr.subList(trailSize, tr.size))},false)")
            trailSize = tr.size
        }
        // Konum ve kamera
        val p = s.pos
        if (p != null) {
            val h = heading.feed(p, s.speedKmh)
            val nav = navMode && AppSettings.headingUp && s.driving
            val inset = (rightInsetPx / density).toInt()
            val key = "${p.lat},${p.lon},$h,$follow,$nav,$inset"
            if (key != posKey) {
                posKey = key
                js("setPos(${p.lat},${p.lon},${h ?: "null"},$follow,$nav,$inset,${!hadFix})")
                hadFix = true
            }
        } else if (hadFix) { hadFix = false; js("clearPos()") }
    }

    override fun recenter(s: UiState) {
        val p = s.pos ?: return
        val nav = navMode && AppSettings.headingUp && s.driving
        js("setPos(${p.lat},${p.lon},${heading.deg ?: "null"},true,$nav,${(rightInsetPx / density).toInt()},false)")
    }

    override fun zoomIn() = js("map.setZoom(map.getZoom()+1)")
    override fun zoomOut() = js("map.setZoom(map.getZoom()-1)")
    override fun resume() { wv.onResume() }
    override fun pause() { wv.onPause() }

    companion object {
        /** Panelin web anahtarı (yalnız personel.aysoftbilisim.com sitesinden geçerli; zaten sitede açık). */
        const val KEY = "AIzaSyCkcKg0t29DyA8qblqUYCqSsPiIEe-soCk"

        private val HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;overflow:hidden;background:#e9e5dc}
#wrap{position:absolute;left:0;top:0;width:100%;height:100%}#map{width:100%;height:100%}
.night #map{filter:invert(.92) hue-rotate(180deg) brightness(.9)}</style></head>
<body><div id="wrap"><div id="map"></div></div>
<script>
var map,vec=false,objs={route:[],corr:[],alts:[]},trail=null,you=null,lastNav=false;
function gm_authFailure(){Android.fail()}
function P(a){return {lat:a[0],lng:a[1]}}
function clear(k){objs[k].forEach(function(o){o.setMap(null)});objs[k]=[]}
var lw=0,lh=0;
function size(){var w=document.documentElement.clientWidth,h=document.documentElement.clientHeight,el=document.getElementById('wrap');
 if(vec){el.style.cssText='position:absolute;left:0;top:0;width:100%;height:100%'}
 else{var d=Math.ceil(Math.sqrt(w*w+h*h));el.style.cssText='position:absolute;width:'+d+'px;height:'+d+'px;left:'+((w-d)/2)+'px;top:'+((h-d)/2)+'px;transform:'+(el.style.transform||'')}
 if(map&&(w!=lw||h!=lh)){lw=w;lh=h;google.maps.event.trigger(map,'resize')}}
setInterval(size,1000);
function dot(p,fill,stroke,r,z){return new google.maps.Marker({map:map,position:p,zIndex:z,clickable:false,
 icon:{path:google.maps.SymbolPath.CIRCLE,scale:r,fillColor:fill,fillOpacity:1,strokeColor:stroke,strokeWeight:3}})}
function init(){
 map=new google.maps.Map(document.getElementById('map'),{center:{lat:39,lng:35},zoom:6,mapId:'DEMO_MAP_ID',renderingType:'VECTOR',
  disableDefaultUI:true,clickableIcons:false,gestureHandling:'greedy',keyboardShortcuts:false});
 map.addListener('dragstart',function(){Android.pan()});
 var done=false;function go(){if(done)return;done=true;vec=map.getRenderingType&&map.getRenderingType()==='VECTOR';size();
  google.maps.event.trigger(map,'resize');Android.ready()}
 map.addListener('renderingtype_changed',function(){if(map.getRenderingType()!=='UNINITIALIZED')go()});
 google.maps.event.addListenerOnce(map,'tilesloaded',go);setTimeout(go,4000);
 addEventListener('resize',size);size();
}
function setNight(n){document.body.className=n?'night':''}
function setRoute(line,s,e,fit){clear('route');if(line.length<2)return;var r=line.map(P);
 objs.route.push(new google.maps.Polyline({map:map,path:r,strokeColor:'#0B57D0',strokeWeight:12,zIndex:10}));
 objs.route.push(new google.maps.Polyline({map:map,path:r,strokeColor:'#4A8CFF',strokeWeight:8,zIndex:11}));
 objs.route.push(dot(P(s[0]),'#2FBF71','#fff',7,15));objs.route.push(dot(P(e[0]),'#4DA3FF','#fff',8,15));
 if(fit){var b=new google.maps.LatLngBounds();r.forEach(function(p){b.extend(p)});map.fitBounds(b,60)}}
function setCorr(list){clear('corr');list.forEach(function(c){
 if(c.s)objs.corr.push(new google.maps.Polyline({map:map,path:c.s.map(P),strokeColor:'#E5322D',strokeWeight:17,zIndex:9}));
 else if(c.p)objs.corr.push(new google.maps.Polyline({map:map,path:c.p.map(P),strokeColor:'#D62B2B',strokeOpacity:.7,strokeWeight:5,zIndex:12}));
 var m=dot(P(c.f),'#ffffff','#D62B2B',5,13);m.setTitle(c.t);objs.corr.push(m)})}
function setAlts(list,sel){clear('alts');list.forEach(function(l,i){if(i==sel)return;
 var pl=new google.maps.Polyline({map:map,path:l.map(P),strokeColor:'#8A96A3',strokeOpacity:.85,strokeWeight:8,zIndex:5});
 pl.addListener('click',function(){Android.alt(i)});objs.alts.push(pl)})}
function setTrail(pts,reset){if(!trail)trail=new google.maps.Polyline({map:map,path:[],strokeColor:'#F2B632',strokeOpacity:.6,strokeWeight:4,zIndex:14});
 var p=trail.getPath();if(reset)p.clear();pts.forEach(function(a){p.push(new google.maps.LatLng(a[0],a[1]))})}
function move(p,brg,d){var b=brg*Math.PI/180;return {lat:p.lat+d*Math.cos(b)/111320,lng:p.lng+d*Math.sin(b)/(111320*Math.cos(p.lat*Math.PI/180))}}
function clearPos(){if(you){you.setMap(null);you=null}}
function setPos(lat,lon,h,follow,nav,inset,first){var pos={lat:lat,lng:lon};
 if(!you)you=new google.maps.Marker({map:map,zIndex:20,clickable:false});you.setPosition(pos);
 you.setIcon(h==null?{path:google.maps.SymbolPath.CIRCLE,scale:8,fillColor:'#F2B632',fillOpacity:1,strokeColor:'#fff',strokeWeight:3}
  :{path:google.maps.SymbolPath.FORWARD_CLOSED_ARROW,scale:6,rotation:h,fillColor:'#2F7BFF',fillOpacity:1,strokeColor:'#fff',strokeWeight:2});
 if(!follow&&!first){if(!nav&&lastNav){lastNav=false}return}
 var z=first?(nav?17:15):(map.getZoom()<12?16.5:map.getZoom());
 var hh=(nav&&h!=null)?h:0,mpp=156543.03*Math.cos(lat*Math.PI/180)/Math.pow(2,z);
 var c=move(pos,hh,(nav?innerHeight*0.25:0)*mpp);c=move(c,hh+90,(inset/2)*mpp);
 if(vec){map.moveCamera({center:c,zoom:z,heading:nav?hh:0,tilt:nav?45:0})}
 else{map.setCenter(c);if(map.getZoom()!=z)map.setZoom(z);document.getElementById('wrap').style.transform='rotate('+(nav?-hh:0)+'deg)'}
 lastNav=nav}
</script>
<script async src="https://maps.googleapis.com/maps/api/js?key=__KEY__&callback=init&v=weekly&language=tr&region=TR"></script>
</body></html>
"""
    }
}
