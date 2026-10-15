package tw.xiaoliangkou.app.core

/** 心情、安撫、消費類別、來源的文字與表情符號 */
object Catalog {
    data class Mood(val key: String, val label: String, val emoji: String, val valence: Int) {
        val needsComfort get() = valence < 0
    }

    val moods = listOf(
        Mood("happy", "開心", "😊", 2),
        Mood("sweet", "甜蜜", "🥰", 2),
        Mood("calm", "平靜", "🙂", 1),
        Mood("missyou", "想你", "🥺", 0),
        Mood("tired", "好累", "😩", -1),
        Mood("anxious", "焦慮", "😰", -1),
        Mood("sad", "難過", "😢", -2),
        Mood("angry", "生氣", "😠", -2),
        Mood("sick", "不舒服", "🤒", -2),
    )

    fun mood(key: String?) = moods.firstOrNull { it.key == key }

    data class Comfort(val key: String, val label: String, val emoji: String, val push: String)

    val comforts = listOf(
        Comfort("hug", "抱抱", "🤗", "給了你一個大大的抱抱"),
        Comfort("pat", "摸摸頭", "💆", "摸摸你的頭，說辛苦了"),
        Comfort("kiss", "親親", "😘", "親了你一下"),
        Comfort("here", "我在", "💬", "說：我在，想聊隨時找我"),
        Comfort("cheer", "加油", "💪", "幫你加油打氣"),
        Comfort("treat", "請你喝", "🥤", "說等等請你喝一杯"),
    )

    fun comfort(key: String?) = comforts.firstOrNull { it.key == key }

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
