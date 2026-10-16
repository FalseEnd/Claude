package tw.xiaoliangkou.app.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.xiaoliangkou.app.core.Geo
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.log2

/** 搜尋到的地點：[title] 顯示在第一行，[subtitle] 是補充的地址（可能是空的）；[zoom] 是地圖要放大到多少（門牌 17、行政區小一點） */
data class PlaceHit(val title: String, val subtitle: String, val lat: Double, val lng: Double, val zoom: Double = 17.0)

/** 搜尋地址或地點；畫面測試時換成假的 */
fun interface PlaceSearcher {
    /** 找不到回傳空清單；連不上網路之類的錯誤丟 IOException。[near] 是地圖目前的中心，附近的結果排前面 */
    suspend fun search(query: String, near: LatLng?): List<PlaceHit>
}

/**
 * 地圖的地址搜尋，不需要任何金鑰：
 * 1. 先用手機內建的 Geocoder（有 Google 服務的手機用的是 Google 的地址資料，台灣的門牌最準）
 * 2. 手機沒有、出錯或找不到，再問 OpenStreetMap 的 Nominatim。
 *    使用規定：要帶 App 名稱、一秒最多一次，而且不能邊打字邊查，所以只在按下搜尋時查。
 */
class PlaceSearch(
    /** 手機沒有 Geocoder 就回傳 null */
    private val geocoder: () -> Geocoder?,
    private val userAgent: String,
    private val http: OkHttpClient = searchHttp,
    /** Nominatim 規定一秒最多查一次 */
    private val nominatimIntervalMs: Long = 1_100,
) : PlaceSearcher {
    constructor(context: Context, userAgent: String) :
        this({ if (Geocoder.isPresent()) Geocoder(context, Locale.TAIWAN) else null }, userAgent)

    private val nominatimLock = Mutex()
    private var lastNominatim = 0L

    /** 最近查過的結果（Nominatim 也要求重複的查詢要自己記住，不要一直問） */
    private val recent = object : LinkedHashMap<String, List<PlaceHit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceHit>>?) = size > 30
    }

    override suspend fun search(query: String, near: LatLng?): List<PlaceHit> {
        val q = query.trim().take(200)
        if (q.isEmpty()) return emptyList()
        // 地圖附近的結果會排前面，所以記的時候也把大概的位置（約 10 公里）算進去
        val key = q + near?.let { String.format(Locale.US, "@%.1f,%.1f", it.lat, it.lng) }.orEmpty()
        synchronized(recent) { recent[key] }?.let { return it }
        // 找不到的不記，網路恢復後再按一次就會重查
        return lookUp(q, near).also { hits -> if (hits.isNotEmpty()) synchronized(recent) { recent[key] = hits } }
    }

    private suspend fun lookUp(q: String, near: LatLng?): List<PlaceHit> {
        val phone = try {
            fromPhone(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 手機查不了（沒有 Google 服務、沒網路、服務出錯），改問 Nominatim
        }
        if (!phone.isNullOrEmpty()) return phone
        return try {
            nominatim(q, near)
        } catch (e: IOException) {
            // 手機有查、只是沒找到，就當作找不到；兩邊都連不上才算失敗
            if (phone != null) emptyList() else throw e
        }
    }

    private suspend fun fromPhone(q: String): List<PlaceHit>? {
        val g = geocoder() ?: return null
        val found: List<Address> = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.getFromLocationName(q, MAX_RESULTS, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                        override fun onError(errorMessage: String?) = cont.resumeWithException(IOException(errorMessage ?: "Geocoder"))
                    })
                } else {
                    // 舊版只有會卡住的寫法，放到另一條執行緒
                    thread(isDaemon = true, name = "geocoder") {
                        @Suppress("DEPRECATION")
                        cont.resumeWith(runCatching { g.getFromLocationName(q, MAX_RESULTS).orEmpty() })
                    }
                }
            }
        } ?: return null
        return dedupe(found.mapNotNull { it.toHit() })
    }

    private suspend fun nominatim(q: String, near: LatLng?): List<PlaceHit> {
        for (v in nominatimQueries(q)) {
            val hits = nominatimOnce(v.query, near)
            if (hits.isEmpty()) continue
            return when (v.kind) {
                QueryKind.AS_TYPED -> dedupe(hits)
                // 改寫過的門牌：第一行顯示找到的地址，不是那個地址上的店名
                QueryKind.ADDRESS -> dedupe(hits.map { it.addressFirst() })
                // 只找到路：帶到最符合的那一段就好
                QueryKind.ROAD -> listOf(hits.first().addressFirst().copy(subtitle = "找不到這個門牌，先帶你到這條路"))
            }
        }
        return emptyList()
    }

    private suspend fun nominatimOnce(q: String, near: LatLng?): List<PlaceHit> = nominatimLock.withLock {
        val wait = lastNominatim + nominatimIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val url = NOMINATIM.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("limit", MAX_RESULTS.toString())
            .addQueryParameter("accept-language", "zh-TW")
            // 地圖附近的結果優先，但不限定範圍（出去玩也查得到）
            .apply { near?.let { addQueryParameter("viewbox", String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", it.lng - 0.3, it.lat + 0.3, it.lng + 0.3, it.lat - 0.3)) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("Nominatim HTTP ${res.code}")
                    parseNominatim(res.body?.string().orEmpty())
                }
            }
        } finally {
            lastNominatim = System.currentTimeMillis()
        }
    }

    companion object {
        const val MAX_RESULTS = 5
        private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
        private val searchHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        private val json = Json { ignoreUnknownKeys = true }

        /** 「110台灣台北市信義區市府路45號」→「台北市信義區市府路45號」 */
        fun tidyAddress(s: String): String {
            val zip = Regex("^\\d{3,6}\\s*")
            return s.trim().replace(zip, "").removePrefix("台灣").removePrefix("臺灣").trim().replace(zip, "")
        }

        /** 手機 Geocoder 的結果：有名稱（例如「台北101」）就當標題，地址放第二行；只有門牌就直接顯示地址 */
        internal fun Address.toHit(): PlaceHit? {
            if (!hasLatitude() || !hasLongitude()) return null
            val line = tidyAddress(
                getAddressLine(0) ?: listOfNotNull(adminArea, subAdminArea, locality, subLocality, thoroughfare, subThoroughfare).distinct().joinToString(""),
            )
            val feature = featureName?.trim().orEmpty()
            val named = feature.length >= 2 && feature.any { !it.isDigit() && it !in "-之號" } && !line.contains(feature)
            // 只有縣市或鄉鎮市區（沒有路名、門牌、店名）就不要放太大
            val zoom = when {
                named || thoroughfare != null || subThoroughfare != null || premises != null -> 17.0
                subLocality != null || locality != null || subAdminArea != null -> 13.0
                adminArea != null -> 10.0
                else -> 17.0
            }
            return when {
                named -> PlaceHit(feature, line, latitude, longitude, zoom)
                line.isNotEmpty() -> PlaceHit(line, "", latitude, longitude, zoom)
                feature.isNotEmpty() -> PlaceHit(feature, "", latitude, longitude, zoom)
                else -> null
            }
        }

        /** Nominatim 的回應（jsonv2 + addressdetails） */
        fun parseNominatim(body: String): List<PlaceHit> {
            val list = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
            return list.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = o.text("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = o.text("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                val parts = o.text("display_name").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val title = o.text("name")?.takeIf { it.isNotBlank() } ?: parts.firstOrNull() ?: return@mapNotNull null
                val address = (o["address"] as? JsonObject)?.let(::nominatimAddress)
                    ?: parts.filter { it != title }.joinToString(", ")
                PlaceHit(title, address.takeIf { it != title }.orEmpty(), lat, lng, zoomFor(o["boundingbox"] as? JsonArray))
            }
        }

        /** 台灣的地址照中文順序排：「臺北市信義區信義路五段7號」；其他國家回傳 null，改用 Nominatim 的 display_name */
        private fun nominatimAddress(a: JsonObject): String? {
            if (a.text("country_code") != "tw") return null
            val county = a.text("county")
            val city = a.text("city")?.takeIf { it != county }
            val district = listOf("city_district", "suburb", "town", "district").firstNotNullOfOrNull { a.text(it) }
            val road = a.text("road")
            val number = a.text("house_number")?.let { if (road != null) "${it}號" else null }
            return listOfNotNull(county, city, district, road, number).distinct().joinToString("").ifEmpty { null }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** 依範圍大小決定放大多少，讓整個地方剛好看得到：大樓 17、行政區約 12、整個城市約 10 */
        private fun zoomFor(box: JsonArray?): Double {
            val v = box?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: return 17.0
            val span = maxOf(v[1] - v[0], v[3] - v[2])
            if (span <= 0) return 17.0
            return floor(log2(360 / span)).coerceIn(5.0, 17.0)
        }

        /**
         * Nominatim 看不懂連在一起的台灣地址，要改成「小到大、逗號分開」才找得到：
         * 「台北市信義區市府路45號3樓」→ 先查原文，再查「45, 市府路, 信義區, 台北市」，
         * 地圖資料裡沒有這個門牌的話，最後退到路名「市府路, 信義區, 台北市」。
         */
        fun nominatimQueries(q: String): List<NominatimQuery> {
            val out = mutableListOf(NominatimQuery(q, QueryKind.AS_TYPED))
            val m = TW_ADDRESS.matchEntire(q.replace(Regex("\\s+"), "")) ?: return out
            val (city, district, road, lane, alley, number) = m.destructured
            if (road.isEmpty()) return out
            val street = road + lane + alley
            val area = listOf(district, city).filter { it.isNotEmpty() }
            if (number.isNotEmpty()) out += NominatimQuery((listOf(number, street) + area).joinToString(", "), QueryKind.ADDRESS)
            out += NominatimQuery((listOf(street) + area).joinToString(", "), if (number.isEmpty()) QueryKind.ADDRESS else QueryKind.ROAD)
            return out.distinctBy { it.query }
        }

        // 郵遞區號、國名、縣市、鄉鎮市區、路／街（含段）、巷、弄、號；後面的樓層之類的忽略
        private val TW_ADDRESS = Regex(
            "^(?:\\d{3,6})?(?:(?:臺灣|台灣)(?=\\p{IsHan}{2}[縣市]))?(\\p{IsHan}{2}[縣市])?(\\p{IsHan}{1,3}?[區鄉鎮市])?" +
                "(\\p{IsHan}+?(?:路|街|大道)(?:[一二三四五六七八九十]+段)?)?(\\d+巷)?(\\d+弄)?(?:(\\d+(?:之\\d+)?)號)?.*$",
        )

        /** 同名又很近的（同一棟樓的不同資料）只留一個 */
        fun dedupe(hits: List<PlaceHit>): List<PlaceHit> = hits.fold(mutableListOf<PlaceHit>()) { kept, h ->
            if (kept.none { it.title == h.title && Geo.distance(it.lat, it.lng, h.lat, h.lng) < 500 }) kept += h
            kept
        }.take(MAX_RESULTS)

        private fun PlaceHit.addressFirst() =
            if (subtitle.isEmpty()) this else copy(title = subtitle, subtitle = if (subtitle.contains(title)) "" else title)
    }

    enum class QueryKind { AS_TYPED, ADDRESS, ROAD }
    data class NominatimQuery(val query: String, val kind: QueryKind)
}
