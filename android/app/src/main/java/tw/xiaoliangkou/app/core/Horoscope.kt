package tw.xiaoliangkou.app.core

import java.time.LocalDate
import java.time.MonthDay

private typealias Line = (Horoscope.Sign, Horoscope.Sign) -> String

/**
 * 星座運勢：依 (星座, 日期) 決定的每日運勢與兩人速配，純屬娛樂。
 * - 日期劃分用台灣常見的版本（與科技紫微網相同）
 * - 同一天同星座結果固定，換一天就會變
 */
object Horoscope {
    enum class Element(val label: String, val emoji: String) {
        FIRE("火象", "🔥"), EARTH("土象", "🌱"), AIR("風象", "🌬️"), WATER("水象", "💧"),
    }

    data class Sign(
        val id: Int,
        val name: String,
        val emoji: String,
        val element: Element,
        val start: MonthDay,
        val end: MonthDay,
        /** 個性，例：熱情直率 */
        val trait: String,
        /** 在感情裡帶給對方的，例：行動力 */
        val gift: String,
    ) {
        /** 例：3/21–4/19 */
        val range: String get() = "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
    }

    data class Aspect(val label: String, val emoji: String, val stars: Int, val text: String)
    data class LuckyColor(val name: String, val argb: Long)

    data class Daily(
        val sign: Sign,
        val date: LocalDate,
        val overall: Aspect,
        val love: Aspect,
        val work: Aspect,
        val money: Aspect,
        val health: Aspect,
        val luckyColor: LuckyColor,
        val luckyNumber: Int,
        /** 今天的一句話 */
        val advice: String,
    ) {
        val aspects: List<Aspect> get() = listOf(overall, love, work, money, health)
    }

    data class Match(val a: Sign, val b: Sign, val score: Int, val label: String, val text: String)

    val signs = listOf(
        Sign(0, "牡羊座", "♈️", Element.FIRE, MonthDay.of(3, 21), MonthDay.of(4, 19), "熱情直率", "行動力"),
        Sign(1, "金牛座", "♉️", Element.EARTH, MonthDay.of(4, 20), MonthDay.of(5, 20), "溫和踏實", "安全感"),
        Sign(2, "雙子座", "♊️", Element.AIR, MonthDay.of(5, 21), MonthDay.of(6, 21), "聰明幽默", "新鮮感"),
        Sign(3, "巨蟹座", "♋️", Element.WATER, MonthDay.of(6, 22), MonthDay.of(7, 22), "體貼顧家", "溫暖"),
        Sign(4, "獅子座", "♌️", Element.FIRE, MonthDay.of(7, 23), MonthDay.of(8, 22), "大方自信", "熱情"),
        Sign(5, "處女座", "♍️", Element.EARTH, MonthDay.of(8, 23), MonthDay.of(9, 22), "細心認真", "貼心照顧"),
        Sign(6, "天秤座", "♎️", Element.AIR, MonthDay.of(9, 23), MonthDay.of(10, 23), "優雅隨和", "浪漫氛圍"),
        Sign(7, "天蠍座", "♏️", Element.WATER, MonthDay.of(10, 24), MonthDay.of(11, 22), "神秘專一", "深情"),
        Sign(8, "射手座", "♐️", Element.FIRE, MonthDay.of(11, 23), MonthDay.of(12, 21), "樂觀自由", "驚喜"),
        Sign(9, "摩羯座", "♑️", Element.EARTH, MonthDay.of(12, 22), MonthDay.of(1, 19), "成熟可靠", "穩定感"),
        Sign(10, "水瓶座", "♒️", Element.AIR, MonthDay.of(1, 20), MonthDay.of(2, 18), "獨立有個性", "創意"),
        Sign(11, "雙魚座", "♓️", Element.WATER, MonthDay.of(2, 19), MonthDay.of(3, 20), "浪漫感性", "溫柔"),
    )

    fun signOf(date: LocalDate): Sign {
        val md = MonthDay.from(date)
        return signs.first { s -> if (s.start <= s.end) md >= s.start && md <= s.end else md >= s.start || md <= s.end }
    }

    /** 科技紫微網的今日運勢；手機瀏覽器會自動轉到手機版的同一個星座 */
    fun moreUrl(sign: Sign): String = "https://astro.click108.com.tw/daily_${sign.id}.php?iAstro=${sign.id}"

    // ---------- 每日運勢 ----------

    fun daily(sign: Sign, date: LocalDate): Daily {
        val day = date.toEpochDay()
        val rng = Rng(day * 16 + sign.id)
        val love = stars(rng)
        val work = stars(rng)
        val money = stars(rng)
        val health = stars(rng)
        // 綜合：四項平均，四捨五入
        val overall = ((love + work + money + health) * 10 / 4 + 5) / 10
        fun aspect(i: Int, label: String, emoji: String, stars: Int, lines: Lines) =
            Aspect(label, emoji, stars, lines.pick(stars, sign.id * 8L + i, day))
        return Daily(
            sign = sign,
            date = date,
            overall = aspect(0, "綜合", "🌟", overall, overallLines),
            love = aspect(1, "愛情", "💗", love, loveLines),
            work = aspect(2, "工作", "💼", work, workLines),
            money = aspect(3, "財運", "💰", money, moneyLines),
            health = aspect(4, "健康", "🍀", health, healthLines),
            luckyColor = colors[rng.int(colors.size)],
            luckyNumber = 1 + rng.int(9),
            advice = rotate(advices, 1000L + sign.id, day),
        )
    }

    /** 1–5 星，多半落在 3–4 星，偶爾很棒或需要休息一下 */
    private val starWeights = intArrayOf(1, 3, 6, 6, 4)

    private fun stars(rng: Rng): Int {
        var r = rng.int(starWeights.sum())
        starWeights.forEachIndexed { i, w -> if (r < w) return i + 1 else r -= w }
        return 3
    }

    /** 每 n 天把句庫洗牌一次、依序輪流，所以連續兩天不會抽到同一句 */
    private fun rotate(pool: List<String>, seed: Long, day: Long): String {
        val n = pool.size.toLong()
        val block = Math.floorDiv(day, n)
        val order = shuffled(pool.size, seed, block)
        if (order[0] == shuffled(pool.size, seed, block - 1).last()) order[0] = order[1].also { order[1] = order[0] }
        return pool[order[Math.floorMod(day, n).toInt()]]
    }

    private fun shuffled(n: Int, seed: Long, block: Long): IntArray {
        val rng = Rng(seed * 1_000_003 + block)
        val a = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val j = rng.int(i + 1)
            a[i] = a[j].also { a[j] = a[i] }
        }
        return a
    }

    /** 句庫：好（4–5 星）、普通（3 星）、放慢一點（1–2 星），都要溫暖不嚇人 */
    internal class Lines(val good: List<String>, val okay: List<String>, val soft: List<String>) {
        val all get() = good + okay + soft
        private fun tier(stars: Int) = if (stars >= 4) 0 else if (stars == 3) 1 else 2
        fun forStars(stars: Int) = listOf(good, okay, soft)[tier(stars)]
        fun pick(stars: Int, seed: Long, day: Long): String = rotate(forStars(stars), seed * 3 + tier(stars), day)
    }

    internal val overallLines = Lines(
        good = listOf(
            "今天整體運勢很順，想做的事就大膽去做，好消息可能就在轉角。",
            "心情明亮、做事順手，是開始新計畫的好日子。",
            "身邊的人會主動幫你一把，記得說聲謝謝，好運會一直循環。",
            "今天的你特別有魅力，笑一笑就能讓氣氛變好。",
            "直覺很準的一天，遇到選擇時就相信自己的第一個念頭。",
            "小小的幸運接連出現，像是剛好趕上公車、剛好有位子坐。",
            "狀態滿分！把握好能量，把拖了很久的事一口氣完成吧。",
            "適合出門走走，換個環境會帶來意想不到的靈感和好心情。",
        ),
        okay = listOf(
            "平平穩穩的一天，照著自己的節奏走就很好。",
            "適合整理桌面、行程或心情，清爽之後運氣也會跟著來。",
            "不用急著表現，把手邊的小事做好就是最大的收穫。",
            "普通的日子也藏著小確幸，留意一下身邊的美好吧。",
            "有點忙但還算順，記得在中間留一點時間喘口氣。",
            "找朋友聊聊天能幫你充電，說不定還會聽到好建議。",
        ),
        soft = listOf(
            "今天步調可以放慢一點，事情不用一次做完，休息也是前進。",
            "可能會有點小卡關，深呼吸，換個方法試試看就會通了。",
            "能量稍微低一些，早點睡、吃頓好的，明天又是滿血的你。",
            "計畫趕不上變化也沒關係，保持彈性，反而會遇到更好的安排。",
            "適合待在舒服的地方充電，給自己一點溫柔的獨處時間。",
            "小事別放心上，今天多對自己好一點，就是最好的運氣。",
        ),
    )

    internal val loveLines = Lines(
        good = listOf(
            "戀愛運滿點！今天的一個擁抱，會讓對方甜上一整天 💗",
            "兩個人默契超好，不用說太多，一個眼神就懂。",
            "適合安排小約會，就算只是一起散步買飲料也很浪漫。",
            "主動說一句「好想你」，會收到超乎期待的回應。",
            "今天的你閃閃發亮，對方會忍不住一直看著你。",
            "很適合聊聊未來的計畫，兩個人會越聊越有共識。",
            "準備個小驚喜吧，一張紙條或一顆糖，都能讓對方心花怒放。",
            "甜度爆表的一天，記得拍張合照留下今天的笑容 📸",
        ),
        okay = listOf(
            "感情穩穩的，平凡的日常陪伴就是最好的浪漫。",
            "一起吃頓飯、聊聊今天發生的事，心就會更靠近。",
            "多聽對方說話，比急著給建議更能讓他感到被愛。",
            "適合一起做家事或煮飯，合作中會有小小的甜蜜。",
            "傳一張可愛的貼圖，讓忙碌的他知道你在想他。",
            "給彼此一點自己的時間，見面時會更珍惜。",
        ),
        soft = listOf(
            "今天容易為了小事鬧彆扭，先抱一下再說，氣就消一半了。",
            "對方可能有點累，多一點體貼和耐心，他都會記在心裡。",
            "有話好好說，語氣放軟一點，溝通會順利很多。",
            "如果有點小誤會，主動問一句「你還好嗎？」就能化解。",
            "別把工作的壓力帶回兩個人之間，今晚就好好放鬆在一起吧。",
            "想念的時候就說出來，不要讓對方猜，坦白最可愛。",
        ),
    )

    internal val workLines = Lines(
        good = listOf(
            "工作效率超高，今天交出的成果會被看見。",
            "適合提出新想法，你的點子很有機會被採用。",
            "跟同事合作順利，團隊默契讓事情事半功倍。",
            "思路清楚的一天，難題可以趁今天一口氣解決。",
            "主管或客戶對你印象很好，適合爭取想要的機會。",
            "學習力很強，今天看的書、上的課特別容易吸收。",
            "貴人運不錯，遇到問題就大方請教，會得到很實用的幫忙。",
            "把握今天的好狀態，把重要的工作排在早上先完成。",
        ),
        okay = listOf(
            "按部就班的一天，把待辦清單一項一項劃掉會很有成就感。",
            "細節多留意一點，檢查兩次能省下之後很多麻煩。",
            "適合整理資料、規劃進度，為接下來的衝刺做準備。",
            "開會時多聽聽別人的想法，會有新的啟發。",
            "工作量剛剛好，記得準時下班，留點時間給自己和另一半。",
            "穩穩做好份內的事，累積的努力都會慢慢被看見。",
        ),
        soft = listOf(
            "事情可能比預期多一點，排好優先順序，一件一件來就好。",
            "溝通時多確認一次，可以避免小誤會。",
            "今天不急著做大決定，多收集一點資訊再出手。",
            "卡關時先去倒杯水、走一走，回來常常就有新思路。",
            "覺得疲憊就適時休息一下，效率反而會更好。",
            "別對自己太嚴格，今天能完成基本進度就已經很棒了。",
        ),
    )

    internal val moneyLines = Lines(
        good = listOf(
            "財運亮眼，可能有小小的意外之財或優惠找上門 💰",
            "適合檢視一下理財計畫，今天的判斷力特別好。",
            "購物運不錯，等很久的東西可能剛好在打折。",
            "工作上的努力有機會換成實質回報，好好期待吧。",
            "偏財運小旺，團購或抽獎可以試試手氣。",
            "今天花錢花得很聰明，買到的東西都很實用。",
            "適合和另一半一起訂個存錢目標，會越存越有動力。",
            "收支都很順，記得犒賞一下努力的自己。",
        ),
        okay = listOf(
            "財運平穩，照著預算花就不會有壓力。",
            "記帳的好日子，看看錢都花去哪裡，會有新發現。",
            "小錢慢慢存，積少成多的感覺很踏實。",
            "想買的東西先放購物車一天，明天還想要再買。",
            "適合整理發票和優惠券，說不定有忘了用的好康。",
            "和另一半一起分擔日常開銷，算清楚反而更甜蜜。",
        ),
        soft = listOf(
            "今天容易被衝動購物誘惑，結帳前先想三秒鐘。",
            "先不要借錢給別人或做高風險投資，穩穩守住就是贏。",
            "外送和飲料的小錢加起來也不少，今天試著自己帶水吧。",
            "收到帳單記得核對一下，避免多付冤枉錢。",
            "讓錢包休息一天，用不花錢的方式約會也很浪漫。",
            "財運稍微保守，把錢花在真正需要的地方就好。",
        ),
    )

    internal val healthLines = Lines(
        good = listOf(
            "精神飽滿、元氣十足，很適合去運動流流汗。",
            "身體狀況很好，今天吃什麼都特別香。",
            "適合和另一半一起去散步或騎腳踏車，健康又甜蜜。",
            "睡眠品質不錯，醒來會覺得神清氣爽。",
            "心情愉快，整個人氣色超好。",
            "很適合開始一個小小的運動習慣，例如每天拉筋十分鐘。",
            "活力滿滿，連爬樓梯都覺得輕鬆。",
            "身心都很平衡，做點喜歡的事會更放鬆。",
        ),
        okay = listOf(
            "記得多喝水，身體會謝謝你。",
            "坐久了就起來伸個懶腰，肩頸會舒服很多。",
            "三餐盡量定時，別用零食代替正餐。",
            "適合早點上床，給自己一個完整的好覺。",
            "眼睛用久了就看看遠方，讓它休息一下。",
            "今天適合吃清淡一點，多吃蔬菜水果。",
        ),
        soft = listOf(
            "有點累的話別硬撐，小睡二十分鐘就能回血。",
            "天氣變化注意保暖，出門帶件薄外套。",
            "今天少喝點冰的，溫溫的飲料對身體比較好。",
            "壓力大的時候深呼吸幾次，讓心情慢慢放鬆。",
            "別熬夜追劇了，今晚早點睡，明天會更有精神。",
            "身體在提醒你放慢腳步，泡個熱水澡好好休息吧。",
        ),
    )

    internal val advices = listOf(
        "對喜歡的人好，是最不會後悔的事。",
        "今天也要好好吃飯，好好被愛。",
        "慢慢來，比較快。",
        "把「謝謝」和「我愛你」說出口，一天就會很美好。",
        "你笑起來的樣子，就是今天最好的幸運符。",
        "小小的進步，也值得被慶祝。",
        "累了就靠一下，身邊的人很樂意當你的肩膀。",
        "今天的煩惱，留給明天的自己就好。",
        "一起吃的飯，總是特別好吃。",
        "牽著手走的路，再遠也不怕。",
        "溫柔是一種很強大的力量。",
        "先照顧好自己，才有力氣照顧喜歡的人。",
        "不完美也沒關係，有人剛好喜歡這樣的你。",
        "今天就做一件讓自己開心的小事吧。",
        "好好說話，是愛的開始。",
        "偶爾任性一下，也是一種浪漫。",
        "把手機放下，好好看看眼前的人。",
        "幸福不用很大，剛剛好就很好。",
        "相信自己，你比想像中更厲害。",
        "記得抬頭看看天空，今天也很美。",
        "有你在的地方，就是最好的地方。",
        "每個平凡的日子，都值得好好收藏。",
        "願意等待的人，會遇到最好的時機。",
        "今天多給一個擁抱，明天就多一個笑容。",
        "不用跟別人比較，你有自己的步調。",
        "想見的人就去見，想說的話就說吧。",
        "把今天過好，就是對未來最好的準備。",
        "生活需要一點甜，去買杯喜歡的飲料吧。",
        "吵架不過夜，愛要天天說。",
        "你值得所有美好的事情。",
    )

    val colors = listOf(
        LuckyColor("櫻花粉", 0xFFF7B6C8),
        LuckyColor("蜜桃粉", 0xFFFFB7A1),
        LuckyColor("玫瑰紅", 0xFFE0577A),
        LuckyColor("酒紅色", 0xFF8C2F45),
        LuckyColor("藕粉色", 0xFFE2B6B8),
        LuckyColor("珊瑚橘", 0xFFFF8A65),
        LuckyColor("杏桃色", 0xFFFBCEB1),
        LuckyColor("奶茶色", 0xFFD8B79A),
        LuckyColor("焦糖棕", 0xFFB07A4F),
        LuckyColor("檸檬黃", 0xFFFFE066),
        LuckyColor("暖陽黃", 0xFFFFC94D),
        LuckyColor("琥珀金", 0xFFE0A526),
        LuckyColor("抹茶綠", 0xFF9BB66E),
        LuckyColor("薄荷綠", 0xFF98E2C6),
        LuckyColor("湖水綠", 0xFF4DB6AC),
        LuckyColor("天空藍", 0xFF87CEEB),
        LuckyColor("霧灰藍", 0xFF8FA6BF),
        LuckyColor("寶石藍", 0xFF2F5DA8),
        LuckyColor("海軍藍", 0xFF27406B),
        LuckyColor("薰衣草紫", 0xFFB9A3E3),
        LuckyColor("莓果紫", 0xFF8E4585),
        LuckyColor("象牙白", 0xFFFFF8E7),
        LuckyColor("銀灰色", 0xFFB8BCC4),
        LuckyColor("經典黑", 0xFF2B2B2B),
    )

    // ---------- 速配 ----------

    /** 依兩個星座在黃道上相隔幾宮（0–6）：同星座、相鄰、六分（火風／土水）、四分、三分（同元素）、十二分之五、對宮 */
    private val baseScore = intArrayOf(84, 70, 88, 64, 95, 72, 85)

    /** 兩人的速配指數（60–98 的固定值）與一句話，順序不影響結果 */
    fun match(a: Sign, b: Sign): Match {
        val (score, text) = matches.getValue(minOf(a.id, b.id) to maxOf(a.id, b.id))
        return Match(a, b, score, label(score), text)
    }

    /** 今天的速配：在固定值上下浮動 3 分 */
    fun matchOfDay(a: Sign, b: Sign, date: LocalDate): Match {
        val base = match(a, b)
        val pair = minOf(a.id, b.id) * 12 + maxOf(a.id, b.id)
        val score = (base.score + Rng(date.toEpochDay() * 256 + pair + 0x5A5A).int(7) - 3).coerceIn(60, 98)
        return base.copy(score = score, label = label(score))
    }

    private fun label(score: Int) = when {
        score >= 93 -> "天生一對"
        score >= 86 -> "超級速配"
        score >= 80 -> "默契十足"
        score >= 72 -> "越處越甜"
        score >= 66 -> "互補成長"
        else -> "慢慢磨合"
    }

    /** 78 組（含同星座）的分數與一句話：依元素組合挑句型，同一類的組合輪流用不同句型 */
    private val matches: Map<Pair<Int, Int>, Pair<Int, String>> by lazy {
        val used = mutableMapOf<String, Int>()
        buildMap {
            for (x in signs) for (y in signs.drop(x.id)) {
                val gap = minOf(y.id - x.id, 12 - (y.id - x.id))
                val score = (baseScore[gap] + (x.id * 7 + y.id * 11 + x.id * y.id) % 7 - 3).coerceIn(60, 98)
                val (p, q) = if (gap in 1..5 && (x.element to y.element) !in byElements) y to x else x to y
                val (key, lines) = when (gap) {
                    0 -> "same" to sameSign
                    6 -> "opposite" to opposite
                    else -> "${p.element}${q.element}" to byElements.getValue(p.element to q.element)
                }
                val rank = used.merge(key, 1, Int::plus)!! - 1
                put(x.id to y.id, score to lines[rank % lines.size](p, q))
            }
        }
    }

    private val sameSign = listOf<Line>(
        { a, _ -> "兩個${a.name}在一起，像照鏡子一樣懂彼此；${a.trait}加倍，也記得欣賞對方的小不同。" },
        { a, _ -> "同星座默契滿分，${a.gift}直接乘以二！偶爾太像會互不相讓，輪流讓一下就好。" },
        { a, _ -> "都是${a.name}，最懂對方要什麼，把自己的${a.gift}好好用在彼此身上吧。" },
        { a, _ -> "${a.name}遇上${a.name}，一樣${a.trait}，連喜歡的約會都差不多，一拍即合！" },
    )

    private val opposite = listOf<Line>(
        { a, b -> "${a.name}和${b.name}是對宮星座，像磁鐵兩端互相吸引，彼此擁有的剛好是對方缺少的 🧲" },
        { a, b -> "對宮的兩個人，${a.trait}的${a.name}和${b.trait}的${b.name}剛好互補，懂得欣賞差異就是最佳拍檔。" },
        { a, b -> "${a.name}的${a.gift}遇上${b.name}的${b.gift}，看似相反卻深深吸引，是很有火花的組合。" },
    )

    /** key 的元素順序就是句子裡兩個星座的順序 */
    private val byElements: Map<Pair<Element, Element>, List<Line>> = mapOf(
        (Element.FIRE to Element.FIRE) to listOf<Line>(
            { a, b -> "${a.trait}的${a.name}遇上${b.trait}的${b.name}，兩團火湊在一起，熱戀感可以一直延續下去 🔥" },
            { a, b -> "${a.name}和${b.name}都是行動派，說走就走的約會最適合你們；吵架時就比賽誰先道歉吧！" },
            { a, b -> "${a.name}的${a.gift}加上${b.name}的${b.gift}，你們是彼此最強的啦啦隊。" },
        ),
        (Element.EARTH to Element.EARTH) to listOf<Line>(
            { a, b -> "${a.trait}的${a.name}和${b.trait}的${b.name}，一步一步把日子過得踏實又安心。" },
            { a, b -> "${a.name}和${b.name}都重視實際的生活，一起存錢、一起規劃未來，是最穩的組合。" },
            { a, b -> "不用轟轟烈烈，${a.name}的${a.gift}和${b.name}的${b.gift}，就是最長久的浪漫。" },
        ),
        (Element.AIR to Element.AIR) to listOf<Line>(
            { a, b -> "${a.name}和${b.name}一聊就停不下來，是能一起天馬行空的靈魂伴侶 💬" },
            { a, b -> "${a.trait}的${a.name}遇上${b.trait}的${b.name}，給足彼此空間，又有說不完的話題。" },
            { a, b -> "${a.name}的${a.gift}加上${b.name}的${b.gift}，一起學新東西、一起去旅行，越相處越有趣。" },
        ),
        (Element.WATER to Element.WATER) to listOf<Line>(
            { a, b -> "${a.name}和${b.name}都很重感情，一個眼神就懂對方在想什麼 💧" },
            { a, b -> "${a.trait}的${a.name}和${b.trait}的${b.name}互相包容，是彼此最安心的避風港。" },
            { a, b -> "${a.name}的${a.gift}遇上${b.name}的${b.gift}，記得常把愛說出口，甜度還會再翻倍。" },
        ),
        (Element.FIRE to Element.AIR) to listOf<Line>(
            { f, a -> "風讓火燒得更旺，${f.name}的${f.gift}加上${a.name}的${a.gift}，每天都過得很精彩 ✨" },
            { f, a -> "${f.trait}的${f.name}和${a.trait}的${a.name}很合拍，聊天、出遊都充滿火花。" },
            { f, a -> "${a.name}出點子、${f.name}馬上行動，是朋友眼中最會玩的一對。" },
        ),
        (Element.EARTH to Element.WATER) to listOf<Line>(
            { e, w -> "水滋潤大地，${e.name}的${e.gift}和${w.name}的${w.gift}，讓彼此慢慢長成更好的人 🌱" },
            { e, w -> "${e.trait}的${e.name}遇上${w.trait}的${w.name}，細水長流，是越久越甜的組合。" },
            { e, w -> "${e.name}負責踏實、${w.name}負責溫柔，兩個人在一起特別有安全感。" },
        ),
        (Element.FIRE to Element.EARTH) to listOf<Line>(
            { f, e -> "${f.name}想衝、${e.name}想穩，${f.trait}遇上${e.trait}，配合好就是最佳平衡。" },
            { f, e -> "${f.name}的${f.gift}加上${e.name}的${e.gift}，多聽聽對方的節奏，會是很互補的一對。" },
            { f, e -> "${f.name}帶來熱情、${e.name}帶來踏實，偶爾要磨合，但一起完成目標時特別有成就感。" },
        ),
        (Element.FIRE to Element.WATER) to listOf<Line>(
            { f, w -> "火和水很不一樣，${f.trait}的${f.name}和${w.trait}的${w.name}多一點耐心，就能熱情又溫柔。" },
            { f, w -> "${f.name}比較直接、${w.name}比較細膩，多說一句「我是在乎你」，誤會就會少很多。" },
            { f, w -> "${f.name}的${f.gift}和${w.name}的${w.gift}都很珍貴，用對方需要的方式去愛，就會很甜。" },
        ),
        (Element.AIR to Element.EARTH) to listOf<Line>(
            { a, e -> "${a.trait}的${a.name}愛想像、${e.trait}的${e.name}重實際，剛好互補。" },
            { a, e -> "${a.name}的${a.gift}加上${e.name}的${e.gift}，放在一起很能把夢想變成真。" },
            { a, e -> "${a.name}想得遠、${e.name}走得穩，多一點好奇和耐心，會發現彼此其實很可愛。" },
        ),
        (Element.AIR to Element.WATER) to listOf<Line>(
            { a, w -> "${a.trait}的${a.name}重想法、${w.trait}的${w.name}重感覺，多分享心情就會越來越懂彼此。" },
            { a, w -> "理性的${a.name}遇上感性的${w.name}，互相學習，是會一起成長的組合。" },
            { a, w -> "${a.name}給${a.gift}、${w.name}給${w.gift}，記得常常抱抱、說說心裡話 🤍" },
        ),
    )

    /** SplitMix64：不依賴標準函式庫的實作，版本更新也不會讓同一天的結果改變 */
    private class Rng(seed: Long) {
        private var x = seed

        fun next(): Long {
            x += 0x9E3779B97F4A7C15uL.toLong()
            var z = x
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        fun int(n: Int): Int = ((next() ushr 1) % n).toInt()
    }
}
