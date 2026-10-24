package tw.xiaoliangkou.app

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.core.Weather.Outing
import tw.xiaoliangkou.app.core.WeatherNow
import tw.xiaoliangkou.app.data.OpenMeteo
import tw.xiaoliangkou.app.data.WeatherException
import tw.xiaoliangkou.app.data.sharedHttp
import java.io.IOException
import java.util.Collections

/** 天氣：外出建議、提醒對方的話、Open-Meteo 的請求和回應 */
class WeatherTest {
    // ================================================================ 外出建議

    private fun tips(w: WeatherNow) = Weather.advice(w).tips.map { it.concern }

    @Test fun niceWeatherHasNothingToWorryAbout() {
        val a = Weather.advice(FakeWeather.NICE)
        assertEquals(Outing.GOOD, a.outing)
        assertTrue(a.tips.isEmpty())
        assertEquals("你那邊今天天氣不錯，有空出去走走吧 ☀️", Weather.reminder(a))
        assertEquals("heart", Weather.reminderKind(a))
    }

    @Test fun rainyColdDayPutsTheRainFirst() {
        val a = Weather.advice(FakeWeather.RAINY_COOL)
        assertEquals(Outing.CAREFUL, a.outing)
        assertEquals(listOf("正在下雨", "空氣品質對敏感族群不健康（AQI 118）", "有點涼（12°）", "有點霧（能見度 2.4 公里）"), tips(FakeWeather.RAINY_COOL))
        assertEquals("你那邊正在下雨、空氣品質對敏感族群不健康（AQI 118），記得帶傘、戴口罩，別在戶外運動喔 ☔", Weather.reminder(a))
        assertEquals("safe", Weather.reminderKind(a))
    }

    @Test fun sunnyHotDayWarnsAboutUvAndHeat() {
        val a = Weather.advice(FakeWeather.SUNNY_HOT)
        assertEquals(Outing.CAREFUL, a.outing)
        assertEquals(listOf("紫外線過量級（9）", "熱到 37°"), tips(FakeWeather.SUNNY_HOT))
        assertEquals("water", Weather.reminderKind(a))
    }

    @Test fun rainChanceThresholds() {
        val dry = FakeWeather.NICE
        assertEquals(listOf("可能會下雨（30%）"), tips(dry.copy(rainChance = 30)))
        assertEquals(listOf("降雨機率 60%"), tips(dry.copy(rainChance = 60)))
        assertTrue(tips(dry.copy(rainChance = 29)).isEmpty())
        assertEquals(Outing.STAY, Weather.advice(dry.copy(code = 95)).outing)
        assertEquals(Outing.STAY, Weather.advice(dry.copy(code = 82)).outing)
    }

    @Test fun airQualityLevels() {
        assertEquals("良好" to 0, Weather.aqiLevel(50))
        assertEquals("普通" to 1, Weather.aqiLevel(51))
        assertEquals("對敏感族群不健康" to 2, Weather.aqiLevel(101))
        assertEquals("對所有族群不健康" to 3, Weather.aqiLevel(151))
        assertEquals("危害" to 5, Weather.aqiLevel(301))
        assertTrue("普通不用特別提醒", tips(FakeWeather.NICE.copy(aqi = 99)).isEmpty())
        assertEquals(Outing.STAY, Weather.advice(FakeWeather.NICE.copy(aqi = 160)).outing)
    }

    @Test fun uvLevelsFollowTheWeatherBureau() {
        assertEquals("低量級", Weather.uvLevel(2.9))
        assertEquals("中量級", Weather.uvLevel(5.9))
        assertEquals("高量級", Weather.uvLevel(7.0))
        assertEquals("過量級", Weather.uvLevel(10.4))
        assertEquals("危險級", Weather.uvLevel(11.0))
        assertTrue("晚上沒有紫外線", tips(FakeWeather.NICE.copy(uvMax = 0.0)).isEmpty())
    }

    @Test fun visibilityWindHumidityAndTemperatureSwing() {
        val n = FakeWeather.NICE
        assertEquals(listOf("能見度只有 800 公尺"), tips(n.copy(visibilityM = 800.0)))
        assertEquals("10 公里以上", Weather.visibilityText(24_140.0))
        assertEquals("3 公里", Weather.visibilityText(3_000.0))
        assertEquals(listOf("風很大"), tips(n.copy(windKmh = 45.0)))
        assertEquals(Outing.STAY, Weather.advice(n.copy(windKmh = 70.0)).outing)
        assertEquals(listOf("很乾燥（濕度 30%）"), tips(n.copy(humidity = 30)))
        assertEquals(listOf("早晚溫差 9°"), tips(n.copy(high = 28.0, low = 19.0)))
        assertEquals("冷的時候不再說溫差", listOf("冷到 8°"), tips(n.copy(temp = 9.0, feelsLike = 8.0, high = 18.0, low = 8.0)))
    }

    @Test fun reminderFitsInAComfortAndUsesKnownKinds() {
        val worst = WeatherNow(temp = 2.0, code = 96, feelsLike = -3.0, humidity = 20, visibilityM = 100.0, windKmh = 80.0, rainChance = 100, uvMax = 12.0, high = 3.0, low = -4.0, aqi = 320)
        val a = Weather.advice(worst)
        assertEquals(Outing.STAY, a.outing)
        assertTrue(Weather.reminder(a).length <= 200)
        val kinds = listOf(worst, FakeWeather.SUNNY_HOT, FakeWeather.RAINY_COOL, FakeWeather.NICE).flatMap { Weather.advice(it).tips.map { t -> t.kind } } + "heart"
        kinds.forEach { assertTrue(it, Catalog.comfort(it) != null) }
    }

    @Test fun weatherCodesHaveWords() {
        assertEquals("☀️" to "晴天", Weather.describe(0))
        assertEquals("🌙" to "晴天", Weather.describe(0, isDay = false))
        assertEquals("🌧️" to "下雨", Weather.describe(63))
        assertEquals("⛈️" to "雷雨", Weather.describe(95))
        assertTrue(Weather.isRaining(80))
        assertFalse(Weather.isRaining(3))
    }

    // ================================================================ Open-Meteo

    /** 今天 0 點到明天 23 點，每小時一筆；現在是 15:15 */
    private fun forecast(current: String = "2026-10-24T15:15"): String {
        val times = (0 until 48).map { h -> "\"2026-10-${24 + h / 24}T%02d:00\"".format(h % 24) }
        // 早上 10 點的 95% 已經過去，明天早上 6 點（第 30 個小時）超過 12 小時，都不算
        val rain = (0 until 48).map { h -> when (h) { 10 -> 95; 20 -> 70; 30 -> 99; else -> 5 } }
        val uv = (0 until 48).map { h -> when (h) { 12 -> 11.0; 15 -> 6.2; 16 -> 5.0; else -> 0.0 } }
        return """{"latitude":25.03,"longitude":121.56,"timezone":"Asia/Taipei","current_units":{"temperature_2m":"°C"},""" +
            """"current":{"time":"$current","interval":900,"temperature_2m":27.3,"apparent_temperature":30.1,"relative_humidity_2m":71,"weather_code":2,"is_day":1,"wind_speed_10m":12.6,"visibility":24140.0},""" +
            """"hourly":{"time":[${times.joinToString(",")}],"precipitation_probability":[${rain.joinToString(",")}],"uv_index":[${uv.joinToString(",")}],"visibility":[${(0 until 48).joinToString(",") { "20000.0" }}]},""" +
            """"daily":{"time":["2026-10-24","2026-10-25"],"temperature_2m_max":[29.1,28.0],"temperature_2m_min":[22.4,21.9]}}"""
    }

    private val air = """{"latitude":25.0,"longitude":121.5,"current_units":{"us_aqi":"USAQI"},"current":{"time":"2026-10-24T15:00","interval":3600,"us_aqi":57,"pm2_5":15.3}}"""

    @Test fun parsesTheNextTwelveHours() {
        val w = OpenMeteo.parseForecast(forecast(), 1234)
        assertEquals(27.3, w.temp, 0.0)
        assertEquals(30.1, w.feelsLike!!, 0.0)
        assertEquals(71, w.humidity)
        assertEquals(2, w.code)
        assertTrue(w.isDay)
        assertEquals(24140.0, w.visibilityM!!, 0.0)
        assertEquals(70, w.rainChance)
        assertEquals(6.2, w.uvMax!!, 0.0)
        assertEquals(29.1, w.high!!, 0.0)
        assertEquals(22.4, w.low!!, 0.0)
        assertEquals(1234L, w.fetchedAt)
        assertEquals(57 to 15.3, OpenMeteo.parseAir(air))
        assertNull(OpenMeteo.parseAir("""{"current":{"time":"2026-10-24T15:00"}}"""))
        assertNull(OpenMeteo.parseAir("<html>"))
    }

    @Test fun badForecastIsAnError() {
        for (bad in listOf("<html>oops</html>", """{"error":true,"reason":"Daily API request limit exceeded"}""", """{"current":{"time":"2026-10-24T15:15"}}""")) {
            try {
                OpenMeteo.parseForecast(bad, 0)
                fail(bad)
            } catch (e: WeatherException) {
                assertEquals("天氣資料看不懂", e.message)
            }
        }
    }

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    private class Answer(val code: Int, val body: String)

    /** 假的 Open-Meteo：從真正用的 [sharedHttp] 長出來；回傳 null＝連不上 */
    private fun http(answer: (Request) -> Answer?): OkHttpClient = sharedHttp.newBuilder().addInterceptor { chain ->
        val req = chain.request()
        requests += req
        val a = answer(req) ?: throw IOException("offline")
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(a.code).message("X").body(a.body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private var now = 1_000_000L

    private fun meteo(answer: (Request) -> Answer?) = OpenMeteo(http(answer), clock = { now })

    @Test fun asksForRoundedCoordinatesAndRemembersThem() = runBlocking<Unit> {
        val m = meteo { if (it.url.host.startsWith("air-quality")) Answer(200, air) else Answer(200, forecast()) }
        val w = m.now(25.033964, 121.564468)
        assertEquals(57, w.aqi)
        assertEquals(15.3, w.pm25!!, 0.0)
        val f = requests.first { it.url.host == "api.open-meteo.com" }.url
        assertEquals("/v1/forecast", f.encodedPath)
        assertEquals("25.03", f.queryParameter("latitude"))
        assertEquals("121.56", f.queryParameter("longitude"))
        assertEquals("auto", f.queryParameter("timezone"))
        assertTrue(f.queryParameter("current")!!.split(",").containsAll(listOf("temperature_2m", "visibility", "weather_code")))
        assertTrue(f.queryParameter("hourly")!!.split(",").containsAll(listOf("precipitation_probability", "uv_index")))
        val a = requests.first { it.url.host == "air-quality-api.open-meteo.com" }.url
        assertEquals("25.03", a.queryParameter("latitude"))
        assertEquals("us_aqi,pm2_5", a.queryParameter("current"))
        requests.forEach { r ->
            assertNull("不帶 Supabase 的金鑰", r.header("apikey"))
            assertNull(r.header("Authorization"))
        }

        // 1 公里內、20 分鐘內不再問
        val asked = requests.size
        m.now(25.0341, 121.5639)
        assertEquals(asked, requests.size)
        now += OpenMeteo.TTL_MS
        m.now(25.0341, 121.5639)
        assertEquals(asked + 2, requests.size)
    }

    @Test fun airQualityIsOptional() = runBlocking<Unit> {
        val w = meteo { if (it.url.host.startsWith("air-quality")) Answer(500, "{}") else Answer(200, forecast()) }.now(25.0, 121.5)
        assertEquals(27.3, w.temp, 0.0)
        assertNull(w.aqi)
        val offline = meteo { if (it.url.host.startsWith("air-quality")) null else Answer(200, forecast()) }.now(25.0, 121.5)
        assertNull(offline.aqi)
    }

    @Test fun forecastFailuresSayWhatHappened() = runBlocking<Unit> {
        suspend fun message(answer: Answer?) = try {
            meteo { answer }.now(25.0, 121.5)
            null
        } catch (e: WeatherException) {
            e.message
        }
        assertEquals("連不到天氣資料", message(null))
        assertEquals("天氣資料今天查太多次了，等一下再試", message(Answer(429, """{"error":true,"reason":"Daily API request limit exceeded"}""")))
        assertEquals("天氣資料暫時有點狀況，等一下再試", message(Answer(502, "")))
        // 失敗的不記：恢復了就查得到
        var up = false
        val m = meteo { if (up) Answer(200, if (it.url.host.startsWith("air")) air else forecast()) else null }
        try { m.now(25.0, 121.5); fail() } catch (e: WeatherException) {}
        up = true
        assertEquals(27.3, m.now(25.0, 121.5).temp, 0.0)
    }
}
