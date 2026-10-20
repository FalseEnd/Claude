package tw.xiaoliangkou.app

import kotlinx.coroutines.awaitCancellation
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphyException
import tw.xiaoliangkou.app.data.GiphyItem
import tw.xiaoliangkou.app.data.GiphyKind
import tw.xiaoliangkou.app.data.GiphyPage
import tw.xiaoliangkou.app.data.GiphyQuery
import tw.xiaoliangkou.app.data.GiphySearcher
import java.util.Collections

/**
 * 假的 GIPHY 搜尋（選貼圖的畫面用）：不連網路，記下查了什麼、試了哪個 key。
 * 結果的網址跟 API 回傳的一樣長在 media.giphy.com 底下，[FakeStickers] 會照 id 畫出假的圖
 */
class FakeGiphy(
    /** 查的時候要丟的錯（null 是查得到） */
    var fail: GiphyException.Reason? = null,
    /** 一直等不回來：載入中的畫面 */
    var hang: Boolean = false,
) : GiphySearcher {
    val asked: MutableList<GiphyQuery> = Collections.synchronizedList(mutableListOf())
    val checked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 試 key 時要丟的錯 */
    var keyFails: GiphyException.Reason? = null

    override suspend fun page(q: GiphyQuery): GiphyPage {
        asked += q
        if (hang) awaitCancellation()
        fail?.let { throw GiphyException(it) }
        return page(q.kind, q.query, q.offset)
    }

    override suspend fun checkKey(key: String) {
        checked += key
        keyFails?.let { throw GiphyException(it) }
    }

    companion object {
        /** 格式正確的假 key */
        const val KEY = "xlkTestKey0123456789abcd"
        /** 換一張貼圖時用的另外兩張（[FakeStickers] 依 id 畫出不同的圖） */
        const val STICKER_B = "xT4uQulxzV39haRFjG"
        const val STICKER_C = "IzXiddo2twMmdmU8Lv"

        /** 一頁結果：id 由種類、關鍵字、第幾張決定，同一頁每次都一樣；最多 [Giphy.MAX_RESULTS] 張 */
        fun page(kind: GiphyKind, query: String, offset: Int, size: Int = Giphy.LIMIT): GiphyPage {
            val tag = query.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifEmpty { "trending" }
            val items = (offset until offset + size).map { i ->
                val id = "${kind.path.take(1)}$tag${i + 1}"
                val base = "https://media2.giphy.com/media/v1.Y2lkPXhsa3Rlc3Q/$id"
                GiphyItem(id, "${query.ifEmpty { "trending" }} ${i + 1}", "xlk_artist", "$base/200w_d.webp", "$base/200w_s.gif", 200, 200)
            }
            return GiphyPage(items, (offset + size).takeIf { it < Giphy.MAX_RESULTS })
        }

        /** 格子上唸給看不到畫面的人聽的字（[GiphyItem.description]） */
        fun description(query: String, n: Int) = "$query $n，xlk_artist"
    }
}
