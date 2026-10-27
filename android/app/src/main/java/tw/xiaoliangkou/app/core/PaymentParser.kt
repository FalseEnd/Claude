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

    /** 一則系統通知：哪個 App（[appLabel] 是 App 的名字）、標題，以及內文（展開後的全文、每一行、聊天訊息） */
    data class Notice(val packageName: String, val title: String?, val lines: List<String>, val appLabel: String? = null)

    sealed interface Result {
        data class Payment(val parsed: Parsed) : Result
        data class Skipped(val reason: String) : Result
    }

    const val LINE_APP = "jp.naver.line.android"

    /** 專門的錢包 App：通知本身就是交易，不必再出現「付款」這種字（例如 Google 錢包：標題是店名、內文是金額） */
    private val walletApps = mapOf(
        "com.google.android.apps.walletnfcrel" to "applepay",
        "com.samsung.android.spay" to "applepay",
        "com.samsung.android.samsungpay.gear" to "applepay",
        "com.jkos.app" to "jkopay",
    )

    /**
     * 名字看起來是錢包、行動支付的 App（套件名稱各家常改，也不一定查得到）：悠遊付、全支付、Google 錢包、Samsung Wallet…
     * 這些 App 的通知本身就是交易。LINE、銀行 App 不算（它們也會發廣告、聊天）
     */
    private val walletLabel = Regex("""錢包|wallet|悠遊付|全支付|街口|icash\s*pay|拍錢包|歐付寶|全盈|台灣\s*pay|臺灣\s*pay|samsung\s*pay|google\s*pay""", RegexOption.IGNORE_CASE)

    /** 這則通知的 App 是錢包嗎：是的話回傳付款方式（找不到名字對應的就算行動支付） */
    private fun walletOf(n: Notice): String? {
        walletApps[n.packageName]?.let { return it }
        val label = n.appLabel?.takeIf { n.packageName != LINE_APP && walletLabel.containsMatchIn(it) } ?: return null
        return sources.firstOrNull { it.second.containsMatchIn(label) }?.first ?: "applepay"
    }

    /**
     * 商家關鍵字（小寫比對）。同一個名字對到好幾個時用最長的那個：「uber eats」是餐飲、「uber」是交通，
     * 「apple music」是娛樂、「apple」是購物。悠遊卡、一卡通也是付款方式，不拿來判斷類別
     */
    private val categoryWords = linkedMapOf(
        "drink" to listOf(
            "星巴克", "starbucks", "路易莎", "louisa", "cama", "85度c", "85°c", "伯朗", "丹堤", "怡客", "西雅圖", "金鑛", "黑沃",
            "咖啡", "coffee", "cafe", "café", "costa", "%arabica", "blue bottle", "藍瓶",
            "50嵐", "五十嵐", "coco都可", "coco 都可", "清心福全", "清心", "可不可", "迷客夏", "milksha", "茶湯會", "一沐日", "得正",
            "麻古", "鮮茶道", "大苑子", "春水堂", "珍煮丹", "龜記", "先喝道", "老虎堂", "天仁茗茶", "歇腳亭", "日出茶太", "chatime",
            "貢茶", "茶聚", "康青龍", "再睡5分鐘", "萬波", "鶴茶樓", "圓石", "樺達", "吃茶三千", "布萊恩紅茶", "烏弄", "水巷茶弄",
            "上宇林", "茶的魔手", "comebuy", "老賴茶棧", "大茗", "八曜和茶", "teatop", "台茶", "cupetit", "yifang", "一芳",
            "珍珠奶茶", "奶茶", "紅茶", "綠茶", "果汁", "冰沙", "飲料", "手搖", "茶飲", "茶坊", "茶舖", "茶屋", "茶棧", "茶行",
        ),
        "food" to listOf(
            "麥當勞", "mcdonald", "摩斯", "mos burger", "肯德基", "kfc", "漢堡王", "burger king", "必勝客", "pizza hut",
            "達美樂", "domino", "拿坡里", "21世紀", "頂呱呱", "丹丹漢堡", "胖老爹", "拉亞", "麥味登", "美而美", "弘爺", "q burger",
            "晨間廚房", "早安美芝城", "呷尚寶", "爭鮮", "壽司郎", "sushiro", "藏壽司", "kura", "合點壽司", "鼎泰豐", "王品", "瓦城",
            "西堤", "陶板屋", "夏慕尼", "原燒", "聚北海道", "石二鍋", "12mini", "築間", "老四川", "海底撈", "千葉火鍋", "這一鍋",
            "馬辣", "乾杯", "胡同", "八方雲集", "四海遊龍", "三商巧福", "鬍鬚張", "大戶屋", "吉野家", "松屋", "すき家", "sukiya",
            "一蘭", "一風堂", "屯京", "鼎王", "繼光香香雞", "起家雞", "貴族世家", "我家牛排", "孫東寶", "饗食天堂", "饗饗", "漢來",
            "欣葉", "添好運", "點點心", "勝博殿", "銀座杏子", "薩莉亞", "saizeriya", "subway", "賽百味", "開丼", "牛角",
            "斑鳩的窩", "好丘", "鬥牛士", "悟饕", "池上便當", "金仙蝦捲", "阿宗麵線", "微熱山丘", "聖瑪莉", "mister donut",
            "krispy", "哈根達斯", "häagen", "cold stone", "31冰淇淋", "baskin", "uber eats", "ubereats", "foodpanda", "熊貓外送",
            "外送", "餐廳", "餐飲", "便當", "拉麵", "火鍋", "小吃", "食堂", "料理", "廚房", "早餐", "早午餐", "燒肉", "牛排", "丼",
            "披薩", "pizza", "burger", "漢堡", "甜點", "蛋糕", "麵包", "烘焙", "吐司", "甜甜圈", "冰淇淋", "雪花冰", "豆花", "剉冰",
            "炸雞", "雞排", "鹹酥雞", "滷味", "臭豆腐", "牛肉麵", "麵線", "水餃", "鍋貼", "湯包", "小籠包", "滷肉飯", "雞肉飯",
            "壽司", "居酒屋", "鐵板燒", "熱炒", "快炒", "自助餐", "buffet", "restaurant", "bistro", "kitchen", "bakery",
        ),
        "transport" to listOf(
            "高鐵", "thsr", "台鐵", "臺鐵", "捷運", "mrt", "metro", "北捷", "高捷", "中捷", "桃捷", "機捷", "uber", "計程車",
            "taxi", "大車隊", "55688", "yoxi", "linego", "line taxi", "中油", "cpc", "台塑石油", "全國加油", "統一精工", "福懋",
            "加油", "停車", "嘟嘟房", "俥亭", "台灣聯通", "城市車旅", "parking", "youbike", "irent", "goshare", "wemo", "客運",
            "國光", "統聯", "和欣", "葛瑪蘭", "阿羅哈", "航空", "機票", "airline", "airways", "長榮航空", "eva air", "中華航空",
            "華航", "china airlines", "星宇", "starlux", "虎航", "tigerair", "立榮", "etag", "遠通電收", "高速公路", "租車",
            "和運", "格上", "渡輪", "船票", "公車", "巴士",
        ),
        "daily" to listOf(
            "7-eleven", "7-11", "7eleven", "統一超商", "全家", "familymart", "family mart", "萊爾富", "hi-life", "hilife", "ok超商",
            "ok mart", "okmart", "美廉社", "全聯", "pxmart", "家樂福", "carrefour", "大潤發", "愛買", "好市多", "costco", "頂好",
            "wellcome", "楓康", "美聯社", "屈臣氏", "watsons", "康是美", "cosmed", "寶雅", "poya", "大創", "daiso", "小北百貨",
            "光南", "大樹藥局", "杏一", "丁丁藥局", "啄木鳥", "日藥本舖", "松本清", "matsukiyo", "唐吉訶德", "don don donki",
            "藥局", "藥妝", "超市", "超商", "便利商店", "量販", "生鮮", "市場", "中華電信", "台灣大哥大", "遠傳", "台灣之星",
            "亞太電信", "電信", "台電", "台灣電力", "自來水", "瓦斯", "天然氣", "水費", "電費", "診所", "醫院", "牙醫", "眼科",
            "醫療", "掛號", "動物醫院", "寵物", "洗衣", "理髮", "美髮", "剪髮", "快剪", "管理費",
        ),
        "shopping" to listOf(
            "uniqlo", "zara", "h&m", "lativ", "net服飾", "nike", "adidas", "new balance", "puma", "decathlon", "迪卡儂", "momo",
            "蝦皮", "shopee", "pchome", "yahoo購物", "yahoo奇摩購物", "露天", "博客來", "誠品", "金石堂", "無印良品", "muji",
            "ikea", "宜得利", "nitori", "特力屋", "hola", "新光三越", "sogo", "遠百", "遠東百貨", "微風", "京站", "統一時代", "漢神",
            "環球購物", "outlet", "三井", "百貨", "燦坤", "全國電子", "順發", "3c", "apple", "小米", "寶島眼鏡", "小林眼鏡",
            "眼鏡", "服飾", "鞋", "購物", "商城", "網購", "淘寶", "taobao", "amazon", "樂天市場", "東森購物", "生活工場",
            "家電", "書店", "文具", "玩具", "反斗城", "toys", "studio a", "神腦",
        ),
        "fun" to listOf(
            "威秀", "vieshow", "秀泰", "showtime", "國賓", "美麗華", "影城", "電影", "cinema", "netflix", "spotify", "disney",
            "kkbox", "youtube", "apple music", "icloud", "chatgpt", "openai", "steam", "playstation", "psn", "nintendo", "xbox",
            "app store", "google play", "遊戲", "ktv", "好樂迪", "錢櫃", "星聚點", "樂園", "六福村", "義大世界", "義大遊樂", "劍湖山", "麗寶樂園",
            "門票", "展覽", "博物館", "美術館", "動物園", "水族館", "健身", "運動中心", "world gym", "健身工廠", "按摩", "芳療",
            "溫泉", "飯店", "旅館", "民宿", "hotel", "klook", "kkday", "agoda", "booking.com", "airbnb", "旅行社", "演唱會",
            "kktix", "拓元", "tixcraft", "年代售票", "寬宏", "ibon售票",
        ),
        "gift" to listOf(
            "花店", "花藝", "花坊", "花束", "禮品", "禮物", "禮盒", "喜餅", "精品", "珠寶", "金飾", "周大福", "周生生", "pandora",
            "swarovski", "tiffany", "卡片",
        ),
    )

    /** 名字裡有這些字、清單上又找不到時的猜測（只看店名，不看整則通知） */
    private val nameHints = listOf(
        "drink" to Regex("茶$|飲$|咖啡|茶飲|奶茶|果汁"),
        "food" to Regex("麵|飯|鍋|餐|食|雞|粥|餃|堡|壽司|燒|丼|饅頭|包子|豆漿|早點|小館|廚|烤|炸|鴨|牛肉|排骨|羹|丸|湯|滷|串|炒|麻辣"),
        "transport" to Regex("加油站|停車場|車站|客運"),
        "daily" to Regex("藥|超市|商店|雜貨|五金|洗衣|診所|醫院"),
        "shopping" to Regex("百貨|服飾|鞋|眼鏡|書局|書店|電器|3C|購物", RegexOption.IGNORE_CASE),
        "gift" to Regex("花|禮"),
    )

    private val sources = listOf(
        // LINE Pay 的通知現在是「LINE錢包」官方帳號發的；中信 LINE Pay 卡也算
        "linepay" to Regex("""line\s*pay|line\s*錢包""", RegexOption.IGNORE_CASE),
        "jkopay" to Regex("街口"),
        "applepay" to Regex("""apple\s*pay|google\s*pay|google\s*錢包|google\s*wallet|samsung\s*pay""", RegexOption.IGNORE_CASE),
        "easywallet" to Regex("悠遊付"),
        "pxpay" to Regex("""全支付|px\s*pay""", RegexOption.IGNORE_CASE),
        "pluspay" to Regex("""全盈\s*\+?\s*pay""", RegexOption.IGNORE_CASE),
        "icashpay" to Regex("""icash\s*pay""", RegexOption.IGNORE_CASE),
        "taiwanpay" to Regex("""台灣\s*pay|臺灣\s*pay|taiwan\s*pay""", RegexOption.IGNORE_CASE),
        "pipay" to Regex("""pi\s*拍錢包|拍錢包"""),
        "opay" to Regex("歐付寶"),
        "card" to Regex("信用卡|簽帳|卡片|刷卡|末四碼|末4碼|卡號"),
    )

    /** 找不到店名時用的名字 */
    private val unnamed = mapOf(
        "linepay" to "LINE Pay 消費", "jkopay" to "街口支付消費", "applepay" to "行動支付消費",
        "easywallet" to "悠遊付消費", "pxpay" to "全支付消費", "pluspay" to "全盈+PAY 消費", "icashpay" to "icash Pay 消費",
        "taiwanpay" to "台灣Pay 消費", "pipay" to "Pi 拍錢包消費", "opay" to "歐付寶消費", "card" to "信用卡消費",
    )

    /** 找不到店名時記的名字（不能拿來學「這家店是什麼類別」） */
    fun isPlaceholder(name: String) = name in unnamed.values || name == "未知商家"

    /** 通知必須包含這些字才當作付款，避免把聊天訊息裡的金額記成帳 */
    private val paymentWords = Regex("付款|消費|交易|扣款|刷卡|支付|結帳|購買|支出|付訖")
    /** 有這些字就不是一筆花費（退款、收款、儲值、驗證碼、帳單…） */
    private val notSpending = Regex("退款|退貨|取消|失敗|未成功|未完成|收款|入帳|儲值|加值|提領|驗證碼|OTP|動態密碼|繳款截止|本期帳單", RegexOption.IGNORE_CASE)
    /** 真的付款了才會出現的字；銀行、商店的官方帳號也常發「刷卡滿 3,000 享回饋」這種廣告 */
    private val doneWords = Regex("付款完成|付款成功|已付款|已支付|支付成功|支付完成|交易成功|交易完成|消費成功|扣款成功|已扣款|刷卡成功|授權成功|消費通知|交易通知|刷卡通知|付款通知|扣款通知|支出通知|消費提醒|交易提醒|刷卡提醒|付款提醒|您已於|您於|已於")
    private val promo = Regex("""滿\s*$CUR?\s*[\d,]+\s*元?\s*(?:送|折|享|回饋|現折)|優惠|活動|抽獎|登錄|好禮|加碼|限時|領取|折價券|優惠券""", RegexOption.IGNORE_CASE)
    /** LINE 裡只看付款、銀行的官方帳號，朋友和群組的聊天不算 */
    private val lineOfficial = Regex(
        """line\s*錢包|line\s*pay|line\s*bank|銀行|信用卡|中信|中國信託|國泰|玉山|台新|富邦|永豐|聯邦|第一銀|一銀|華南|兆豐|合庫|合作金庫|""" +
            """彰銀|土銀|臺銀|台銀|上海商銀|遠東商銀|元大|星展|滙豐|匯豐|渣打|新光|凱基|王道|將來|樂天|街口|一卡通|悠遊""",
        RegexOption.IGNORE_CASE,
    )

    private const val NUM = """([\d,]+(?:\.\d+)?)"""
    private const val CUR = """(?:NT\$|NTD|NT(?=\s*\d)|TWD|新台幣|新臺幣|台幣|臺幣|\$)"""
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
    // 店名的第一個字不能是於、在、向（「於 10/27 在 全家 消費」拿掉日期後是「於 在 全家 消費」）
    private val atMerchant = Regex("""(?:於|在|向)\s*([^\s\n，,。；;｜|【】「」於在向]$NAME{0,29}?)\s*(?:完成)?(?:付款|消費|支付|刷卡|購買|交易|結帳)""")
    /** 「您已於「八方雲集」完成付款」：引號裡的店名 */
    private val quotedMerchant = Regex("""[「『]([^」』\n]{2,30})[」』]""")
    /** 「消費新台幣120元 國內 統一超商」：金額後面接著的店名（前面可能有國內、國外） */
    private val afterAmount = Regex("""(?:$CUR\s*[\d,]+(?:\.\d+)?\s*元?|[\d,]+(?:\.\d+)?\s*元)\s*[，,]?\s*(?:於|在)?\s*(?:國內|國外|海外)?\s*($NAME{2,30})""", RegexOption.IGNORE_CASE)
    /** 找店名前先拿掉日期、時間（「於10/27 12:34在 全家 消費」） */
    private val dateTime = Regex("""\d{4}[/\-.]\d{1,2}[/\-.]\d{1,2}|\d{1,2}/\d{1,2}(?:/\d{2,4})?|\d{1,2}:\d{2}(?::\d{2})?|上午|下午|\(GMT[+\-]\d{4}\)""", RegexOption.IGNORE_CASE)
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

    /** 看商家（或整則通知）的關鍵字判斷類別：對到好幾個時用最長的那個；都沒有是 other */
    fun categorize(text: String): String {
        val m = text.lowercase()
        var best: String? = null
        var bestLen = 0
        for ((cat, words) in categoryWords) for (w in words) if (w.length > bestLen && m.contains(w)) { best = cat; bestLen = w.length }
        return best ?: "other"
    }

    /** 店名的類別：先看關鍵字，沒有的話看名字裡的字（「老王牛肉麵」→ 餐飲） */
    fun categorizeMerchant(name: String): String =
        categorize(name).takeIf { it != "other" } ?: nameHints.firstOrNull { it.second.containsMatchIn(name) }?.first ?: "other"

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

    fun merchantOf(text: String): String? = merchantRaw(text)?.let(::cleanMerchant)?.takeIf { it.length >= 2 }

    private fun merchantRaw(text: String): String? {
        // 有標籤的最可靠；「給 X」「於 X 付款」可能抓到「給您」「於您的帳戶」，要再檢查
        for (m in labeledMerchant.findAll(text)) tidy(m.groupValues[1])?.let { return it }
        val t = text.replace(dateTime, " ")
        for (re in listOf(tradeMerchant, quotedMerchant, toMerchant, atMerchant, bracketMerchant)) {
            for (m in re.findAll(t)) tidy(m.groupValues[1])?.takeIf(::plausible)?.let { return it }
        }
        for (m in afterAmount.findAll(t)) {
            m.groupValues[1].replace(noise, " ").replace(Regex("""\s+"""), " ").let(::tidy)?.takeIf { plausible(it) && looksLikeStore(it) }?.let { return it }
        }
        return nearAmount(t)
    }

    /** 抓到的不是店名：「您」「你的帳戶」、卡號、銀行名稱這種 */
    private fun plausible(s: String) = !s.startsWith("您") && !s.startsWith("你") && !Regex("""^(?:卡號|末四碼|末4碼|帳戶|帳號|以|使用)""").containsMatchIn(s)

    /**
     * 整理店名：拿掉公司字尾（股份有限公司）、門市代號（#1234）、金流前綴（PAYPAL *、LINEPAY*），
     * 刷卡通知常見的英文代號換成大家習慣的名字（PXMART → 全聯、FAMILYMART → 全家）；中文的店名（含分店）照原樣
     */
    fun cleanMerchant(raw: String): String {
        var n = raw.trim()
        n = n.replace(Regex("""^(?:paypal|linepay|line pay|jkopay|google|apple pay|sq|sp|tst)\s*\*\s*""", RegexOption.IGNORE_CASE), "")
        n = n.replace(Regex("""\s*\*\s*"""), " ")
        n = n.replace(Regex("""\s*(?:股份有限公司|有限公司|企業社|台灣分公司|臺灣分公司|分公司)"""), " ")
        n = n.replace(Regex("""\s*#\s*\d+|\s+\d{4,}$"""), "")
        n = n.replace(Regex("""\s+"""), " ").trim(' ', '-', '_', '.', '·')
        // 只換刷卡帳單那種全大寫的代號；錢包 App 已經寫好的「Starbucks」照原樣
        if (n.none(Char::isLowerCase)) {
            val lower = n.lowercase()
            for ((codes, name) in aliases) {
                val code = codes.firstOrNull { lower.startsWith(it) } ?: continue
                // 後面接著中文的分店名就留著（「7-ELEVEN 民生門市」），英文的地名、代號就不要了
                val rest = n.substring(code.length).trim(' ', '-', '_', '.')
                return if (rest.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "$name $rest" else name
            }
        }
        return n
    }

    /** 刷卡通知常見的英文代號 → 中文名字 */
    private val aliases = listOf(
        listOf("pxmart", "px mart") to "全聯",
        listOf("familymart", "family mart") to "全家",
        listOf("7-eleven", "7eleven", "president chain") to "7-ELEVEN",
        listOf("hi-life", "hilife") to "萊爾富",
        listOf("mcdonald") to "麥當勞",
        listOf("starbucks") to "星巴克",
        listOf("uber eats", "ubereats", "uber *eats") to "Uber Eats",
        listOf("foodpanda") to "foodpanda",
        listOf("carrefour") to "家樂福",
        listOf("costco") to "好市多",
        listOf("watsons") to "屈臣氏",
        listOf("cosmed") to "康是美",
        listOf("taipei metro", "taipei rapid") to "台北捷運",
        listOf("thsr", "taiwan high speed") to "台灣高鐵",
        listOf("netflix") to "Netflix",
        listOf("spotify") to "Spotify",
    )

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
        val merchant = merchantOf(body) ?: storeTitle?.trim()?.takeIf(::looksLikeStore)?.let(::cleanMerchant)
        return Parsed(
            amount = amount,
            merchant = merchant ?: unnamed[source] ?: "未知商家",
            source = source,
            // 先看店名；店名看不出來再看整則通知（例如「Uber Eats 訂單」）
            category = merchant?.let(::categorize)?.takeIf { it != "other" } ?: categorize(all).takeIf { it != "other" }
                ?: merchant?.let(::categorizeMerchant) ?: "other",
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
        val wallet = walletOf(n)
        if (wallet == null && !paymentWords.containsMatchIn(all)) return Result.Skipped("不是付款通知")
        // 銀行、LINE錢包的官方帳號也會發「刷卡滿 3,000 享回饋」這種廣告；寫了「付款完成」之類的就一定是真的付款
        if (wallet == null && !doneWords.containsMatchIn(all)) promo.find(all)?.let { return Result.Skipped("有「${it.value}」，像是廣告") }
        // 錢包 App 的標題常常就是店名（Google 錢包）；標題是錢包自己的名字（「悠遊付」「付款成功」）就不算
        val storeTitle = title.takeIf { wallet != null && !walletLabel.containsMatchIn(it) && !paymentWords.containsMatchIn(it) && it != n.appLabel }
        val p = parse(all, body.ifEmpty { all }, wallet ?: if (n.packageName == LINE_APP) "linepay" else null, storeTitle)
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
            else -> walletOf(n) != null || (paymentWords.containsMatchIn(all) && currencyAmount.containsMatchIn(all))
        }
    }

    /** 從系統通知判斷是不是付款通知（只有標題和內文時） */
    fun parsePaymentNotification(title: String?, text: String?): Parsed? =
        (parseNotification(Notice("", title, listOfNotNull(text))) as? Result.Payment)?.parsed
}
