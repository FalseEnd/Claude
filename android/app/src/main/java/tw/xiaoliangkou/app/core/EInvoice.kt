package tw.xiaoliangkou.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 雲端發票（手機條碼載具）：財政部「載具發票表頭查詢」API 的回應、電子發票平台匯出的消費明細 CSV，
 * 和把發票上的公司名稱（「統一超商股份有限公司台北市第一一七分公司」）變成店名（「7-ELEVEN」）
 */
object EInvoice {
    data class Invoice(
        val number: String,
        val date: LocalDate,
        /** 發票時間；CSV 沒有時間是 null */
        val time: LocalTime?,
        val seller: String,
        val amount: Double,
        val sellerBan: String? = null,
    ) {
        val at: LocalDateTime get() = date.atTime(time ?: NOON)
    }

    private val NOON = LocalTime.of(12, 0)
    private val json = Json { ignoreUnknownKeys = true }

    /** 手機條碼：斜線開頭、後面 7 碼（數字、大寫英文、+ - .） */
    val CARD = Regex("""^/[0-9A-Z.+\-]{7}$""")
    private val INV_NUM = Regex("""[A-Z]{2}-?\d{8}""")

    /** 「載具發票表頭查詢」的結果 */
    data class Page(val code: String, val message: String, val invoices: List<Invoice>)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()

    /** 解析 carrierInvChk 的回應（看不懂的拋 IllegalArgumentException） */
    fun parseHeader(text: String): Page {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalArgumentException("看不懂財政部的回應", it) }
        val code = root.str("code") ?: throw IllegalArgumentException("看不懂財政部的回應")
        val list = (root["details"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val num = o.str("invNum")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // 作廢、註銷的發票不是花費
            if (o.str("invStatus")?.let { it.contains("作廢") || it.contains("註銷") } == true) return@mapNotNull null
            val amount = o.str("amount")?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val d = o["invDate"] as? JsonObject
            val date = d?.let {
                val y = it.str("year")?.toIntOrNull() ?: return@let null
                val m = it.str("month")?.toIntOrNull() ?: return@let null
                val day = it.str("date")?.toIntOrNull() ?: return@let null
                // 年份是民國年（112）或西元年都可以
                runCatching { LocalDate.of(if (y < 1911) y + 1911 else y, m, day) }.getOrNull()
            } ?: return@mapNotNull null
            val time = o.str("invoiceTime")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            Invoice(num, date, time, o.str("sellerName").orEmpty(), amount, o.str("sellerBan"))
        }
        return Page(code, root.str("msg").orEmpty(), list)
    }

    /**
     * 電子發票平台匯出的消費明細（載具 › 消費明細 › 匯出 CSV）：
     * `M|載具名稱|載具號碼|發票日期|商店統編|商店店名|發票號碼|總金額|發票狀態|` 是一張發票，`D|…` 是品項（不用）。
     * 格式改過的話也盡量讀：一行裡找得到發票號碼、日期、金額就算一張
     */
    fun parseCsv(text: String): List<Invoice> {
        val out = linkedMapOf<String, Invoice>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty() || line.startsWith("D|") || line.startsWith("D,")) continue
            val fields = line.split(if ('|' in line) '|' else ',').map { it.trim().trim('"') }
            val inv = if (fields.firstOrNull() == "M" && fields.size >= 8) fromMLine(fields) else guess(fields)
            if (inv != null && inv.number !in out) out[inv.number] = inv
        }
        return out.values.toList()
    }

    private fun fromMLine(f: List<String>): Invoice? {
        if (f.getOrNull(8)?.let { it.contains("作廢") || it.contains("註銷") } == true) return null
        val date = dateOf(f[3]) ?: return null
        val amount = f[7].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val num = INV_NUM.find(f[6])?.value?.replace("-", "") ?: return null
        return Invoice(num, date, null, f[5], amount, f[4].takeIf { it.isNotEmpty() })
    }

    /** 不認得的格式：發票號碼、日期、金額（最後一個數字欄位）、店名（最長的文字欄位） */
    private fun guess(f: List<String>): Invoice? {
        if (f.any { it.contains("作廢") || it.contains("註銷") }) return null
        val num = f.firstNotNullOfOrNull { INV_NUM.matchEntire(it)?.value }?.replace("-", "") ?: return null
        val date = f.firstNotNullOfOrNull(::dateOf) ?: return null
        val amount = f.filter { Regex("""^[\d,]+(?:\.\d+)?$""").matches(it) && dateOf(it) == null && it.length < 9 }
            .lastOrNull()?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val seller = f.filter { s -> s.any { it.isLetter() } && INV_NUM.matchEntire(s) == null && !s.contains("載具") && !s.startsWith("/") }
            .maxByOrNull { it.length }.orEmpty()
        return Invoice(num, date, null, seller, amount)
    }

    private val dateFormats = listOf("yyyyMMdd", "yyyy/MM/dd", "yyyy-MM-dd", "yyyy/M/d").map { DateTimeFormatter.ofPattern(it) }

    private fun dateOf(s: String): LocalDate? {
        val t = s.trim()
        if (!Regex("""^\d{4}[/\-]?\d{1,2}[/\-]?\d{1,2}$""").matches(t)) {
            // 民國年 112/06/09
            val roc = Regex("""^(\d{2,3})[/\-](\d{1,2})[/\-](\d{1,2})$""").matchEntire(t) ?: return null
            return runCatching { LocalDate.of(roc.groupValues[1].toInt() + 1911, roc.groupValues[2].toInt(), roc.groupValues[3].toInt()) }.getOrNull()
        }
        return dateFormats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() }
    }

    /** 發票上的公司名稱 → 大家叫的店名：連鎖店換成品牌，其他的拿掉公司字尾和分公司 */
    fun storeName(seller: String): String {
        val s = seller.trim()
        brands.firstOrNull { (keys, _) -> keys.any { s.contains(it, ignoreCase = true) } }?.let { return it.second }
        val cut = s.replace(Regex("""(?:股份有限公司|有限公司|企業社|商行|實業社).*$"""), "")
            .replace(Regex("""(?:台灣|臺灣)?分公司.*$"""), "")
            .trim()
        return cut.ifEmpty { s }.take(60)
    }

    /** 常見連鎖店的公司名稱（發票上的賣方名稱）→ 店名 */
    private val brands = listOf(
        listOf("統一超商") to "7-ELEVEN",
        listOf("全家便利商店") to "全家",
        listOf("萊爾富") to "萊爾富",
        listOf("來來超商") to "OK超商",
        listOf("全聯實業", "全聯福利") to "全聯",
        listOf("家福股份", "家樂福") to "家樂福",
        listOf("好市多") to "好市多",
        listOf("大潤發") to "大潤發",
        listOf("三商家購") to "美廉社",
        listOf("統一星巴克", "星巴克") to "星巴克",
        listOf("路易莎") to "路易莎咖啡",
        listOf("麥當勞") to "麥當勞",
        listOf("肯德基") to "肯德基",
        listOf("安心食品") to "摩斯漢堡",
        listOf("台灣比薩", "必勝客") to "必勝客",
        listOf("達美樂") to "達美樂",
        listOf("漢堡王") to "漢堡王",
        listOf("屈臣氏") to "屈臣氏",
        listOf("康是美", "統一藥品") to "康是美",
        listOf("寶雅") to "寶雅",
        listOf("大創") to "大創",
        listOf("優食台灣", "Uber Eats") to "Uber Eats",
        listOf("富胖達", "foodpanda") to "foodpanda",
        listOf("台灣大車隊") to "台灣大車隊",
        listOf("台灣中油") to "中油",
        listOf("台灣高速鐵路") to "台灣高鐵",
        listOf("台北大眾捷運", "臺北大眾捷運") to "台北捷運",
        listOf("網路家庭") to "PChome",
        listOf("富邦媒體") to "momo",
        listOf("樂購蝦皮", "蝦皮") to "蝦皮購物",
        listOf("優衣庫", "UNIQLO") to "UNIQLO",
        listOf("無印良品") to "無印良品",
        listOf("宜家家居", "IKEA") to "IKEA",
        listOf("燦坤") to "燦坤",
        listOf("全國電子") to "全國電子",
        listOf("威秀影城") to "威秀影城",
        listOf("秀泰") to "秀泰影城",
        listOf("鼎泰豐") to "鼎泰豐",
        listOf("王品") to "王品",
        listOf("瓦城") to "瓦城",
        listOf("八方雲集") to "八方雲集",
        listOf("爭鮮") to "爭鮮",
        listOf("壽司郎") to "壽司郎",
        listOf("清心福全") to "清心福全",
        listOf("五十嵐", "50嵐") to "50嵐",
    )
}
