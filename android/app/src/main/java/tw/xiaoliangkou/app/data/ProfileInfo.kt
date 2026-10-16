package tw.xiaoliangkou.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.Period

/**
 * 個人資料（profiles.info）：資料庫只存一包 JSON，有哪些欄位由 App 決定。
 * 身高、體重存數字，其他都是字串；年齡、星座由生日算，不另外存。
 */
object ProfileInfo {
    const val HEIGHT = "heightCm"
    const val WEIGHT = "weightKg"
    const val PHONE = "phone"
    const val BLOOD_TYPE = "bloodType"
    const val MBTI = "mbti"
    const val JOB = "job"
    const val HOBBIES = "hobbies"
    const val LIKES = "likes"
    const val DISLIKES = "dislikes"
    const val ALLERGIES = "allergies"
    const val CLOTHES_SIZE = "clothesSize"
    const val SHOE_SIZE = "shoeSize"
    const val INTRO = "intro"

    val bloodTypes = listOf("A", "B", "O", "AB")

    /** 畫面上的一個欄位；[maxLength] 是最多幾個字（資料庫整包最多 4000 bytes） */
    data class Field(
        val key: String,
        val label: String,
        val emoji: String,
        val numeric: Boolean = false,
        val unit: String? = null,
        val hint: String? = null,
        val maxLength: Int = 40,
    )

    val fields = listOf(
        Field(HEIGHT, "身高", "📏", numeric = true, unit = "cm", hint = "160", maxLength = 5),
        Field(WEIGHT, "體重", "⚖️", numeric = true, unit = "kg", hint = "48", maxLength = 5),
        Field(PHONE, "電話", "📞", hint = "0912-345-678", maxLength = 20),
        Field(BLOOD_TYPE, "血型", "🩸", hint = "A / B / O / AB", maxLength = 2),
        Field(MBTI, "MBTI", "🧠", hint = "INFP", maxLength = 6),
        Field(JOB, "工作", "💼", hint = "設計師"),
        Field(HOBBIES, "興趣", "🎨", hint = "追劇、爬山", maxLength = 100),
        Field(LIKES, "喜歡的", "💗", hint = "珍奶半糖少冰、貓咪", maxLength = 100),
        Field(DISLIKES, "不喜歡的", "🙅", hint = "香菜、蟑螂", maxLength = 100),
        Field(ALLERGIES, "過敏", "🤧", hint = "蝦子、花粉", maxLength = 100),
        Field(CLOTHES_SIZE, "衣服尺寸", "👕", hint = "M", maxLength = 10),
        Field(SHOE_SIZE, "鞋子尺寸", "👟", hint = "23.5", maxLength = 10),
        Field(INTRO, "自我介紹", "💬", hint = "用一句話介紹自己", maxLength = 200),
    )

    fun field(key: String): Field? = fields.firstOrNull { it.key == key }

    /** 欄位的文字（數字會去掉多餘的 .0）；沒填是 null */
    fun text(info: JsonObject, key: String): String? {
        val p = info[key] as? JsonPrimitive ?: return null
        if (field(key)?.numeric == true) number(info, key)?.let { return format(it) }
        return p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 數字欄位（身高、體重）；舊資料存成字串也讀得到 */
    fun number(info: JsonObject, key: String): Double? {
        val p = info[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.trim()?.let(::parseNumber)
    }

    /** 給對方看的樣子：「160 cm」 */
    fun display(info: JsonObject, f: Field): String? = text(info, f.key)?.let { if (f.unit != null) "$it ${f.unit}" else it }

    /** 有填的欄位（照 [fields] 的順序） */
    fun filled(info: JsonObject): List<Pair<Field, String>> = fields.mapNotNull { f -> display(info, f)?.let { f to it } }

    /**
     * 要存的 info：[values] 是畫面上的輸入（key → 文字），空白的欄位移除，數字欄位轉成數字（看不懂的也移除）。
     * [base] 是原本的 info，新版 App 加的欄位不會被舊版洗掉。
     */
    fun build(values: Map<String, String?>, base: JsonObject = JsonObject(emptyMap())): JsonObject {
        val out = base.filterValues { it !is JsonPrimitive || !(it.isString && it.content.isBlank()) }.toMutableMap()
        for ((key, raw) in values) {
            val f = field(key)
            val v = raw?.trim().orEmpty().let { if (f != null) it.take(f.maxLength) else it }
            val value: JsonPrimitive? = when {
                v.isEmpty() -> null
                f?.numeric == true -> parseNumber(v)?.takeIf { it > 0 && it < 1000 }?.let(::numberOf)
                key == BLOOD_TYPE -> JsonPrimitive(v.uppercase())
                key == MBTI -> JsonPrimitive(v.uppercase())
                else -> JsonPrimitive(v)
            }
            if (value == null) out.remove(key) else out[key] = value
        }
        return JsonObject(out)
    }

    /** 畫面編輯用：info → key → 文字 */
    fun values(info: JsonObject): Map<String, String> = fields.associate { it.key to (text(info, it.key) ?: "") }

    /** 滿幾歲（生日還沒到就少一歲；2/29 出生的人在平年 3/1 才加一歲） */
    fun age(birthday: LocalDate, today: LocalDate = LocalDate.now()): Int =
        if (birthday.isAfter(today)) 0 else Period.between(birthday, today).years

    private fun parseNumber(s: String): Double? = s.replace(',', '.').replace("，", ".").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun numberOf(d: Double): JsonPrimitive {
        val r = Math.round(d * 10) / 10.0
        return if (r == Math.floor(r)) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
    }

    private fun format(d: Double): String {
        val r = Math.round(d * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }
}
