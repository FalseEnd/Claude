package tw.xiaoliangkou.app.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tw.xiaoliangkou.app.core.WeatherNow
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** 抓不到天氣：連不上，或回了看不懂的東西 */
class WeatherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 查某個地方的天氣；畫面測試時換成假的 */
interface WeatherSource {
    /** 失敗丟 [WeatherException] */
    suspend fun now(lat: Double, lng: Double): WeatherNow

    /** 那裡大概是哪（「台北市大安區」），查不到是 null */
    suspend fun area(lat: Double, lng: Double): String? = null
}

/**
 * Open-Meteo（https://open-meteo.com/）的預報和空氣品質：免費、不用 API key，資料是 CC BY 4.0（畫面上要寫來源）。
 * 只送出四捨五入到小數點兩位（約 1 公里）的座標，查過的地方 [TTL_MS] 內不再問一次。
 * 空氣品質查不到時天氣照樣顯示（[WeatherNow.aqi] 是 null）
 */
class OpenMeteo(
    /** 不帶 Supabase 的金鑰和登入的 token */
    private val http: OkHttpClient = sharedHttp,
    private val geocoder: () -> Geocoder? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) : WeatherSource {
    constructor(context: Context) : this(geocoder = { if (Geocoder.isPresent()) Geocoder(context, Locale.TAIWAN) else null })

    private val cache = HashMap<String, WeatherNow>()
    private val areas = HashMap<String, String?>()

    private fun key(lat: Double, lng: Double) = "${round(lat)},${round(lng)}"

    override suspend fun now(lat: Double, lng: Double): WeatherNow {
        val k = key(lat, lng)
        synchronized(cache) { cache[k] }?.takeIf { clock() - it.fetchedAt < TTL_MS }?.let { return it }
        val w = coroutineScope {
            val air = async {
                try {
                    parseAir(get(airUrl(round(lat), round(lng))))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val forecast = parseForecast(get(forecastUrl(round(lat), round(lng))), clock())
            val a = air.await()
            forecast.copy(aqi = a?.first, pm25 = a?.second)
        }
        synchronized(cache) { cache[k] = w }
        return w
    }

    override suspend fun area(lat: Double, lng: Double): String? {
        val k = key(lat, lng)
        synchronized(areas) { if (areas.containsKey(k)) return areas[k] }
        val g = geocoder() ?: return null
        val found: List<Address>? = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                        override fun onError(errorMessage: String?) = cont.resume(emptyList())
                    })
                } else {
                    thread(isDaemon = true, name = "geocoder") {
                        @Suppress("DEPRECATION")
                        cont.resume(runCatching { g.getFromLocation(lat, lng, 1).orEmpty() }.getOrDefault(emptyList()))
                    }
                }
            }
        }
        val name = found?.firstOrNull()?.let(::areaOf)
        // 查不到（逾時、沒網路）不記，下次再查
        if (found != null) synchronized(areas) { areas[k] = name }
        return name
    }

    private fun forecastUrl(lat: String, lng: String): HttpUrl = FORECAST.toHttpUrl().newBuilder()
        .addQueryParameter("latitude", lat).addQueryParameter("longitude", lng)
        .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,visibility")
        .addQueryParameter("hourly", "precipitation_probability,uv_index,visibility")
        .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
        .addQueryParameter("timezone", "auto")
        .addQueryParameter("forecast_days", "2")
        .build()

    private fun airUrl(lat: String, lng: String): HttpUrl = AIR.toHttpUrl().newBuilder()
        .addQueryParameter("latitude", lat).addQueryParameter("longitude", lng)
        .addQueryParameter("current", "us_aqi,pm2_5")
        .addQueryParameter("timezone", "auto")
        .build()

    private suspend fun get(url: HttpUrl): String {
        val (code, text) = try {
            fetch(url) { it.code to it.body?.string().orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw WeatherException("連不到天氣資料", e)
        }
        if (code == 429) throw WeatherException("天氣資料今天查太多次了，等一下再試")
        if (code !in 200..299) throw WeatherException("天氣資料暫時有點狀況，等一下再試")
        return text
    }

    private suspend fun <T> fetch(url: HttpUrl, read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        val call = http.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use(read)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(r)
            }
        })
    }

    companion object {
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        private const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
        /** 查過的地方記多久：20 分鐘（預報每 15 分鐘更新） */
        const val TTL_MS = 20 * 60 * 1000L
        /** 降雨機率、紫外線看接下來幾個小時 */
        const val HOURS = 12

        private fun round(v: Double) = String.format(Locale.US, "%.2f", v)

        private fun JsonObject.num(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.list(name: String) = (this[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }

        /** 預報的回應 → [WeatherNow]（還沒有空氣品質） */
        internal fun parseForecast(text: String, now: Long): WeatherNow {
            val root = try {
                Api.json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw WeatherException("天氣資料看不懂", e)
            }
            val cur = root["current"] as? JsonObject ?: throw WeatherException("天氣資料看不懂")
            val temp = cur.num("temperature_2m") ?: throw WeatherException("天氣資料看不懂")
            val code = (cur["weather_code"] as? JsonPrimitive)?.intOrNull ?: cur.num("weather_code")?.toInt() ?: 0

            // 接下來 12 小時：從現在這個小時開始（時間是當地時間，和 current.time 比）
            val hourly = root["hourly"] as? JsonObject
            val times = (hourly?.get("time") as? JsonArray)?.map { (it as? JsonPrimitive)?.content }.orEmpty()
            val curHour = (cur["time"] as? JsonPrimitive)?.content?.let { runCatching { LocalDateTime.parse(it).withMinute(0) }.getOrNull() }
            val start = curHour?.let { h -> times.indexOfFirst { t -> t != null && runCatching { !LocalDateTime.parse(t).isBefore(h) }.getOrDefault(false) } }
                ?.takeIf { it >= 0 } ?: 0
            fun next(name: String) = hourly?.list(name)?.drop(start)?.take(HOURS)?.filterNotNull().orEmpty()
            val rain = next("precipitation_probability").maxOrNull()?.roundToInt()
            val uv = next("uv_index").maxOrNull()

            val daily = root["daily"] as? JsonObject
            return WeatherNow(
                temp = temp,
                code = code,
                isDay = cur.num("is_day")?.let { it > 0.5 } ?: true,
                feelsLike = cur.num("apparent_temperature"),
                humidity = cur.num("relative_humidity_2m")?.roundToInt(),
                visibilityM = cur.num("visibility") ?: hourly?.list("visibility")?.getOrNull(start),
                windKmh = cur.num("wind_speed_10m"),
                rainChance = rain,
                uvMax = uv,
                high = daily?.list("temperature_2m_max")?.firstOrNull(),
                low = daily?.list("temperature_2m_min")?.firstOrNull(),
                fetchedAt = now,
            )
        }

        /** 空氣品質的回應 → (AQI, PM2.5) */
        internal fun parseAir(text: String): Pair<Int?, Double?>? {
            val cur = runCatching { Api.json.parseToJsonElement(text).jsonObject["current"] as? JsonObject }.getOrNull() ?: return null
            val aqi = cur.num("us_aqi")?.roundToInt()
            return if (aqi == null) null else aqi to cur.num("pm2_5")
        }

        /** 「台北市大安區」：縣市＋區；沒有區就只有縣市 */
        internal fun areaOf(a: Address): String? {
            val city = a.adminArea ?: a.subAdminArea
            val district = a.locality ?: a.subLocality ?: a.subAdminArea?.takeIf { it != city }
            return listOfNotNull(city, district?.takeIf { it != city }).joinToString("").ifEmpty { null }
        }
    }
}
