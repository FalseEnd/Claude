package tw.xiaoliangkou.app.core

/**
 * 自動記帳：從付款通知找出金額、商家、付款方式和類別。
 *
 * 各家通知的寫法不一樣、也常改版（例如 LINE Pay 的通知現在是「LINE錢包」官方帳號在 LINE 裡發的），
 * 所以不靠單一格式：
 * - 金額：「金額：」標籤，或 NT$、新台幣、TWD、$ 後面的數字、「… 元」；跳過餘額、額度、回饋這種不是付款的金額
 * - 商家：「商店：」「店家名稱：」這類標籤 → 「交易：X」→「給 X」→「於／在 X 付款」→ 金額後面括號裡的店名
 *   → 金額旁邊單獨一行的店名
 * - 找不到店名（LINE 官方說明：直接刷卡的消費，通知不一定有店名）就記成「LINE Pay 消費」這類名字，之後可以自己改
 */
object PaymentParser {
    /** [knownMerchant] 為 false：通知裡沒有店名，[merchant] 是用付款方式代替的 */
    data class Parsed(
        val amount: Double,
        val merchant: String,
        val source: String,
        val category: String,
        val knownMerchant: Boolean = true,
    )

    /** 一則系統通知：哪個 App、標題，以及內文（展開後的全文、每一行、聊天訊息） */
    data class Notice(val packageName: String, val title: String?, val lines: List<String>)

    sealed interface Result {
        data class Payment(val parsed: Parsed) : Result
        data class Skipped(val reason: String) : Result
    }

    const val LINE_APP = "jp.naver.line.android"

    /** 專門的錢包 App：通知本身就是交易，不必再出現「付款」這種字（例如 Google 錢包：標題是店名、內文是金額） */
    private val walletApps = mapOf(
        "com.google.android.apps.walletnfcrel" to "applepay",
        "com.samsung.android.spay" to "applepay",
        "com.jkos.app" to "jkopay",
    )

    private val categoryWords = linkedMapOf(
        "drink" to listOf(
            "星巴克", "starbucks", "路易莎", "louisa", "cama", "85度c", "伯朗", "丹堤", "咖啡", "coffee", "cafe",
            "50嵐", "五十嵐", "coco都可", "清心", "可不可", "迷客夏", "茶湯會", "一沐日", "得正", "麻古", "鮮茶道", "大苑子",
            "春水堂", "珍煮丹", "龜記", "先喝道", "老虎堂", "飲料", "手搖", "茶",
        ),
        "food" to listOf(
            "麥當勞", "mcdonald", "摩斯", "mos burger", "肯德基", "kfc", "漢堡王", "burger king", "必勝客", "pizza hut",
            "達美樂", "domino", "拿坡里", "21世紀", "爭鮮", "壽司郎", "sushiro", "藏壽司", "鼎泰豐", "王品", "瓦城",
            "八方雲集", "四海遊龍", "麥味登", "拉亞", "美而美", "餐廳", "餐飲", "便當", "拉麵", "火鍋", "小吃", "食堂",
            "早餐", "早午餐", "燒肉", "牛排", "丼", "披薩", "甜點", "蛋糕", "麵包", "烘焙", "uber eats", "ubereats", "foodpanda",
        ),
        "transport" to listOf(
            // 悠遊卡、一卡通也是付款方式，不拿來判斷類別
            "高鐵", "台鐵", "臺鐵", "捷運", "uber", "計程車", "台灣大車隊", "中油", "台塑石油", "全國加油",
            "加油", "停車", "youbike", "irent", "goshare", "wemo", "客運", "國光", "統聯", "航空", "機票",
        ),
        "daily" to listOf(
            "7-eleven", "7-11", "統一超商", "全家", "familymart", "萊爾富", "hi-life", "ok超商", "ok mart", "美廉社", "全聯",
            "pxmart", "家樂福", "carrefour", "大潤發", "好市多", "costco", "頂好", "屈臣氏", "watsons", "康是美", "cosmed",
            "寶雅", "poya", "大創", "daiso", "小北百貨", "藥局", "超市", "超商", "便利商店",
        ),
        "shopping" to listOf(
            "uniqlo", "zara", "h&m", "nike", "adidas", "momo", "蝦皮", "shopee", "pchome", "博客來", "誠品", "無印良品", "muji",
            "ikea", "宜得利", "nitori", "新光三越", "sogo", "遠百", "遠東百貨", "微風", "京站", "百貨", "燦坤", "全國電子",
            "順發", "購物", "商城",
        ),
        "fun" to listOf(
            "威秀", "秀泰", "國賓", "影城", "電影", "netflix", "spotify", "disney", "kkbox", "youtube", "steam", "playstation",
            "nintendo", "app store", "google play", "ktv", "好樂迪", "錢櫃", "樂園", "門票", "展覽",
        ),
    )

    private val sources = listOf(
        // LINE Pay 的通知現在是「LINE錢包」官方帳號發的；中信 LINE Pay 卡也算
        "linepay" to Regex("""line\s*pay|line\s*錢包""", RegexOption.IGNORE_CASE),
        "jkopay" to Regex("街口"),
        "applepay" to Regex("""apple\s*pay|google\s*pay|google\s*錢包|google\s*wallet|samsung\s*pay""", RegexOption.IGNORE_CASE),
        "easywallet" to Regex("悠遊付"),
        "card" to Regex("信用卡|簽帳|卡片|刷卡|末四碼|卡號"),
    )

    /** 找不到店名時用的名字 */
    private val unnamed = mapOf(
        "linepay" to "LINE Pay 消費", "jkopay" to "街口支付消費", "applepay" to "行動支付消費",
        "easywallet" to "悠遊付消費", "card" to "信用卡消費",
    )

    /** 通知必須包含這些字才當作付款，避免把聊天訊息裡的金額記成帳 */
    private val paymentWords = Regex("付款|消費|交易|扣款|刷卡|支付|結帳|購買")
    /** 有這些字就不是一筆花費（退款、收款、儲值、驗證碼、帳單…） */
    private val notSpending = Regex("退款|退貨|取消|失敗|未成功|未完成|收款|入帳|儲值|加值|提領|驗證碼|OTP|動態密碼|繳款截止|本期帳單", RegexOption.IGNORE_CASE)
    /** 真的付款了才會出現的字；銀行、商店的官方帳號也常發「刷卡滿 3,000 享回饋」這種廣告 */
    private val doneWords = Regex("付款完成|付款成功|已付款|已支付|支付成功|支付完成|交易成功|交易完成|消費成功|扣款成功|已扣款|刷卡成功|授權成功|消費通知|交易通知|刷卡通知|付款通知|扣款通知|您已於|您於|已於")
    private val promo = Regex("""滿\s*$CUR?\s*[\d,]+\s*元?\s*(?:送|折|享|回饋|現折)|優惠|活動|抽獎|登錄|好禮|加碼|限時|領取|折價券|優惠券""", RegexOption.IGNORE_CASE)
    /** LINE 裡只看付款、銀行的官方帳號，朋友和群組的聊天不算 */
    private val lineOfficial = Regex(
        """line\s*錢包|line\s*pay|line\s*bank|銀行|信用卡|中信|中國信託|國泰|玉山|台新|富邦|永豐|聯邦|第一銀|一銀|華南|兆豐|合庫|合作金庫|""" +
            """彰銀|土銀|臺銀|台銀|上海商銀|遠東商銀|元大|星展|滙豐|匯豐|渣打|新光|凱基|王道|將來|樂天|街口|一卡通|悠遊""",
        RegexOption.IGNORE_CASE,
    )

    private const val NUM = """([\d,]+(?:\.\d+)?)"""
    private const val CUR = """(?:NT\$|NTD|TWD|新台幣|新臺幣|台幣|臺幣|\$)"""
    private val currencyAmount = Regex("""$CUR\s*$NUM|$NUM\s*元""", RegexOption.IGNORE_CASE)
    private val labeledAmount = Regex("""(?:付款金額|消費金額|交易金額|扣款金額|支付金額|刷卡金額|金額)\s*[:：]?\s*$CUR?\s*$NUM""", RegexOption.IGNORE_CASE)
    /** 金額前面是這些字的不是這次付款的錢 */
    private val otherAmount = Regex("""(?:餘額|可用|額度|回饋|點數|累積|贈送|上限|門檻)\s*[:：]?\s*$""")

    private const val NAME = """[^\n，,。；;｜|【】「」]"""
    // 「商店名稱 OK超商」：完整的標籤後面空一格就是店名（LINE錢包的付款訊息就是這樣）；
    // 「商店」這種短標籤可能出現在店名裡（全家便利商店），一定要接冒號或換行
    private val labeledMerchant = Regex(
        """(?:^|[\s｜|】：:，,。])(?:(?:商店名稱|店家名稱|特店名稱|商家名稱|消費店家|消費商店|交易店家|交易商店|收款商店|收款方|收款對象|付款對象)\s*[:：]?|""" +
            """(?:商店|店家|特店|商家|merchant|store)\s*(?:[:：]|(?=\n)))\s*($NAME{1,40})""",
        RegexOption.IGNORE_CASE,
    )
    /** 店名後面接著的其他欄位，切掉 */
    private val nextField = Regex(
        """\s*(?:付款後餘額|餘額|付款方式|付款工具|交易時間|付款時間|交易日期|日期|時間|金額|訂單編號|交易序號|交易編號|瞭解更多|查看更多|查看明細|line\s*pay).*$""",
        RegexOption.IGNORE_CASE,
    )
    private val tradeMerchant = Regex("""交易[:：]\s*([^\n，,。$]+?)\s*(?:$CUR|$)""", RegexOption.IGNORE_CASE)
    private val toMerchant = Regex("""(?:付款給|支付給|給)\s*($NAME{1,40})""")
    private val atMerchant = Regex("""(?:於|在)\s*($NAME{1,30}?)\s*(?:付款|消費|支付|刷卡|購買|交易|結帳)""")
    private val bracketMerchant = Regex("""(?:$CUR\s*[\d,]+(?:\.\d+)?|[\d,]+(?:\.\d+)?\s*元)\s*[（(]\s*([^）)\n]{2,30}?)\s*[）)]""", RegexOption.IGNORE_CASE)

    /** 單獨一行時不是店名的字 */
    private val noise = Regex(
        """【[^】]*】|\[[^\]]*\]|line\s*錢包|line\s*pay(?:\s*money)?|付款完成|付款成功|支付成功|支付完成|交易成功|交易完成|消費成功|""" +
            """已付款|付款通知|消費通知|交易通知|扣款通知|刷卡通知|通知|付款|消費|$CUR\s*[\d,]+(?:\.\d+)?|[\d,]+(?:\.\d+)?\s*元|""" +
            """(?:with\s+)?(?:visa|mastercard|master|jcb|amex|銀聯)\b|[•*·]+\s*\d{0,4}""",
        RegexOption.IGNORE_CASE,
    )
    private val notAStore = Regex("""您|的|請|已|於|在|金額|時間|日期|卡號|末四碼|餘額|點數|查看|明細|感謝|謝謝|銀行|line|\d{1,2}[/:]\d{2}""", RegexOption.IGNORE_CASE)
    /** 金額旁邊那一行裡，從這些欄位開始就不是店名了 */
    private val fields = Regex("""(?:付款後餘額|餘額|付款方式|付款工具|交易時間|付款時間|交易日期|日期|時間|訂單編號|交易序號|交易編號)\s*[:：]?.*$""")
    private val dateLike = Regex("""^\d{1,4}[/\-.]\d{1,2}|^[\d/\-.: ]+$""")

    fun categorize(text: String): String {
        val m = text.lowercase()
        return categoryWords.entries.firstOrNull { (_, words) -> words.any { m.contains(it) } }?.key ?: "other"
    }

    private fun String.toAmount() = replace(",", "").toDoubleOrNull()?.takeIf { it > 0 && it < 10_000_000 }

    fun amountOf(text: String): Double? {
        labeledAmount.find(text)?.groupValues?.get(1)?.toAmount()?.let { return it }
        for (m in currencyAmount.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            if (otherAmount.containsMatchIn(before)) continue
            val v = (m.groups[1] ?: m.groups[2])?.value?.toAmount() ?: continue
            return v
        }
        return null
    }

    /** 店名後面常接著金額或時間，一起切掉 */
    private fun tidy(name: String): String? =
        name.replace(nextField, "")
            .replace(Regex("""\s*(?:$CUR\s*[\d,]|[\d,]+(?:\.\d+)?\s*元|\d{1,2}:\d{2}).*$""", RegexOption.IGNORE_CASE), "")
            .trim(' ', '　', '：', ':', '-', '·', '•', '。')
            .takeIf { it.isNotEmpty() && !dateLike.containsMatchIn(it) }

    fun merchantOf(text: String): String? {
        // 有標籤的最可靠；「給 X」「於 X 付款」可能抓到「給您」「於您的帳戶」，要再檢查
        for (m in labeledMerchant.findAll(text)) tidy(m.groupValues[1])?.let { return it }
        for (re in listOf(tradeMerchant, toMerchant, atMerchant, bracketMerchant)) {
            for (m in re.findAll(text)) tidy(m.groupValues[1])?.takeIf { !it.startsWith("您") && !it.startsWith("你") }?.let { return it }
        }
        return nearAmount(text)
    }

    /** 沒有標籤的格式（例如「付款完成／NT$65／全家便利商店」）：金額那一行或前後一行剩下的字 */
    private fun nearAmount(text: String): String? {
        val lines = text.split('\n', '｜', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val i = lines.indexOfFirst { currencyAmount.containsMatchIn(it) }
        if (i < 0) return null
        return listOfNotNull(lines[i], lines.getOrNull(i + 1), lines.getOrNull(i - 1))
            .map { it.replace(fields, "").replace(noise, " ").replace(Regex("""\s+"""), " ").trim(' ', '：', ':', '-', '·', '•') }
            .firstOrNull(::looksLikeStore)
    }

    private fun looksLikeStore(s: String) = s.length in 2..30 && s.any(Char::isLetter) && !notAStore.containsMatchIn(s)

    fun parse(text: String, hintSource: String? = null): Parsed? = parse(text, text, hintSource, null)

    /**
     * [all]：標題加內文，用來判斷付款方式和類別；[body]：只有內文，用來找金額和店名（標題常常是銀行名稱，不是店名）。
     * [storeTitle]：錢包 App 的通知標題就是店名（例如 Google 錢包）。
     */
    private fun parse(all: String, body: String, hintSource: String?, storeTitle: String?): Parsed? {
        val amount = amountOf(body) ?: amountOf(all) ?: return null
        val source = sources.firstOrNull { it.second.containsMatchIn(all) }?.first ?: hintSource ?: "other"
        val merchant = merchantOf(body) ?: storeTitle?.trim()?.takeIf(::looksLikeStore)
        return Parsed(
            amount = amount,
            merchant = merchant ?: unnamed[source] ?: "未知商家",
            source = source,
            // 先看店名；店名看不出來再看整則通知（例如「Uber Eats 訂單」）
            category = merchant?.let(::categorize)?.takeIf { it != "other" } ?: categorize(all),
            knownMerchant = merchant != null,
        )
    }

    /** 判斷一則系統通知是不是付款；不是的話說明原因（「最近的付款通知」會顯示） */
    fun parseNotification(n: Notice): Result {
        val title = n.title?.trim().orEmpty()
        val body = n.lines.map { it.trim() }.filter { it.isNotEmpty() && it != title }.distinct().joinToString("\n")
        val all = listOf(title, body).filter { it.isNotEmpty() }.joinToString("\n")
        if (n.packageName == LINE_APP) {
            // 標題是聊天對象；有些版本標題只寫「LINE」，內文開頭才是帳號名稱
            val who = if (title.isEmpty() || title.equals("LINE", ignoreCase = true)) body.lineSequence().firstOrNull().orEmpty() else title
            if (!lineOfficial.containsMatchIn(who)) return Result.Skipped("LINE 的聊天訊息")
        }
        notSpending.find(all)?.let { return Result.Skipped("有「${it.value}」，不是一筆花費") }
        val wallet = walletApps[n.packageName]
        if (wallet == null && !paymentWords.containsMatchIn(all)) return Result.Skipped("不是付款通知")
        // 銀行、LINE錢包的官方帳號也會發「刷卡滿 3,000 享回饋」這種廣告；寫了「付款完成」之類的就一定是真的付款
        if (wallet == null && !doneWords.containsMatchIn(all)) promo.find(all)?.let { return Result.Skipped("有「${it.value}」，像是廣告") }
        val p = parse(all, body.ifEmpty { all }, wallet ?: if (n.packageName == LINE_APP) "linepay" else null, title.takeIf { wallet != null })
            ?: return Result.Skipped("找不到金額")
        return Result.Payment(p)
    }

    /** 值得留在「最近的付款通知」的通知：付款相關的 App、LINE 的付款或銀行帳號，或提到付款又有金額（一般聊天不留） */
    fun looksPaymentRelated(n: Notice): Boolean {
        val title = n.title.orEmpty()
        val all = (listOf(title) + n.lines).joinToString("\n")
        return when (n.packageName) {
            LINE_APP -> lineOfficial.containsMatchIn(title.ifEmpty { n.lines.firstOrNull().orEmpty() }) ||
                (title.equals("LINE", ignoreCase = true) && lineOfficial.containsMatchIn(n.lines.firstOrNull().orEmpty()))
            in walletApps -> true
            else -> paymentWords.containsMatchIn(all) && currencyAmount.containsMatchIn(all)
        }
    }

    /** 從系統通知判斷是不是付款通知（只有標題和內文時） */
    fun parsePaymentNotification(title: String?, text: String?): Parsed? =
        (parseNotification(Notice("", title, listOfNotNull(text))) as? Result.Payment)?.parsed
}
