package tw.xiaoliangkou.app.data

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** GIPHY 的兩種圖：貼圖（透明背景）、一般的 GIF */
enum class GiphyKind(val path: String) { STICKERS("stickers"), GIFS("gifs") }

/** 要查的那一頁：[query] 空白就是熱門的 */
data class GiphyQuery(val key: String, val kind: GiphyKind, val query: String = "", val offset: Int = 0)

/**
 * 格子裡的一張。網址都是 API 回傳的，照原樣顯示（GIPHY 的規定：不能改、不能存檔）。
 * [animated] 是會動的（Android 9 以上用 WebP，舊的手機用 GIF），[still] 是不會動的（系統關掉動畫時用）
 */
data class GiphyItem(
    val id: String,
    val title: String,
    val creator: String?,
    val animated: String,
    val still: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** 唸給看不到畫面的人聽：標題，有作者的話加上作者 */
    val description: String
        get() = listOfNotNull(title.ifBlank { null }, creator?.takeUnless { title.contains(it) }).joinToString("，").ifEmpty { "GIPHY 貼圖" }
}

/** 一頁結果；[next] 是下一頁的 offset，沒有了（或已經 [Giphy.MAX_RESULTS] 張）是 null */
data class GiphyPage(val items: List<GiphyItem>, val next: Int?)

/** 查 GIPHY 失敗：畫面依 [reason] 顯示不同的說明 */
class GiphyException(val reason: Reason, cause: Throwable? = null) : Exception(reason.text, cause) {
    enum class Reason(val text: String) {
        /** key 不對、被停用（401、403） */
        BAD_KEY("GIPHY 的 API key 不能用，請重新設定"),
        /** 免費的 key 一小時只能查 100 次 */
        RATE_LIMITED("GIPHY 這個小時用太多次了，等一下再試 🙏"),
        NETWORK("連不到 GIPHY"),
        /** GIPHY 回了錯誤、看不懂的內容，或是說明文件講的「假的回應」 */
        SERVER("GIPHY 暫時有點狀況，等一下再試"),
    }
}

/** 搜尋 GIPHY；畫面測試時換成假的 */
fun interface GiphySearcher {
    /** 失敗丟 [GiphyException] */
    suspend fun page(q: GiphyQuery): GiphyPage

    /** 這個 key 能不能用：能用就沒事，不能用丟 [GiphyException] */
    suspend fun checkKey(key: String) { page(GiphyQuery(key, GiphyKind.STICKERS)) }
}

/**
 * GIPHY 的搜尋與熱門（https://developers.giphy.com/docs/api/）。
 * 免費的 beta key 一小時只能呼叫 100 次：查過的每一頁都記在記憶體裡，一小時內不會再問一次（之後熱門的才會換新）。
 * 只用 [giphyHttp] 連線，不會帶到 Supabase 的金鑰和登入的 token
 */
class Giphy(
    private val http: OkHttpClient = giphyHttp,
    /** Android 9（API 28）以上才能顯示會動的 WebP */
    private val sdk: Int = Build.VERSION.SDK_INT,
    /** 現在的時間（毫秒）；測試時換成假的 */
    private val clock: () -> Long = System::currentTimeMillis,
) : GiphySearcher {
    /** 查過的頁面：(種類, offset, 關鍵字) → (查的時間, 結果) */
    private val pages = object : LinkedHashMap<String, Pair<Long, GiphyPage>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, GiphyPage>>?) = size > MAX_PAGES
    }

    override suspend fun page(q: GiphyQuery): GiphyPage {
        if (!isValidKey(q.key)) throw GiphyException(GiphyException.Reason.BAD_KEY)
        val text = query(q.query)
        val key = "${q.kind.path}|${q.offset}|$text"
        // 背景服務讓 App 一直活著：超過 [PAGE_TTL_MS] 的重查，熱門的才會換新、GIPHY 拿掉的圖也不會一直留著
        val now = clock()
        synchronized(pages) { pages[key] }?.takeIf { now - it.first < PAGE_TTL_MS }?.let { return it.second }
        // 找不到、出錯的不記：網路恢復或換了 key 再查就會重查
        return parse(get(url(q.key, q.kind, text, q.offset, LIMIT)), q.offset, sdk).also { p -> synchronized(pages) { pages[key] = clock() to p } }
    }

    /** 拿熱門貼圖的第一張試試看（只花一次呼叫） */
    override suspend fun checkKey(key: String) {
        if (!isValidKey(key)) throw GiphyException(GiphyException.Reason.BAD_KEY)
        parse(get(url(key, GiphyKind.STICKERS, "", 0, 1)), 0, sdk)
    }

    /** 選好的貼圖不會動的那張（通知的大圖）；id 不對、抓不到、超過 [MAX_STILL_BYTES] 都是 null */
    suspend fun still(id: String): ByteArray? {
        val url = stickerUrl(id, still = true, sdk = sdk)?.toHttpUrl() ?: return null
        return try {
            fetch(url) { res ->
                val body = res.body
                if (!res.isSuccessful || body == null || body.contentLength() > MAX_STILL_BYTES) return@fetch null
                // 沒說多大的話邊讀邊數，超過就不要了
                val source = body.source()
                if (source.request(MAX_STILL_BYTES + 1)) null else source.buffer.readByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun url(key: String, kind: GiphyKind, text: String, offset: Int, limit: Int): HttpUrl =
        API.toHttpUrl().newBuilder()
            .addPathSegment(kind.path)
            .addPathSegment(if (text.isEmpty()) "trending" else "search")
            .addQueryParameter("api_key", key)
            .apply { if (text.isNotEmpty()) addQueryParameter("q", text).addQueryParameter("lang", LANG) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("rating", RATING)
            .build()

    /** 送出請求，HTTP 的錯誤換成 [GiphyException]；回傳內容 */
    private suspend fun get(url: HttpUrl): String {
        val (code, text) = try {
            fetch(url) { it.code to it.body?.string().orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw GiphyException(GiphyException.Reason.NETWORK, e)
        } catch (e: Exception) {
            throw GiphyException(GiphyException.Reason.SERVER, e)
        }
        when {
            code == 401 || code == 403 -> throw GiphyException(GiphyException.Reason.BAD_KEY)
            code == 429 -> throw GiphyException(GiphyException.Reason.RATE_LIMITED)
            code !in 200..299 -> throw GiphyException(GiphyException.Reason.SERVER)
        }
        return text
    }

    /** 在 OkHttp 的執行緒讀完回應；協程取消（逾時、換了關鍵字）時連線跟著取消 */
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
        private const val API = "https://api.giphy.com/v1"
        private const val MEDIA = "https://media.giphy.com/media/"
        /** 申請 API key 的地方（GIPHY Developers 的 Dashboard） */
        const val DASHBOARD = "https://developers.giphy.com/dashboard/"

        /** 一頁幾張（beta key 最多 50） */
        const val LIMIT = 24
        /** 最多往下載到幾張：省著用一小時 100 次的額度 */
        const val MAX_RESULTS = 96
        /** 關鍵字最多幾個字（GIPHY 的上限，超過會回 414） */
        const val MAX_QUERY = 50
        /** 打字時等一下再查，不要每打一個字就用掉一次額度 */
        const val DEBOUNCE_MS = 600L
        const val RATING = "pg-13"
        const val LANG = "zh-TW"
        /** 通知的大圖最多抓多大 */
        const val MAX_STILL_BYTES = 1024L * 1024
        private const val MAX_PAGES = 64
        /** 查過的頁面記多久：一小時（免費 key 的額度也是一小時算一次） */
        const val PAGE_TTL_MS = 60 * 60 * 1000L

        // 跟資料庫的 check 一樣：GIPHY 的 id 只有英數字；組網址前一定先檢查，對方傳來奇怪的 id 也不會讓手機去載別的東西
        private val ID = Regex("^[A-Za-z0-9]{1,64}$")
        private val KEY = Regex("^[A-Za-z0-9_-]{16,128}$")

        // 格子裡優先用張數比較少的（檔案小很多），沒有才用一般的
        private val ANIMATED = listOf("fixed_width_downsampled", "fixed_width", "fixed_height_downsampled", "fixed_height")
        private val STILLS = listOf("fixed_width_still", "fixed_height_still", "original_still")

        fun isValidId(id: String?) = id != null && ID.matches(id)
        fun isValidKey(key: String?) = key != null && KEY.matches(key)

        /**
         * 注音輸入法還在選字（「ㄅㄠˋ」、「抱ㄅㄠ」）：打字停下來時先不查，選好字或按搜尋鍵才查（一小時只有 100 次）。
         * 英文、拼音看不出打完了沒，照常停一下就查
         */
        fun hasZhuyin(text: String) = text.any { it in '㄀'..'ㄯ' || it in 'ㆠ'..'ㆿ' || it in "ˊˇˋ˙" }

        /** 送出去的關鍵字：去掉前後空白，最多 [MAX_QUERY] 個字 */
        fun query(raw: String): String {
            val q = raw.trim().take(MAX_QUERY).trimEnd()
            // 不要切在 emoji 的一半
            return if (q.isNotEmpty() && q.last().isHighSurrogate()) q.dropLast(1).trimEnd() else q
        }

        /**
         * 已經選好的貼圖（資料庫只存 id）：用 id 組出網址。
         * 會動的在 Android 9 以上用 WebP、8.x 用 GIF；[still] 是不會動的第一張。id 不對是 null
         */
        fun stickerUrl(id: String?, still: Boolean = false, sdk: Int = Build.VERSION.SDK_INT): String? {
            if (!isValidId(id)) return null
            val file = when {
                still -> "200w_s.gif"
                sdk >= Build.VERSION_CODES.P -> "200w.webp"
                else -> "200w.gif"
            }
            return "$MEDIA$id/$file"
        }

        /** 搜尋、熱門的回應 → 一頁（[offset] 是這一頁的起點）；看不懂、假的回應丟 [GiphyException] */
        fun parse(body: String, offset: Int, sdk: Int): GiphyPage {
            val root = runCatching { Api.json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: throw GiphyException(GiphyException.Reason.SERVER)
            val meta = root["meta"] as? JsonObject
            when (meta?.number("status")) {
                null, 200 -> Unit
                401, 403 -> throw GiphyException(GiphyException.Reason.BAD_KEY)
                429 -> throw GiphyException(GiphyException.Reason.RATE_LIMITED)
                else -> throw GiphyException(GiphyException.Reason.SERVER)
            }
            val data = root["data"] as? JsonArray ?: throw GiphyException(GiphyException.Reason.SERVER)
            // 說明文件：狀態 200、沒有 response_id、也沒有資料的是「假的回應」，要當作錯誤
            if (data.isEmpty() && meta?.text("response_id").isNullOrEmpty()) throw GiphyException(GiphyException.Reason.SERVER)
            val items = data.mapNotNull { (it as? JsonObject)?.let { o -> item(o, sdk) } }
            val pagination = root["pagination"] as? JsonObject
            val count = pagination?.number("count") ?: data.size
            val total = pagination?.number("total_count")
            val end = offset + count
            val more = count > 0 && end < MAX_RESULTS && (if (total != null) end < total else count >= LIMIT)
            return GiphyPage(items, end.takeIf { more })
        }

        /** 一張圖：沒有可以顯示的網址就跳過 */
        private fun item(o: JsonObject, sdk: Int): GiphyItem? {
            val id = o.text("id")?.takeIf(::isValidId) ?: return null
            val images = o["images"] as? JsonObject ?: return null
            val webp = sdk >= Build.VERSION_CODES.P
            var width: Int? = null
            var height: Int? = null
            val animated = ANIMATED.firstNotNullOfOrNull { name ->
                val r = images[name] as? JsonObject ?: return@firstNotNullOfOrNull null
                // 舊的手機不能播會動的 WebP
                val url = (if (webp) https(r.text("webp")) else null) ?: https(r.text("url")) ?: return@firstNotNullOfOrNull null
                width = r.number("width")
                height = r.number("height")
                url
            } ?: return null
            val still = STILLS.firstNotNullOfOrNull { https((images[it] as? JsonObject)?.text("url")) } ?: animated
            val user = o["user"] as? JsonObject
            val creator = listOf(user?.text("display_name"), user?.text("username"), o.text("username"))
                .firstNotNullOfOrNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            return GiphyItem(id, o.text("title")?.trim().orEmpty(), creator, animated, still, width, height)
        }

        private fun https(url: String?) = url?.takeIf { it.startsWith("https://") }
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // 寬高、數量都可能是字串（"200"）
        private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    }
}

/**
 * 顯示貼圖圖片的連線（只給 Coil 用，見 [tw.xiaoliangkou.app.ui.components.stickerImageLoader]）：
 * [giphyHttp] 加上記憶體裡的圖片快取 [GiphyMediaCache]。搜尋和通知的大圖還是用 [giphyHttp]
 */
internal val giphyMediaHttp: OkHttpClient = giphyHttp.newBuilder().addInterceptor(GiphyMediaCache()).build()

/**
 * 貼圖圖片的記憶體快取。Coil 只把不會動的圖放進它的記憶體快取（會動的每次都重新解碼），
 * 沒有這個的話，會動的貼圖每次出現都要再向 GIPHY 下載一次：回到首頁、捲回來的格子先閃一下載入中，
 * 沒網路時剛剛還看得到的貼圖就變成 🖼️。
 * 只放在 App 的記憶體裡、App 關掉就沒了（GIPHY 不准存檔），全部最多 [maxBytes]；
 * 只記 GIPHY 圖片網址（media*.giphy.com、i.giphy.com）的 GET、200 的回應，一張超過 [maxEntry] 的不記。網址原封不動當作 key
 */
class GiphyMediaCache(private val maxBytes: Long = MAX_BYTES, private val maxEntry: Long = Giphy.MAX_STILL_BYTES) : Interceptor {
    private class Entry(val bytes: ByteArray, val type: String?)

    /** 網址 → 圖片；最久沒用到的在前面 */
    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var size = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET" || !req.url.isHttps || !MEDIA_HOST.matches(req.url.host)) return chain.proceed(req)
        val key = req.url.toString()
        synchronized(this) { entries[key] }?.let { e ->
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .apply { if (e.type != null) header("Content-Type", e.type) }
                .body(e.bytes.toResponseBody(e.type?.toMediaTypeOrNull())).build()
        }
        val res = chain.proceed(req)
        if (res.code != 200) return res
        // 先偷看：太大的不記，原本的回應照樣往下傳
        val bytes = res.peekBody(maxEntry + 1).bytes()
        if (bytes.size > maxEntry) return res
        val type = res.header("Content-Type")
        res.close()
        put(key, Entry(bytes, type))
        return res.newBuilder().body(bytes.toResponseBody(type?.toMediaTypeOrNull())).build()
    }

    private fun put(key: String, e: Entry) = synchronized(this) {
        entries.put(key, e)?.let { size -= it.bytes.size }
        size += e.bytes.size
        // 超過了：從最久沒用到的開始丟
        val it = entries.values.iterator()
        while (size > maxBytes && it.hasNext()) {
            val old = it.next()
            if (old === e) break
            size -= old.bytes.size
            it.remove()
        }
    }

    companion object {
        /** 全部最多放多少（一張會動的貼圖大約 10–100 KB） */
        const val MAX_BYTES = 12L * 1024 * 1024
        private val MEDIA_HOST = Regex("^(media\\d*|i)\\.giphy\\.com$")
    }
}
