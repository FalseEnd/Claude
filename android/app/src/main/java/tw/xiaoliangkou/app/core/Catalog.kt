package tw.xiaoliangkou.app.core

/** 心情、安撫、消費類別、來源的文字與表情符號 */
object Catalog {
    /** 安撫的悄悄話最多幾個字（和資料庫 comforts.message 的 check 一樣） */
    const val MESSAGE_MAX = 200
    /** 直接回一句話（首頁收到的安撫、通知上的「💬 回覆」）時送的安撫：「我在 💬」，悄悄話就是打的字 */
    const val REPLY_KIND = "here"

    /** 畫面上的一組選項（心情分成三組、安撫分成四組，選項多了比較好找） */
    data class Group<T>(val title: String, val items: List<T>)

    /**
     * [giphyQuery] 是選 GIPHY 貼圖時先幫忙搜尋的英文關鍵字（英文的結果多很多）。
     * [extra] 是 0.2.3 才多的：資料庫要更新到 schema 5 才存得進去，還沒更新時不顯示
     */
    data class Mood(val key: String, val label: String, val emoji: String, val valence: Int, val giphyQuery: String, val extra: Boolean = false) {
        val needsComfort get() = valence < 0
        /** 資料庫要到哪一版才存得進去（0＝一開始就有） */
        val since get() = if (extra) 5 else 0
    }

    // 種類和文字要和資料庫一樣（supabase/migrations/20261024000008_more_moods.sql、…09_more_comforts.sql；MoreMoodsTest 會比對）
    val moodGroups = listOf(
        Group(
            "好心情",
            listOf(
                Mood("happy", "開心", "😊", 2, "happy"),
                Mood("sweet", "甜蜜", "🥰", 2, "love"),
                Mood("excited", "興奮", "🤩", 2, "excited", extra = true),
                Mood("laugh", "好好笑", "😂", 2, "lol", extra = true),
                Mood("calm", "平靜", "🙂", 1, "chill"),
                Mood("relaxed", "放鬆", "😌", 1, "relax", extra = true),
            ),
        ),
        Group(
            "日常",
            listOf(
                Mood("missyou", "想你", "🥺", 0, "miss you"),
                Mood("shy", "害羞", "🙈", 1, "shy", extra = true),
                Mood("sleepy", "想睡", "😴", 0, "sleepy", extra = true),
                Mood("hungry", "肚子餓", "🤤", 0, "hungry", extra = true),
                Mood("bored", "無聊", "😑", 0, "bored", extra = true),
                Mood("busy", "忙翻了", "😵", 0, "so busy", extra = true),
            ),
        ),
        // 這一組對方會看到「要不要安撫一下？」
        Group(
            "需要抱抱",
            listOf(
                Mood("tired", "好累", "😩", -1, "tired"),
                Mood("anxious", "焦慮", "😰", -1, "nervous"),
                Mood("stressed", "壓力大", "😖", -1, "stressed", extra = true),
                Mood("wronged", "委屈", "😣", -2, "pouting", extra = true),
                Mood("sad", "難過", "😢", -2, "sad"),
                Mood("angry", "生氣", "😠", -2, "angry"),
                Mood("jealous", "吃醋", "😤", -1, "jealous", extra = true),
                Mood("lonely", "寂寞", "😔", -1, "lonely", extra = true),
                Mood("sick", "不舒服", "🤒", -2, "sick"),
            ),
        ),
    )

    val moods = moodGroups.flatMap { it.items }

    fun mood(key: String?) = moods.firstOrNull { it.key == key }

    /** [since] 是資料庫要到哪一版才存得進去：0＝一開始就有，5＝0.2.3 多的，6＝0.2.6 多的 */
    data class Comfort(val key: String, val label: String, val emoji: String, val push: String, val giphyQuery: String, val since: Int = 0) {
        val extra get() = since > 0
    }

    val comfortGroups = listOf(
        Group(
            "安慰",
            listOf(
                Comfort("hug", "抱抱", "🤗", "給了你一個大大的抱抱", "hug"),
                Comfort("pat", "摸摸頭", "💆", "摸摸你的頭，說辛苦了", "head pat"),
                Comfort("here", "我在", "💬", "說：我在，想聊隨時找我", "here for you"),
                Comfort("cheer", "加油", "💪", "幫你加油打氣", "you can do it"),
                Comfort("support", "挺你", "🛡️", "說永遠站在你這邊", "i got you", since = 5),
                Comfort("treat", "請你喝", "🥤", "說等等請你喝一杯", "bubble tea"),
                Comfort("listen", "聽你說", "👂", "說想聽你說說今天", "i am listening", since = 6),
                Comfort("holdhand", "牽手", "🤝", "牽起你的手", "holding hands", since = 6),
                Comfort("proud", "你最棒", "🏆", "說你是最棒的", "proud of you", since = 6),
            ),
        ),
        Group(
            "甜蜜",
            listOf(
                Comfort("kiss", "親親", "😘", "親了你一下", "kiss"),
                Comfort("love", "愛你", "❤️", "說愛你", "love you", since = 5),
                Comfort("missyou", "想你", "💭", "說好想你", "miss you", since = 5),
                Comfort("flower", "送花", "💐", "送你一束花", "flowers for you", since = 5),
                Comfort("morning", "早安", "☀️", "跟你說早安", "good morning", since = 5),
                Comfort("night", "晚安", "🌙", "跟你說晚安，好夢", "good night", since = 5),
                Comfort("heart", "比心", "💗", "對你比了一個愛心", "finger heart", since = 6),
                Comfort("cuddle", "蹭蹭", "🐻", "蹭蹭你撒嬌", "cuddle", since = 6),
                Comfort("date", "想約會", "💑", "說想跟你約會", "date night", since = 6),
            ),
        ),
        Group(
            "關心",
            listOf(
                Comfort("water", "多喝水", "💧", "提醒你記得多喝水", "drink water", since = 5),
                Comfort("meal", "吃飯沒", "🍱", "問你吃飯了沒", "lets eat", since = 5),
                Comfort("rest", "早點睡", "🛌", "叫你早點休息", "go to sleep", since = 5),
                Comfort("warm", "穿暖", "🧣", "提醒你穿暖一點", "stay warm", since = 6),
                Comfort("gethome", "到家沒", "🏠", "問你到家了沒", "home safe", since = 6),
                Comfort("safe", "路上小心", "🚗", "叫你路上小心", "be safe", since = 6),
            ),
        ),
        Group(
            "逗你開心",
            listOf(
                Comfort("joke", "冷笑話", "🤡", "講了一個冷笑話給你", "bad joke", since = 6),
                Comfort("silly", "扮鬼臉", "😜", "對你扮了一個鬼臉", "silly face", since = 6),
                Comfort("kitty", "送小貓", "🐱", "送你一隻可愛的小貓", "cute kitten", since = 6),
                Comfort("dessert", "吃甜點", "🍰", "說要請你吃甜點", "dessert", since = 6),
                Comfort("gift", "小禮物", "🎁", "說準備了小禮物給你", "gift for you", since = 6),
            ),
        ),
    )

    val comforts = comfortGroups.flatMap { it.items }

    fun comfort(key: String?) = comforts.firstOrNull { it.key == key }

    /** 選貼圖時的快速關鍵字：中文的標籤，搜尋用英文 */
    val stickerQueries = listOf(
        "抱抱" to "hug", "親親" to "kiss", "愛你" to "love you", "想你" to "miss you", "晚安" to "good night", "加油" to "you got this",
        "開心" to "happy", "哭哭" to "crying", "生氣" to "angry", "好累" to "tired", "肚子餓" to "hungry", "謝謝" to "thank you",
    )

    data class Category(val key: String, val label: String, val emoji: String)

    val categories = listOf(
        Category("drink", "咖啡飲料", "☕"),
        Category("food", "餐飲", "🍜"),
        Category("transport", "交通", "🚇"),
        Category("daily", "日用", "🧻"),
        Category("shopping", "購物", "🛍️"),
        Category("fun", "娛樂", "🎬"),
        Category("gift", "禮物", "🎁"),
        Category("other", "其他", "🧾"),
    )

    fun category(key: String?) = categories.firstOrNull { it.key == key } ?: categories.last()

    val sources = mapOf(
        "linepay" to "LINE Pay", "jkopay" to "街口支付", "applepay" to "行動支付", "card" to "信用卡通知",
        "easywallet" to "悠遊付", "pxpay" to "全支付", "pluspay" to "全盈+PAY", "icashpay" to "icash Pay", "taiwanpay" to "台灣Pay",
        "pipay" to "Pi 拍錢包", "opay" to "歐付寶", "notification" to "付款通知", "shortcut" to "捷徑", "einvoice" to "發票載具",
        "ocr" to "拍收據", "manual" to "手動記帳", "other" to "付款通知",
    )

    val placeIcons = listOf("home", "work", "school", "gym", "cafe", "food", "shop", "heart", "pin")

    val symptoms = listOf("經痛", "腰痠", "頭痛", "脹氣", "情緒低落", "疲倦", "想吃甜食", "長痘痘")
}
