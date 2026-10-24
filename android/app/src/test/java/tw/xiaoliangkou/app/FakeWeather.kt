package tw.xiaoliangkou.app

import kotlinx.coroutines.awaitCancellation
import tw.xiaoliangkou.app.core.WeatherNow
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.WeatherException
import tw.xiaoliangkou.app.data.WeatherSource
import java.util.Collections
import kotlin.math.abs

/**
 * 假的天氣（畫面測試用）：不連網路。示範資料裡「我」的位置（[DemoRepository.MY_LOCATION]）回 [mine]，其他地方回 [partner]
 */
class FakeWeather(
    var mine: WeatherNow = SUNNY_HOT,
    var partner: WeatherNow = RAINY_COOL,
    /** 查的時候要丟的錯（null 是查得到） */
    var fail: String? = null,
    /** 一直等不回來：讀取中的畫面 */
    var hang: Boolean = false,
) : WeatherSource {
    val asked: MutableList<Pair<Double, Double>> = Collections.synchronizedList(mutableListOf())

    private fun isMine(lat: Double, lng: Double) =
        abs(lat - DemoRepository.MY_LOCATION.lat) < 0.001 && abs(lng - DemoRepository.MY_LOCATION.lng) < 0.001

    override suspend fun now(lat: Double, lng: Double): WeatherNow {
        asked += lat to lng
        if (hang) awaitCancellation()
        fail?.let { throw WeatherException(it) }
        return if (isMine(lat, lng)) mine else partner
    }

    override suspend fun area(lat: Double, lng: Double) = if (isMine(lat, lng)) "台北市大安區" else "台北市松山區"

    companion object {
        /** 大太陽：紫外線過量、很熱、空氣普通 */
        val SUNNY_HOT = WeatherNow(
            temp = 33.4, code = 0, feelsLike = 37.2, humidity = 62, visibilityM = 24_140.0, windKmh = 9.0,
            rainChance = 10, uvMax = 9.3, high = 34.0, low = 27.0, aqi = 68, pm25 = 18.0,
        )
        /** 下雨變冷：降雨機率高、能見度差、空氣對敏感族群不健康 */
        val RAINY_COOL = WeatherNow(
            temp = 14.2, code = 63, feelsLike = 11.8, humidity = 94, visibilityM = 2_400.0, windKmh = 22.0,
            rainChance = 90, uvMax = 1.0, high = 17.0, low = 12.0, aqi = 118, pm25 = 41.0,
        )
        /** 什麼都剛剛好 */
        val NICE = WeatherNow(
            temp = 24.0, code = 1, feelsLike = 24.5, humidity = 60, visibilityM = 30_000.0, windKmh = 8.0,
            rainChance = 0, uvMax = 4.0, high = 26.0, low = 21.0, aqi = 30, pm25 = 7.0,
        )
    }
}
