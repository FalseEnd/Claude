package tw.xiaoliangkou.app

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphyException
import tw.xiaoliangkou.app.data.GiphyException.Reason
import tw.xiaoliangkou.app.data.GiphyItem
import tw.xiaoliangkou.app.data.GiphyKind
import tw.xiaoliangkou.app.data.GiphyMediaCache
import tw.xiaoliangkou.app.data.GiphyPage
import tw.xiaoliangkou.app.data.GiphyQuery
import tw.xiaoliangkou.app.data.GiphySearcher
import tw.xiaoliangkou.app.data.giphyHttp
import java.io.IOException
import java.util.Collections

/** GIPHY 的搜尋：送出去的參數、回應解析、錯誤、記住查過的頁面、貼圖的網址；用假的 GIPHY（OkHttp interceptor） */
class GiphyTest {
    private val key = "abcdEFGH1234ijklMNOP5678qrstUVWX"

    // GIPHY 真的回應（2026 的 GIF 搜尋、2023 的貼圖搜尋，節錄兩張、只留用得到的欄位）
    private val happyGif = """{"type":"gif","id":"fUQ4rhUZJYiQsas6WD","title":"Happy Sesame Street GIF by Muppet Wiki","username":"muppetwiki","is_sticker":0,
        "images":{"fixed_width_downsampled":{"height":"150","width":"200","url":"https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w_d.gif","webp":"https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w_d.webp"},
        "fixed_width":{"height":"150","width":"200","url":"https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w.gif","webp":"https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w.webp"},
        "fixed_width_still":{"height":"150","width":"200","url":"https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w_s.gif"}},
        "user":{"username":"muppetwiki","display_name":"Muppet Wiki"}}"""
    private val loveGif = """{"type":"gif","id":"wCykTNtEJonzlUZAra","title":"Trending Love GIF by Likee US","username":"LikeeUS","is_sticker":0,
        "images":{"fixed_width_downsampled":{"height":"200","width":"200","url":"https://media4.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/wCykTNtEJonzlUZAra/200w_d.gif","webp":"https://media4.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/wCykTNtEJonzlUZAra/200w_d.webp"},
        "fixed_width_still":{"height":"200","width":"200","url":"https://media4.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/wCykTNtEJonzlUZAra/200w_s.gif"}},
        "user":{"username":"LikeeUS","display_name":"Likee US"}}"""
    private val reactSticker = """{"type":"sticker","id":"eNAsjO55tPbgaor7ma","title":"React Js Sticker by EscuelaDevRock","username":"devrock","is_sticker":1,
        "images":{"fixed_width_downsampled":{"height":"202","width":"200","url":"https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w_d.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_d.gif&ct=s","webp":"https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w_d.webp?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_d.webp&ct=s"},
        "fixed_width":{"height":"202","width":"200","url":"https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w.gif&ct=s","webp":"https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w.webp?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w.webp&ct=s"},
        "fixed_width_still":{"height":"202","width":"200","url":"https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w_s.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_s.gif&ct=s"}},
        "user":{"username":"devrock","display_name":"EscuelaDevRock"}}"""
    private val truckSticker = """{"type":"sticker","id":"TincP7nGBM0PHU6Ees","title":"Truck Auto Pecas Sticker by Rede JS Peças","username":"redejspecas","is_sticker":1,
        "images":{"fixed_width":{"height":"200","width":"200","url":"https://media0.giphy.com/media/TincP7nGBM0PHU6Ees/200w.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w.gif&ct=s"},
        "fixed_width_still":{"height":"200","width":"200","url":"https://media0.giphy.com/media/TincP7nGBM0PHU6Ees/200w_s.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_s.gif&ct=s"}},
        "user":null}"""
    /** 無效的 key（2026-10 實測，原樣） */
    private val unauthorized = """{
        "data": [],
        "meta": {
          "status": 401,
          "msg": "Unauthorized",
          "response_id": ""
        }
      }"""

    private fun page(vararg items: String, count: Int = items.size, total: Int? = 500, offset: Int = 0, responseId: String = "if70h2z01y83x1uc68yong2lm852v0dtyxcchmbl") =
        """{"data":[${items.joinToString(",")}],"pagination":{${total?.let { "\"total_count\":$it," }.orEmpty()}"count":$count,"offset":$offset},""" +
            """"meta":{"status":200,"msg":"OK","response_id":"$responseId"}}"""

    private class Answer(val code: Int, val body: ByteArray, val type: String = "application/json", val length: Boolean = true)
    private fun ok(body: String) = Answer(200, body.toByteArray())

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** 假的 GIPHY：從真正用的 [giphyHttp] 長出來，看得到它送出去的請求；回傳 null＝連不上 */
    private fun http(answer: (Request) -> Answer?): OkHttpClient = giphyHttp.newBuilder().addInterceptor { chain ->
        val req = chain.request()
        requests += req
        val a = answer(req) ?: throw IOException("offline")
        val type = a.type.toMediaType()
        // length = false：不說多大（chunked）
        val body = if (a.length) a.body.toResponseBody(type) else Buffer().write(a.body).asResponseBody(type, -1)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(a.code).message("X").body(body).build()
    }.build()

    private fun giphy(sdk: Int = 34, answer: (Request) -> Answer?) = Giphy(http(answer), sdk)

    private fun fails(reason: Reason, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("應該要失敗：$reason")
        } catch (e: GiphyException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun trendingAndSearchSendTheRightParameters() = runBlocking<Unit> {
        val g = giphy { ok(page(reactSticker)) }
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        with(requests.last().url) {
            assertEquals("https://api.giphy.com/v1/stickers/trending", toString().substringBefore('?'))
            assertEquals(key, queryParameter("api_key"))
            assertEquals("24", queryParameter("limit"))
            assertEquals("0", queryParameter("offset"))
            assertEquals("pg-13", queryParameter("rating"))
            assertNull("熱門的不用關鍵字", queryParameter("q"))
            assertNull(queryParameter("lang"))
        }
        g.page(GiphyQuery(key, GiphyKind.GIFS, "  抱抱 我 ", offset = 48))
        with(requests.last().url) {
            assertEquals("https://api.giphy.com/v1/gifs/search", toString().substringBefore('?'))
            assertEquals("抱抱 我", queryParameter("q"))
            assertEquals("zh-TW", queryParameter("lang"))
            assertEquals("48", queryParameter("offset"))
            assertEquals("pg-13", queryParameter("rating"))
        }
        // 太長的關鍵字切到 50 個字（GIPHY 超過會回 414）
        g.page(GiphyQuery(key, GiphyKind.STICKERS, "好".repeat(80)))
        assertEquals("好".repeat(50), requests.last().url.queryParameter("q"))
        assertEquals("hug", Giphy.query("  hug  "))
        // 不要把 emoji 切成一半
        assertEquals("a".repeat(49), Giphy.query("a".repeat(49) + "😀"))
    }

    @Test fun parsesRealResponses() = runBlocking<Unit> {
        val gifs = giphy { ok(page(happyGif, loveGif)) }.page(GiphyQuery(key, GiphyKind.GIFS, "happy"))
        assertEquals(listOf("fUQ4rhUZJYiQsas6WD", "wCykTNtEJonzlUZAra"), gifs.items.map { it.id })
        val happy = gifs.items.first()
        assertEquals("Happy Sesame Street GIF by Muppet Wiki", happy.title)
        assertEquals("Muppet Wiki", happy.creator)
        // Android 9 以上：會動的 WebP（張數少的那個），網址照 API 回傳的，一個字都不改
        assertEquals(
            "https://media2.giphy.com/media/v1.Y2lkPWJiM2QwMTgyaWY3MGgyejAxeTgzeDF1YzY4eW9uZzJsbTg1MnYwZHR5eGNjaG1ibCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/fUQ4rhUZJYiQsas6WD/200w_d.webp",
            happy.animated,
        )
        assertTrue(happy.still.endsWith("/fUQ4rhUZJYiQsas6WD/200w_s.gif"))
        assertEquals(200, happy.width)
        assertEquals(150, happy.height)
        // 標題裡已經有作者就不用再唸一次
        assertEquals("Happy Sesame Street GIF by Muppet Wiki", happy.description)

        val stickers = giphy(sdk = 27) { ok(page(reactSticker, truckSticker)) }.page(GiphyQuery(key, GiphyKind.STICKERS, "react"))
        val react = stickers.items.first()
        // Android 8：只能播 GIF；網址後面的追蹤參數也原樣保留
        assertEquals(
            "https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w_d.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_d.gif&ct=s",
            react.animated,
        )
        assertEquals(
            "https://media3.giphy.com/media/eNAsjO55tPbgaor7ma/200w_s.gif?cid=c4cfd7d78oogq22bict6bpr8rbp2r5wpdbjy1dhe39tejrmy&ep=v1_stickers_search&rid=200w_s.gif&ct=s",
            react.still,
        )
        // 沒有 downsampled、user 是 null：用一般的 fixed_width，作者用最外層的 username
        val truck = stickers.items[1]
        assertTrue(truck.animated.contains("/TincP7nGBM0PHU6Ees/200w.gif?"))
        assertEquals("redejspecas", truck.creator)
    }

    @Test fun skipsWhatCannotBeShown() {
        val badId = reactSticker.replace("\"id\":\"eNAsjO55tPbgaor7ma\"", "\"id\":\"../../evil\"")
        val noImages = """{"type":"gif","id":"noImages123","title":"x","images":{}}"""
        val plainHttp = """{"type":"gif","id":"plainHttp123","title":"x","images":{"fixed_width":{"url":"http://example.com/200w.gif"}}}"""
        val onlyHeight = """{"type":"gif","id":"onlyHeight123","title":"","images":{"fixed_height":{"width":266,"height":"200","url":"https://media1.giphy.com/media/onlyHeight123/200.gif","webp":"https://media1.giphy.com/media/onlyHeight123/200.webp"}}}"""
        val p = Giphy.parse(page(badId, noImages, plainHttp, onlyHeight, "\"not an object\"", happyGif), 0, 34)
        assertEquals(listOf("onlyHeight123", "fUQ4rhUZJYiQsas6WD"), p.items.map { it.id })
        val only = p.items.first()
        assertEquals("https://media1.giphy.com/media/onlyHeight123/200.webp", only.animated)
        assertEquals("沒有不會動的就用同一張", only.animated, only.still)
        assertEquals(266, only.width)
        assertEquals("GIPHY 貼圖", only.description)
    }

    @Test fun findsTheNextPageAndTheEnd() {
        val items = Array(24) { happyGif }
        assertEquals(24, Giphy.parse(page(*items, total = 500), 0, 34).next)
        assertEquals(72, Giphy.parse(page(*items, total = 500), 48, 34).next)
        // 最多 96 張：省著用一小時 100 次的額度
        assertNull(Giphy.parse(page(*items, total = 500), 72, 34).next)
        // 全部只有 30 張：第二頁就是最後一頁
        assertEquals(24, Giphy.parse(page(*items, total = 30), 0, 34).next)
        assertNull(Giphy.parse(page(*Array(6) { happyGif }, total = 30), 24, 34).next)
        assertNull(Giphy.parse(page(*items, total = 24), 0, 34).next)
        // 沒有 total_count：拿到不滿一頁就是最後一頁
        assertEquals(24, Giphy.parse(page(*items, total = null), 0, 34).next)
        assertNull(Giphy.parse(page(happyGif, total = null), 24, 34).next)
        // 真的找不到（有 response_id）：空的一頁，不是錯誤
        val none = Giphy.parse(page(total = 0), 0, 34)
        assertEquals(GiphyPage(emptyList(), null), none)
    }

    @Test fun errorsAreMapped() {
        fun after(answer: Answer?) = giphy { answer }
        fails(Reason.BAD_KEY) { after(Answer(401, unauthorized.toByteArray())).page(GiphyQuery(key, GiphyKind.STICKERS)) }
        fails(Reason.BAD_KEY) { after(Answer(403, """{"message":"Forbidden"}""".toByteArray())).page(GiphyQuery(key, GiphyKind.GIFS, "hug")) }
        fails(Reason.RATE_LIMITED) { after(Answer(429, """{"message":"API rate limit exceeded"}""".toByteArray())).page(GiphyQuery(key, GiphyKind.GIFS, "hug")) }
        fails(Reason.RATE_LIMITED) { after(ok("""{"data":[],"meta":{"status":429,"msg":"Too Many Requests","response_id":""}}""")).page(GiphyQuery(key, GiphyKind.GIFS)) }
        fails(Reason.NETWORK) { after(null).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        fails(Reason.SERVER) { after(Answer(500, "oops".toByteArray(), "text/plain")).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        fails(Reason.SERVER) { after(Answer(414, "".toByteArray())).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        fails(Reason.SERVER) { after(Answer(200, "<html>Wi-Fi 登入</html>".toByteArray(), "text/html")).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        fails(Reason.SERVER) { after(ok("""{"data":{"id":"x"},"meta":{"status":200}}""")).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        // 說明文件的「假的回應」：狀態 200，但是沒有 response_id、也沒有資料
        fails(Reason.SERVER) { after(ok("""{"data":[],"pagination":{"count":0,"offset":0},"meta":{"status":200,"msg":"OK","response_id":""}}""")).page(GiphyQuery(key, GiphyKind.STICKERS, "hug")) }
        // 格式不對的 key 不用問就知道不行（不浪費額度）
        requests.clear()
        fails(Reason.BAD_KEY) { after(ok(page(happyGif))).page(GiphyQuery("壞 key", GiphyKind.STICKERS)) }
        assertTrue(requests.isEmpty())
        // 錯誤訊息是給畫面用的中文
        assertEquals("GIPHY 這個小時用太多次了，等一下再試 🙏", GiphyException(Reason.RATE_LIMITED).message)
    }

    @Test fun pagesAreRemembered() = runBlocking<Unit> {
        var fail = true
        val g = giphy { r ->
            if (r.url.queryParameter("q") == "cat" && fail) { fail = false; Answer(500, "".toByteArray()) } else ok(page(happyGif))
        }
        g.page(GiphyQuery(key, GiphyKind.GIFS, "hug"))
        g.page(GiphyQuery(key, GiphyKind.GIFS, " hug "))
        assertEquals("同一頁只問一次", 1, requests.size)
        g.page(GiphyQuery(key, GiphyKind.GIFS, "hug", offset = 24))
        g.page(GiphyQuery(key, GiphyKind.STICKERS, "hug"))
        assertEquals("換頁、換種類要再問", 3, requests.size)
        // 出錯的不記：再查一次會重問
        fails(Reason.SERVER) { g.page(GiphyQuery(key, GiphyKind.GIFS, "cat")) }
        assertEquals(1, g.page(GiphyQuery(key, GiphyKind.GIFS, "cat")).items.size)
        assertEquals(5, requests.size)
    }

    /** 背景服務讓 App 一直活著：查過的頁面記一小時，之後重查（熱門的才會換新、GIPHY 拿掉的圖不會一直留著） */
    @Test fun pagesAreForgottenAfterAnHour() = runBlocking<Unit> {
        var now = 1_000_000L
        val g = Giphy(http { ok(page(happyGif)) }, 34) { now }
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        now += Giphy.PAGE_TTL_MS - 60_000
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        assertEquals("一小時內不再問", 1, requests.size)
        now += 2 * 60_000
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        assertEquals("超過一小時就重查", 2, requests.size)
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        assertEquals("重查的又記一小時", 2, requests.size)
    }

    /** 注音還在選字：打字停下來時先不查（一小時只有 100 次）；英文、拼音、選好的中文照常查 */
    @Test fun zhuyinBeingTypedIsNotSearched() {
        for (t in listOf("ㄅㄠˋ", "抱ㄅㄠ", "ㄅ", "ㆠ", "好ˇ", "晚ㄢ˙")) assertTrue(t, Giphy.hasZhuyin(t))
        for (t in listOf("抱抱", "hug", "baobao", "晚安 good night", "", "😀")) assertFalse(t, Giphy.hasZhuyin(t))
    }

    /** 貼圖的圖片記在記憶體裡（Coil 不記會動的圖）：同一個網址只下載一次；不是 GIPHY 的圖、不是 200、太大的不記；放不下時丟掉最久沒用到的 */
    @Test fun stickerImagesAreKeptInMemory() {
        val client = giphyHttp.newBuilder().addInterceptor(GiphyMediaCache(maxBytes = 250, maxEntry = 100)).addInterceptor { chain ->
            val req = chain.request()
            requests += req
            val path = req.url.encodedPath
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if ("/missing/" in path) 404 else 200).message("X")
                .header("Content-Type", "image/webp")
                .body(ByteArray(if ("/big/" in path) 101 else 80) { path.length.toByte() }.toResponseBody("image/webp".toMediaType())).build()
        }.build()
        fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { Triple(it.code, it.header("Content-Type"), it.body!!.bytes().size) }
        fun hits(url: String) = requests.count { it.url.toString() == url }

        val a = "https://media.giphy.com/media/aaa/200w.webp"
        assertEquals(Triple(200, "image/webp", 80), get(a))
        assertEquals("從記憶體拿的一模一樣", Triple(200, "image/webp", 80), get(a))
        assertEquals("同一張只下載一次", 1, hits(a))
        // API 給的網址：media0–4.giphy.com，後面的追蹤參數原封不動算在網址裡
        val b = "https://media2.giphy.com/media/v1.Y2lk/bbb/200w_d.webp?cid=c4cfd7d7&rid=200w_d.webp&ct=s"
        get(b)
        get(b)
        assertEquals(1, hits(b))
        // 不記：搜尋的 API、找不到（404）、太大的（還是整張都拿得到）
        val big = "https://media.giphy.com/media/big/200w.webp"
        for (u in listOf("https://api.giphy.com/v1/stickers/trending?api_key=$key", "https://media.giphy.com/media/missing/200w.webp", big)) {
            assertEquals(u, get(u), get(u))
            assertEquals(u, 2, hits(u))
        }
        assertEquals(101, get(big).third)
        // 放不下了（最多 250 bytes，一張 80）：丟掉最久沒用到的那張
        val c = "https://i.giphy.com/ccc.webp"
        val d = "https://media.giphy.com/media/ddd/200w.webp"
        get(c)
        get(a)
        get(d)
        for (u in listOf(a, c, d)) get(u)
        assertEquals(listOf(1, 1, 1), listOf(a, c, d).map(::hits))
        get(b)
        assertEquals("最久沒用到的被丟掉了", 2, hits(b))
    }

    @Test fun checksTheKeyWithOneTrendingSticker() = runBlocking<Unit> {
        giphy { ok(page(reactSticker)) }.checkKey(key)
        with(requests.single().url) {
            assertEquals("https://api.giphy.com/v1/stickers/trending", toString().substringBefore('?'))
            assertEquals("1", queryParameter("limit"))
            assertEquals(key, queryParameter("api_key"))
        }
        fails(Reason.BAD_KEY) { giphy { Answer(401, unauthorized.toByteArray()) }.checkKey(key) }
        requests.clear()
        fails(Reason.BAD_KEY) { giphy { ok(page(reactSticker)) }.checkKey("short") }
        assertTrue("格式不對不用問", requests.isEmpty())
        // 畫面測試的假搜尋：只寫一個 lambda，檢查 key 也會用到它
        var asked: GiphyQuery? = null
        GiphySearcher { q -> asked = q; GiphyPage(emptyList(), null) }.checkKey(key)
        assertEquals(GiphyQuery(key, GiphyKind.STICKERS), asked)
    }

    @Test fun stillImageForNotifications() = runBlocking<Unit> {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val g = giphy { r -> if (r.url.encodedPath.contains("/missing/")) Answer(404, "x".toByteArray(), "image/gif") else Answer(200, png, "image/gif") }
        assertArrayEquals(png, g.still("L4c8BNDK4STL7B5LdV"))
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w_s.gif", requests.single().url.toString())
        assertNull("找不到（GIPHY 回 404 加一張「找不到」的圖）", g.still("missing"))
        requests.clear()
        assertNull(g.still("../x"))
        assertNull(g.still(""))
        assertTrue("id 不對就不會去抓", requests.isEmpty())
        // 太大的不要（有說多大、沒說多大都一樣）
        val big = ByteArray((Giphy.MAX_STILL_BYTES + 1).toInt())
        assertNull(giphy { Answer(200, big, "image/gif") }.still("L4c8BNDK4STL7B5LdV"))
        assertNull(giphy { Answer(200, big, "image/gif", length = false) }.still("L4c8BNDK4STL7B5LdV"))
        assertArrayEquals(png, giphy { Answer(200, png, "image/gif", length = false) }.still("L4c8BNDK4STL7B5LdV"))
        assertNull("連不上", giphy { null }.still("L4c8BNDK4STL7B5LdV"))
    }

    @Test fun chosenStickersAreShownFromTheirId() {
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w.webp", Giphy.stickerUrl("L4c8BNDK4STL7B5LdV", sdk = 28))
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w.webp", Giphy.stickerUrl("L4c8BNDK4STL7B5LdV", sdk = 35))
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w.gif", Giphy.stickerUrl("L4c8BNDK4STL7B5LdV", sdk = 27))
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w.gif", Giphy.stickerUrl("L4c8BNDK4STL7B5LdV", sdk = 26))
        assertEquals("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w_s.gif", Giphy.stickerUrl("L4c8BNDK4STL7B5LdV", still = true, sdk = 34))
        // 對方傳來奇怪的 id：不組網址
        for (bad in listOf(null, "", "../evil", "a/b", "abc?x=1", "abc def", "貼圖", "a".repeat(65), "abc\n")) {
            assertNull(bad, Giphy.stickerUrl(bad, sdk = 34))
        }
    }

    @Test fun validatesIdsAndKeys() {
        for (id in listOf("L4c8BNDK4STL7B5LdV", "YsTs5ltWtEhnq", "a", "A".repeat(64))) assertTrue(id, Giphy.isValidId(id))
        for (id in listOf(null, "", "a-b", "a_b", "a.b", "../x", " abc", "abc ", "A".repeat(65), "ａｂｃ")) assertFalse("$id", Giphy.isValidId(id))
        for (k in listOf(key, "a".repeat(16), "Ab-_".repeat(32))) assertTrue(k, Giphy.isValidKey(k))
        for (k in listOf(null, "", "a".repeat(15), "a".repeat(129), "abcdEFGH1234ijkl MNOP", "abcdEFGH1234ijkl/", "金鑰金鑰金鑰金鑰金鑰金鑰金鑰金鑰")) assertFalse("$k", Giphy.isValidKey(k))
    }

    @Test fun neverSendsSupabaseHeaders() = runBlocking<Unit> {
        // 跟 Supabase 共用連線池，但沒有加任何 interceptor（Supabase 的 apikey、登入的 token 都是 interceptor 加的）
        assertTrue(giphyHttp.interceptors.isEmpty())
        assertTrue(giphyHttp.networkInterceptors.isEmpty())
        assertEquals(Authenticator.NONE, giphyHttp.authenticator)
        val g = giphy { r -> if (r.url.host == "media.giphy.com") Answer(200, byteArrayOf(1), "image/gif") else ok(page(happyGif)) }
        g.page(GiphyQuery(key, GiphyKind.GIFS, "hug"))
        g.page(GiphyQuery(key, GiphyKind.STICKERS))
        g.checkKey(key)
        g.still("L4c8BNDK4STL7B5LdV")
        assertEquals(4, requests.size)
        for (r in requests) {
            assertNull(r.header("apikey"))
            assertNull(r.header("Authorization"))
            assertTrue(r.url.isHttps)
            assertTrue(r.url.host.endsWith("giphy.com"))
        }
        // key 只放在搜尋的網址裡，不會出現在貼圖圖片的網址
        assertNull(requests.last().url.queryParameter("api_key"))
    }

    @Test fun describesItemsForScreenReaders() {
        assertEquals("跳舞的熊，Milk and Mocha", GiphyItem("abc", "跳舞的熊", "Milk and Mocha", "https://x/a.webp", "https://x/a_s.gif").description)
        assertEquals("Milk and Mocha", GiphyItem("abc", " ", "Milk and Mocha", "https://x/a.webp", "https://x/a_s.gif").description)
        assertEquals("GIPHY 貼圖", GiphyItem("abc", "", null, "https://x/a.webp", "https://x/a_s.gif").description)
    }
}
