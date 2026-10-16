package tw.xiaoliangkou.app

import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.data.LatLng
import tw.xiaoliangkou.app.data.PlaceHit
import tw.xiaoliangkou.app.data.PlaceSearch
import tw.xiaoliangkou.app.data.PlaceSearch.QueryKind
import java.io.IOException
import java.util.Locale

/** 地圖的地址搜尋：手機 Geocoder → OpenStreetMap Nominatim 的順序、台灣地址改寫、回應解析 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceSearchTest {
    // Nominatim 真的回應（2026-10 實測，地址節錄）
    private val taipei101 = """[{"lat":"25.0338352","lon":"121.5644995","name":"台北101","display_name":"台北101, 7, 信義路五段, 西村里, 信義區, 信義商圈, 臺北市, 11049, 臺灣",
        "address":{"tourism":"台北101","house_number":"7","road":"信義路五段","neighbourhood":"西村里","suburb":"信義區","city":"臺北市","postcode":"11049","country":"臺灣","country_code":"tw"}}]"""
    private val shopsAt45 = """[
        {"lat":"25.0337553","lon":"121.5643036","name":"Apple 台北 101","display_name":"Apple 台北 101, 45, 市府路, 西村里, 信義區, 臺北市, 11049, 臺灣",
         "address":{"shop":"Apple 台北 101","house_number":"45","road":"市府路","suburb":"信義區","city":"臺北市","country_code":"tw"}},
        {"lat":"25.0339899","lon":"121.5642821","name":"台北101購物中心","display_name":"台北101購物中心, 45, 市府路, 西村里, 信義區, 臺北市, 11001, 臺灣",
         "address":{"shop":"台北101購物中心","house_number":"45","road":"市府路","suburb":"信義區","city":"臺北市","country_code":"tw"}}]"""
    private val road = """[{"lat":"25.0344612","lon":"121.5634558","name":"市府路","display_name":"市府路, 西村里, 信義區, 臺北市, 11001, 臺灣",
        "address":{"road":"市府路","suburb":"信義區","city":"臺北市","country_code":"tw"}},
        {"lat":"25.0367084","lon":"121.5635679","name":"市府路","display_name":"市府路, 西村里, 信義區, 臺北市, 11008, 臺灣",
        "address":{"road":"市府路","suburb":"信義區","city":"臺北市","country_code":"tw"}}]"""
    private val sensoji = """[{"lat":"35.7148","lon":"139.7967","name":"淺草寺","display_name":"淺草寺, 1, 浅草二丁目, 浅草, 臺東區, 東京都, 111-0032, 日本",
        "address":{"amenity":"淺草寺","country":"日本","country_code":"jp"}}]"""

    private val requests = mutableListOf<okhttp3.Request>()

    /** 假的 Nominatim：依查詢字回傳內容；回傳 null＝連不上 */
    private fun nominatim(answer: (String) -> String?) = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        requests += req
        val body = answer(req.url.queryParameter("q").orEmpty()) ?: throw IOException("offline")
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun geocoder(vararg found: Address, error: String? = null): Geocoder =
        Geocoder(RuntimeEnvironment.getApplication(), Locale.TAIWAN).also {
            shadowOf(it).setFromLocation(found.toList())
            if (error != null) shadowOf(it).setErrorMessage(error)
        }

    private fun address(lat: Double, lng: Double, line: String, feature: String? = null) = Address(Locale.TAIWAN).apply {
        latitude = lat; longitude = lng; setAddressLine(0, line); featureName = feature
    }

    private fun search(geo: Geocoder?, answer: (String) -> String?, q: String, near: LatLng? = null) = runBlocking {
        PlaceSearch({ geo }, "tw.xiaoliangkou.app/test", nominatim(answer), nominatimIntervalMs = 0).search(q, near)
    }

    private fun queries() = requests.map { it.url.queryParameter("q") }

    @Test fun phoneGeocoderComesFirst() {
        val hits = search(geocoder(address(25.0338, 121.5645, "110台灣台北市信義區信義路五段7號", "台北101")), { fail("不該問 Nominatim"); null }, "台北101")
        assertEquals(listOf(PlaceHit("台北101", "台北市信義區信義路五段7號", 25.0338, 121.5645)), hits)
        assertTrue(requests.isEmpty())
    }

    @Test fun plainAddressFromPhoneShowsTheAddress() {
        val hits = search(geocoder(address(25.0375, 121.5637, "110台灣台北市信義區市府路45號", "45")), { null }, "市府路45號")
        assertEquals("台北市信義區市府路45號", hits.single().title)
        assertEquals("", hits.single().subtitle)
    }

    @Test fun fallsBackToNominatimWithAppNameAndNearbyBias() {
        val hits = search(null, { if (it == "台北101") taipei101 else "[]" }, "台北101", near = LatLng(25.03, 121.56))
        assertEquals(listOf(PlaceHit("台北101", "臺北市信義區信義路五段7號", 25.0338352, 121.5644995)), hits)
        val req = requests.single()
        assertEquals("tw.xiaoliangkou.app/test", req.header("User-Agent"))
        assertEquals("zh-TW", req.url.queryParameter("accept-language"))
        assertTrue(req.url.queryParameter("viewbox")!!.startsWith("121.26"))
    }

    @Test fun taiwaneseAddressIsRewrittenForNominatim() {
        // 手機查不到 → Nominatim 原文也查不到 → 改寫成「45, 市府路, 信義區, 台北市」才找到；同一個門牌的店只留一個，標題顯示地址
        val hits = search(geocoder(), { if (it.startsWith("45, ")) shopsAt45 else "[]" }, "台北市信義區市府路45號3樓")
        assertEquals(listOf("台北市信義區市府路45號3樓", "45, 市府路, 信義區, 台北市"), queries())
        assertEquals(listOf(PlaceHit("臺北市信義區市府路45號", "Apple 台北 101", 25.0337553, 121.5643036)), hits)
    }

    @Test fun unknownHouseNumberFallsBackToTheRoad() {
        val hits = search(null, { if (it == "市府路, 信義區, 台北市") road else "[]" }, "台北市信義區市府路999號")
        assertEquals(3, requests.size)
        assertEquals(listOf(PlaceHit("臺北市信義區市府路", "找不到這個門牌，先帶你到這條路", 25.0344612, 121.5634558)), hits)
    }

    @Test fun foreignPlacesKeepNominatimAddress() {
        val hit = search(null, { sensoji }, "淺草寺").single()
        assertEquals("淺草寺", hit.title)
        assertEquals("1, 浅草二丁目, 浅草, 臺東區, 東京都, 111-0032, 日本", hit.subtitle)
    }

    @Test fun remembersRecentSearches() {
        var answers = 0
        val searcher = PlaceSearch({ null }, "tw.xiaoliangkou.app/test", nominatim { answers++; if (it == "台北101") taipei101 else "[]" }, nominatimIntervalMs = 0)
        val near = LatLng(25.03, 121.56)
        runBlocking {
            assertEquals(1, searcher.search("台北101", near).size)
            assertEquals(1, searcher.search(" 台北101 ", LatLng(25.04, 121.57)).size) // 同一區再查一次：用記住的
            assertEquals(0, searcher.search("asdfghjkl", near).size)
            assertEquals(0, searcher.search("asdfghjkl", near).size) // 找不到的不記，會重查
        }
        assertEquals(3, answers)
    }

    @Test fun nothingFoundIsAnEmptyList() {
        assertEquals(emptyList<PlaceHit>(), search(geocoder(), { "[]" }, "asdfghjkl"))
        // 手機查過了（沒找到），Nominatim 連不上：當作找不到
        assertEquals(emptyList<PlaceHit>(), search(geocoder(), { null }, "asdfghjkl"))
    }

    @Test fun failsOnlyWhenBothAreUnreachable() {
        try {
            search(geocoder(error = "grpc failed"), { null }, "台北101")
            fail("兩邊都連不上應該失敗")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun rewritesTaiwaneseAddresses() {
        fun variants(q: String) = PlaceSearch.nominatimQueries(q).map { it.query to it.kind }
        assertEquals(
            listOf("110台北市信義區市府路45號" to QueryKind.AS_TYPED, "45, 市府路, 信義區, 台北市" to QueryKind.ADDRESS, "市府路, 信義區, 台北市" to QueryKind.ROAD),
            variants("110台北市信義區市府路45號"),
        )
        assertEquals(
            listOf("新北市 板橋區 文化路一段188巷" to QueryKind.AS_TYPED, "文化路一段188巷, 板橋區, 新北市" to QueryKind.ADDRESS),
            variants("新北市 板橋區 文化路一段188巷"),
        )
        assertEquals("8, 市民大道三段, 台北市", variants("台北市市民大道三段8號")[1].first)
        assertEquals("99之1, 台灣大道三段, 西屯區, 台中市", variants("台灣台中市西屯區台灣大道三段99之1號")[1].first)
        assertEquals("10, 光明六路, 竹北市", variants("竹北市光明六路10號")[1].first)
        // 不是地址：只查原文
        assertEquals(listOf("台北101" to QueryKind.AS_TYPED), variants("台北101"))
        assertEquals(listOf("鼎泰豐 信義店" to QueryKind.AS_TYPED), variants("鼎泰豐 信義店"))
    }

    @Test fun tidiesPhoneAddresses() {
        assertEquals("台北市信義區市府路45號", PlaceSearch.tidyAddress("110台灣台北市信義區市府路45號"))
        assertEquals("臺北市信義區市府路45號", PlaceSearch.tidyAddress("臺灣110臺北市信義區市府路45號"))
        assertEquals("1-chōme-1 Asakusa, Taito City, Tokyo", PlaceSearch.tidyAddress("1-chōme-1 Asakusa, Taito City, Tokyo"))
    }

    @Test fun zoomsToFitTheFoundArea() {
        fun zoom(box: String) = PlaceSearch.parseNominatim("""[{"lat":"25","lon":"121","name":"x","boundingbox":$box}]""").single().zoom
        assertEquals(17.0, zoom("""["25.0332803","25.0346102","121.5638583","121.5652356"]"""), 0.0) // 台北101
        assertEquals(12.0, zoom("""["25.02","25.05","121.55","121.60"]"""), 0.0) // 信義區
        assertEquals(10.0, zoom("""["24.96","25.21","121.45","121.67"]"""), 0.0) // 臺北市
        assertEquals(6.0, zoom("""["21.8","25.4","119.3","122.1"]"""), 0.0) // 臺灣
        assertEquals(17.0, PlaceSearch.parseNominatim(taipei101).single().zoom, 0.0) // 沒有範圍就當作一個點
        // 手機 Geocoder：只有縣市不要放太大
        val city = Address(Locale.TAIWAN).apply { latitude = 24.14; longitude = 120.68; setAddressLine(0, "台灣台中市"); adminArea = "台中市"; featureName = "台中市" }
        assertEquals(10.0, search(geocoder(city), { null }, "台中市").single().zoom, 0.0)
        val district = Address(Locale.TAIWAN).apply { latitude = 25.03; longitude = 121.57; setAddressLine(0, "台灣台北市信義區"); adminArea = "台北市"; locality = "信義區"; featureName = "信義區" }
        assertEquals(13.0, search(geocoder(district), { null }, "信義區").single().zoom, 0.0)
    }

    @Test fun parsesBrokenResponsesSafely() {
        assertEquals(emptyList<PlaceHit>(), PlaceSearch.parseNominatim("<html>blocked</html>"))
        assertEquals(emptyList<PlaceHit>(), PlaceSearch.parseNominatim("""{"error":"x"}"""))
        assertEquals(emptyList<PlaceHit>(), PlaceSearch.parseNominatim("""[{"name":"沒有座標"}]"""))
    }
}
