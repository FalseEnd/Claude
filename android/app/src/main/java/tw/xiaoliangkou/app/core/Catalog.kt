package tw.xiaoliangkou.app.core

/** 心情、安撫、消費類別、來源的文字與表情符號 */
object Catalog {
    /** 畫面上的一組選項（心情、安撫都分成三組，選項多了比較好找） */
    data class Group<T>(val title: String, val items: List<T>)

    /**
     * [giphyQuery] 是選 GIPHY 貼圖時先幫忙搜尋的英文關鍵字（英文的結果多很多）。
     * [extra] 是 0.2.3 才多的：資料庫要更新到 schema 5 才存得進去，還沒更新時不顯示
     */
    data class Mood(val key: String, val label: String, val emoji: String, val valence: Int, val giphyQuery: String, val extra: Boolean = false) {
        val needsComfort get() = valence < 0
    }

    // 種類和文字要和資料庫一樣（supabase/migrations/20261024000008_more_moods.sql；CatalogSqlTest 會比對）
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

    data class Comfort(val key: String, val label: String, val emoji: String, val push: String, val giphyQuery: String, val extra: Boolean = false)

    val comfortGroups = listOf(
        Group(
            "安慰",
            listOf(
                Comfort("hug", "抱抱", "🤗", "給了你一個大大的抱抱", "hug"),
                Comfort("pat", "摸摸頭", "💆", "摸摸你的頭，說辛苦了", "head pat"),
                Comfort("here", "我在", "💬", "說：我在，想聊隨時找我", "here for you"),
                Comfort("cheer", "加油", "💪", "幫你加油打氣", "you can do it"),
                Comfort("support", "挺你", "🛡️", "說永遠站在你這邊", "i got you", extra = true),
                Comfort("treat", "請你喝", "🥤", "說等等請你喝一杯", "bubble tea"),
            ),
        ),
        Group(
            "甜蜜",
            listOf(
                Comfort("kiss", "親親", "😘", "親了你一下", "kiss"),
                Comfort("love", "愛你", "❤️", "說愛你", "love you", extra = true),
                Comfort("missyou", "想你", "💭", "說好想你", "miss you", extra = true),
                Comfort("flower", "送花", "💐", "送你一束花", "flowers for you", extra = true),
                Comfort("morning", "早安", "☀️", "跟你說早安", "good morning", extra = true),
                Comfort("night", "晚安", "🌙", "跟你說晚安，好夢", "good night", extra = true),
            ),
        ),
        Group(
            "關心",
            listOf(
                Comfort("water", "多喝水", "💧", "提醒你記得多喝水", "drink water", extra = true),
                Comfort("meal", "吃飯沒", "🍱", "問你吃飯了沒", "lets eat", extra = true),
                Comfort("rest", "早點睡", "🛌", "叫你早點休息", "go to sleep", extra = true),
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
        "easywallet" to "悠遊付", "notification" to "付款通知", "shortcut" to "捷徑", "einvoice" to "發票載具",
        "ocr" to "拍收據", "manual" to "手動記帳", "other" to "付款通知",
    )

    val placeIcons = listOf("home", "work", "school", "gym", "cafe", "food", "shop", "heart", "pin")

    val symptoms = listOf("經痛", "腰痠", "頭痛", "脹氣", "情緒低落", "疲倦", "想吃甜食", "長痘痘")
}
