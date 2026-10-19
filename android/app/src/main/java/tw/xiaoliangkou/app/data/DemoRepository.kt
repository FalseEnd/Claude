package tw.xiaoliangkou.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.core.Guess
import tw.xiaoliangkou.app.core.PaymentParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 示範模式：不需要 Supabase，資料存在記憶體裡。
 * 「小雨」會在台北市區照固定行程移動、耗電、在咖啡店消費，也會回應你的安撫與邀約。
 */
class DemoRepository(private val scope: CoroutineScope) : CoupleRepository {
    companion object {
        const val ME = "demo-me"
        const val PARTNER = "demo-partner"
        val MY_LOCATION = LatLng(25.0330, 121.5430) // 大安區

        private data class Stop(val name: String, val lat: Double, val lng: Double, val dwell: Int = 0, val wifi: String? = null, val charge: Boolean = false, val spend: String? = null, val metro: Boolean = false)

        // 小雨的一天（循環）：家 → 便利商店 → 捷運 → 公司 → 咖啡店 → 公司 → 健身房 → 家
        private val ROUTE = listOf(
            Stop("住家", 25.0500, 121.5770, dwell = 24, wifi = "家裡", charge = true),
            Stop("", 25.0492, 121.5764),
            Stop("便利商店", 25.0485, 121.5758, dwell = 6, spend = "mart"),
            Stop("捷運站", 25.0447, 121.5715, dwell = 4),
            Stop("捷運站", 25.0408, 121.5655, dwell = 3, metro = true),
            Stop("公司", 25.0360, 121.5670, dwell = 30, wifi = "公司"),
            Stop("咖啡店", 25.0346, 121.5648, dwell = 10, wifi = "咖啡店", spend = "cafe"),
            Stop("公司", 25.0360, 121.5670, dwell = 20, wifi = "公司"),
            Stop("健身房", 25.0440, 121.5600, dwell = 14),
            Stop("", 25.0475, 121.5690),
        )
        private const val OFFICE = 5
    }

    private val _state = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = _state
    private val _banners = MutableSharedFlow<Banner>(extraBufferCapacity = 16)
    override val banners: SharedFlow<Banner> = _banners

    /** 對方開放猜的開銷，真正的金額先藏在這裡（跟資料庫一樣，猜之前拿不到） */
    private val hiddenAmounts = mutableMapOf<String, Double>()
    /** 上傳的照片、語音（path → 檔案內容），示範照片第一次下載時才畫 */
    private val files = ConcurrentHashMap<String, ByteArray>()
    private var nextId = 1000L
    private fun id() = nextId++
    private fun now() = Instant.now()
    private fun iso(i: Instant = now()) = i.toString()
    private val today get() = LocalDate.now()

    // 模擬狀態
    private var routeIndex = OFFICE
    private var dwellLeft = 30.0
    private var moving = false
    private var lat = ROUTE[OFFICE].lat
    private var lng = ROUTE[OFFICE].lng
    private var battery = 34.0
    private var steps = 4286.0
    private var lowSent = false
    private var inside = setOf<String>()
    var speed = 1
    var running = true
    private var loop: Job? = null

    init {
        _state.value = seed()
        inside = Geo.step(fences(), emptySet(), lat, lng).inside
        loop = scope.launch {
            while (isActive) {
                delay(1000)
                if (running) tick(speed.toDouble())
            }
        }
    }

    override suspend fun refreshAll() = Unit
    override fun close() { loop?.cancel() }

    // ---------------------------------------------------------------- 種子資料
    private fun ago(minutes: Long): Instant {
        val t = now().minus(minutes, ChronoUnit.MINUTES)
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        // 凌晨打開時，「今天稍早」的資料壓縮到今天 0 點之後
        return if (t.isAfter(startOfDay)) t else startOfDay.plusSeconds(((now().epochSecond - startOfDay.epochSecond) * (1 - minutes / 240.0).coerceAtLeast(0.05)).toLong())
    }
    private fun at(daysAgo: Long, h: Int, m: Int): Instant =
        LocalDateTime.of(today.minusDays(daysAgo), java.time.LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant()

    private fun activity(kind: String, actor: String?, time: Instant, vararg pairs: Pair<String, Any?>) = Activity(
        id = id(), actorId = actor, kind = kind, createdAt = iso(time),
        payload = JsonObject(pairs.associate { (k, v) ->
            k to when (v) {
                null -> kotlinx.serialization.json.JsonNull
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }),
    )

    private fun seed(): AppState {
        val since = today.minusDays(513)
        val myBirthday = LocalDate.of(1998, today.plusDays(160).monthValue, today.plusDays(160).dayOfMonth.coerceAtMost(28))
        val herBirthday = LocalDate.of(1999, today.plusDays(37).monthValue, today.plusDays(37).dayOfMonth.coerceAtMost(28))
        fun ex(who: String, t: Instant, merchant: String, amount: Double, source: String, category: String? = null,
               visibility: String = "shared", revealOn: String? = null, guessable: Boolean = false,
               theirGuess: Double? = null, theirPoints: Int? = null, myGuess: Double? = null, myPoints: Int? = null) = Expense(
            id = UUID.randomUUID().toString(), userId = who, amount = if (who == PARTNER && guessable && myGuess == null) null else amount,
            merchant = merchant, category = category ?: PaymentParser.categorize(merchant), source = source, occurredAt = iso(t),
            visibility = visibility, revealOn = revealOn, guessable = guessable, theirGuess = theirGuess, theirPoints = theirPoints,
            myGuess = myGuess, myPoints = myPoints,
        )
        hiddenAmounts.clear()
        val pendingId = UUID.randomUUID().toString()
        hiddenAmounts[pendingId] = 165.0
        val partnerExpenses = listOf(
            Expense(pendingId, PARTNER, null, "星巴克 信義新天地", "drink", "card", iso(ago(15)), guessable = true),
            ex(PARTNER, ago(170), "全家便利商店 松仁店", 89.0, "linepay"),
            ex(PARTNER, at(1, 12, 30), "鼎泰豐 101 店", 680.0, "card"),
            ex(PARTNER, at(1, 19, 5), "UNIQLO 微風南山店", 1280.0, "card"),
            ex(PARTNER, at(2, 8, 40), "路易莎咖啡", 95.0, "linepay", guessable = true, myGuess = 90.0, myPoints = 80),
            ex(PARTNER, at(2, 12, 15), "池上便當", 110.0, "jkopay"),
            ex(PARTNER, at(3, 18, 20), "台灣高鐵", 1490.0, "applepay"),
            ex(PARTNER, at(4, 8, 30), "星巴克 信義新天地", 150.0, "einvoice"),
            ex(PARTNER, at(4, 21, 0), "Netflix", 390.0, "card"),
            ex(PARTNER, at(6, 13, 0), "全聯福利中心", 432.0, "linepay"),
            ex(PARTNER, at(8, 20, 10), "威秀影城", 640.0, "card", guessable = true, myGuess = 500.0, myPoints = 50),
            ex(PARTNER, at(9, 8, 20), "路易莎咖啡", 85.0, "linepay"),
            ex(PARTNER, at(12, 12, 40), "麥當勞", 189.0, "jkopay"),
            ex(PARTNER, at(15, 15, 30), "屈臣氏", 356.0, "card"),
            ex(PARTNER, at(18, 19, 0), "蝦皮購物", 799.0, "einvoice"),
        )
        val myExpenses = listOf(
            ex(ME, ago(200), "麥當勞", 159.0, "jkopay"),
            ex(ME, at(1, 9, 0), "cama café", 75.0, "linepay"),
            ex(ME, at(1, 19, 30), "好樂迪 KTV", 1200.0, "card", guessable = true, theirGuess = 1000.0, theirPoints = 50),
            ex(ME, at(3, 10, 0), "中油 加油站", 1050.0, "card"),
            ex(ME, at(5, 18, 0), "誠品書店", 520.0, "card", category = "gift", visibility = "surprise", revealOn = today.plusDays(37).toString()),
            ex(ME, at(7, 12, 0), "一蘭拉麵", 280.0, "applepay"),
        )
        val moods = mutableListOf(Mood(id(), PARTNER, "tired", "開了一整天的會", iso(ago(80))))
        listOf("happy", "sweet", "calm", "tired", "happy", "missyou").forEachIndexed { i, m -> moods += Mood(id(), PARTNER, m, null, iso(at(i + 1L, 21, 10))) }
        listOf("calm", "happy", "tired", "happy", "sweet", "calm").forEachIndexed { i, m -> moods += Mood(id(), ME, m, null, iso(at(i + 1L, 22, 0))) }
        val saturday = today.plusDays(((6 - today.dayOfWeek.value % 7 + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        fun eventAt(date: LocalDate, h: Int, m: Int) = iso(LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant())
        val periods = listOf(136 to 132, 108 to 103, 79 to 74, 51 to 47, 23 to 18).map { (s, e) ->
            PeriodDto(UUID.randomUUID().toString(), PARTNER, today.minusDays(s.toLong()).toString(), today.minusDays(e.toLong()).toString())
        }
        val places = listOf(
            Place("p-home", name = "小雨家", icon = "home", lat = 25.0500, lng = 121.5770, radiusM = 150, watchUser = PARTNER, createdBy = PARTNER),
            Place("p-office", name = "小雨公司", icon = "work", lat = 25.0360, lng = 121.5670, radiusM = 250, watchUser = PARTNER, createdBy = ME),
            Place("p-gym", name = "健身房", icon = "gym", lat = 25.0440, lng = 121.5600, radiusM = 120, notifyLeave = false, watchUser = PARTNER, createdBy = PARTNER),
            Place("p-mywork", name = "我公司", icon = "work", lat = MY_LOCATION.lat, lng = MY_LOCATION.lng, radiusM = 200, watchUser = ME, createdBy = ME),
        )
        val history = listOf(
            activity("guess", PARTNER, at(1, 22, 0), "merchant" to "好樂迪 KTV", "guess" to 1000, "actual" to 1200, "points" to 50),
            activity("comfort", PARTNER, at(1, 22, 40), "kind" to "here", "message" to "收到抱抱了，晚安"),
            activity("comfort", ME, at(1, 22, 35), "kind" to "hug", "message" to null),
            activity("battery_low", PARTNER, at(1, 17, 50), "level" to 18),
            activity("event_confirmed", PARTNER, at(1, 12, 5), "title" to "週六晚餐"),
            activity("guess", ME, at(2, 9, 0), "merchant" to "路易莎咖啡", "guess" to 90, "actual" to 95, "points" to 80),
            activity("place_leave", PARTNER, ago(150), "place" to "小雨家"),
            activity("place_arrive", PARTNER, ago(95), "place" to "小雨公司"),
            activity("mood", PARTNER, ago(80), "mood" to "tired", "note" to "開了一整天的會"),
        ).sortedByDescending { it.time }

        return AppState(
            loaded = true, demo = true, connected = true, memosLoaded = true, dbSchema = RemoteRepository.DEVICE_SCHEMA,
            myDevice = DeviceStatus(
                ME, 82, true, "wifi", "約 120 Mbps", false, 3_120, iso(), "samsung SM-S921B", iso(),
                deviceName = "阿哲的 Galaxy S24", carrier = "台灣大哥大",
            ),
            me = Profile(ME, "阿哲", email = "azhe@example.com", birthday = myBirthday.toString(), info = ProfileInfo.build(mapOf(
                ProfileInfo.HEIGHT to "176", ProfileInfo.WEIGHT to "68", ProfileInfo.PHONE to "0987-654-321", ProfileInfo.BLOOD_TYPE to "A",
                ProfileInfo.MBTI to "ISTJ", ProfileInfo.JOB to "軟體工程師", ProfileInfo.HOBBIES to "打籃球、手沖咖啡、露營",
                ProfileInfo.LIKES to "豚骨拉麵、黑咖啡、小雨的笑容", ProfileInfo.DISLIKES to "排隊、下雨天塞車", ProfileInfo.CLOTHES_SIZE to "L",
                ProfileInfo.SHOE_SIZE to "27", ProfileInfo.INTRO to "會煮咖啡的工程師 ☕ 假日最喜歡跟小雨去爬山",
            ))),
            partner = Profile(PARTNER, "小雨", avatarUrl = Avatars.emoji("🐰"), birthday = herBirthday.toString(), info = ProfileInfo.build(mapOf(
                ProfileInfo.HEIGHT to "160", ProfileInfo.WEIGHT to "48", ProfileInfo.PHONE to "0912-345-678", ProfileInfo.BLOOD_TYPE to "O",
                ProfileInfo.MBTI to "ENFP", ProfileInfo.JOB to "UI 設計師", ProfileInfo.HOBBIES to "追劇、拍照、瑜珈",
                ProfileInfo.LIKES to "珍珠奶茶（半糖少冰）、草莓蛋糕、貓咪", ProfileInfo.DISLIKES to "香菜、蟑螂", ProfileInfo.ALLERGIES to "芒果",
                ProfileInfo.CLOTHES_SIZE to "S", ProfileInfo.SHOE_SIZE to "23.5", ProfileInfo.INTRO to "愛笑的小雨，喜歡把日常拍成照片 📷",
            ))),
            couple = Couple("demo-couple", since.toString()),
            mySharing = Sharing(ME, shareLocation = true, shareDevice = true, shareExpenses = true, shareMood = true, shareCycle = false),
            partnerSharing = Sharing(PARTNER, shareLocation = true, shareDevice = true, shareExpenses = true, shareMood = true, shareCycle = true),
            partnerOnline = true,
            partnerStatus = PartnerStatus(
                DeviceStatus(PARTNER, 34, false, "wifi", "公司", true, 4286, iso(ago(3)), "Google Pixel 8", iso(), deviceName = "小雨的手機", carrier = "中華電信"),
                LocationDto(PARTNER, lat, lng, 15.0, 0.0, "still", iso(), iso()),
            ),
            myLocation = MY_LOCATION,
            places = places,
            myExpenses = myExpenses,
            partnerExpenses = partnerExpenses,
            moods = moods,
            comforts = listOf(
                Comfort(id(), senderId = ME, receiverId = PARTNER, kind = "hug", createdAt = iso(at(1, 22, 35))),
                Comfort(id(), senderId = PARTNER, receiverId = ME, kind = "here", message = "收到抱抱了，晚安", createdAt = iso(at(1, 22, 40))),
            ),
            anniversaries = listOf(
                Anniversary("a1", title = "在一起紀念日", date = since.toString(), kind = "together"),
                Anniversary("a2", title = "小雨生日", date = herBirthday.toString(), kind = "birthday"),
                Anniversary("a3", title = "阿哲生日", date = myBirthday.toString(), kind = "birthday"),
                Anniversary("a4", title = "第一次約會", date = since.minusDays(24).toString(), kind = "first_date", remindDays = listOf(0, 1)),
            ),
            events = listOf(
                DateEvent("e1", createdBy = ME, title = "週六晚餐", startsAt = eventAt(saturday, 18, 30), locationText = "樂埔町 信義店", note = "已訂位 2 位", status = "confirmed"),
                DateEvent("e2", createdBy = PARTNER, title = "看電影", startsAt = eventAt(today.plusDays(9), 20, 0), locationText = "威秀影城", status = "proposed"),
                DateEvent("e3", createdBy = PARTNER, title = "IKEA 買書櫃", startsAt = eventAt(today.plusDays(13), 14, 0), note = "記得先量尺寸", status = "confirmed", remindMinutes = listOf(1440)),
                DateEvent("e4", createdBy = ME, title = "野餐", startsAt = eventAt(today.minusDays(6), 11, 0), locationText = "大安森林公園", status = "confirmed"),
            ),
            cycle = CycleResponse(
                mine = CycleBundle(),
                partner = CycleBundle(CycleSettingsDto(PARTNER, partnerCanEdit = true, careReminders = true), periods),
            ),
            history = history,
            historyEnd = true,
            notifPrefs = listOf("pairing", "place", "mood", "comfort", "battery_low", "guess", "event", "anniversary", "cycle_care", "sharing_changed", "diary", "memo")
                .associateWith { true },
            notes = listOf(
                Note("n1", PARTNER, "她喜歡半糖少冰 🥤 珍奶要加波霸，不要椰果", iso(at(3, 21, 30))),
                Note("n2", PARTNER, "生日想要的耳環款式：微風南山 2 樓那間的珍珠小耳環（她偷看了三次 👀）", iso(at(12, 22, 10))),
            ),
            dayEntries = listOf(
                DayEntry("d1", ME, today.minusDays(6).toString(), "大安森林公園野餐 🧺 小雨做的三明治超好吃，還遇到好多狗狗！",
                    listOf(DemoMedia.PICNIC), iso(at(6, 17, 40))),
                DayEntry("d2", PARTNER, today.minusDays(6).toString(), "謝謝你陪我野餐 💕 下次換我帶甜點", createdAt = iso(at(6, 21, 5))),
                DayEntry("d3", PARTNER, today.minusDays(1).toString(), "中午吃鼎泰豐 🥟 小籠包好好吃，下次一起來！",
                    listOf(DemoMedia.DINNER, DemoMedia.VOICE), iso(at(1, 13, 10))),
                DayEntry("d4", ME, today.toString(), "下班買了雞蛋糕，等等拿給小雨 🥰", createdAt = iso(ago(40))),
            ).sortedWith(DayEntry.ORDER),
            memos = listOf(
                Memo("m1", ME, "週末採買清單 🛒", "🥚 雞蛋\n🥛 鮮奶\n🍓 草莓\n🧻 衛生紙\n🐱 貓砂", shared = true, pinned = true, color = "yellow",
                    createdAt = iso(at(2, 20, 0)), updatedAt = iso(ago(90))),
                Memo("m2", ME, "小雨生日驚喜 🤫", "1. 訂她最愛的義大利麵餐廳\n2. 珍珠小耳環\n3. 寫一張手寫卡片", shared = false, color = "lavender",
                    createdAt = iso(at(10, 23, 0))),
                Memo("m3", PARTNER, "想一起去的地方 ✈️", "京都賞楓 🍁\n墾丁看海 🌊\n九份吃芋圓", listOf(DemoMedia.TRIP), shared = true, color = "mint",
                    createdAt = iso(at(4, 22, 0)), updatedAt = iso(at(1, 22, 30))),
            ).sortedWith(Memo.ORDER),
        )
    }

    private fun fences() = _state.value.places.filter { it.watchUser != ME }.map { Geo.Fence(it.id, it.lat, it.lng, it.radiusM.toDouble()) }

    private fun log(a: Activity) = _state.update { it.copy(history = listOf(a) + it.history) }
    private suspend fun banner(title: String, body: String?, kind: String) {
        if (_state.value.notifPrefs[kind] == false) return
        _banners.emit(Banner(title, body, kind, RemoteRepository.routeFor(kind)))
    }

    // ---------------------------------------------------------------- 模擬對方移動
    private suspend fun tick(dt: Double) {
        val stop = ROUTE[routeIndex]
        if (!moving) {
            dwellLeft -= dt
            if (stop.charge) battery = (battery + dt / 0.8).coerceAtMost(100.0) else drain(dt)
            if (dwellLeft <= 0) { routeIndex = (routeIndex + 1) % ROUTE.size; moving = true }
        } else {
            val target = ROUTE[routeIndex]
            val metersPerDeg = 111_000.0
            val dLat = (target.lat - lat) * metersPerDeg
            val dLng = (target.lng - lng) * metersPerDeg * 0.906
            val dist = hypot(dLat, dLng)
            val step = (if (target.metro) 90.0 else 30.0) * dt
            if (dist <= step) {
                lat = target.lat; lng = target.lng
                if (!target.metro) steps += dist * 1.33
                if (target.dwell > 0) { moving = false; dwellLeft = target.dwell.toDouble() } else routeIndex = (routeIndex + 1) % ROUTE.size
                target.spend?.let { partnerSpend(it) }
            } else {
                lat += (target.lat - lat) * step / dist
                lng += (target.lng - lng) * step / dist
                if (!target.metro) steps += step * 1.33
            }
            drain(dt)
        }
        if (battery > 30) lowSent = false
        publishStatus()
        val r = Geo.step(fences(), inside, lat, lng)
        inside = r.inside
        if (_state.value.partnerSharing?.locationLive == true) {
            for (t in r.transitions) {
                val place = _state.value.places.firstOrNull { it.id == t.placeId } ?: continue
                log(activity(if (t.arrive) "place_arrive" else "place_leave", PARTNER, now(), "place" to place.name))
                if ((t.arrive && place.notifyArrive) || (!t.arrive && place.notifyLeave)) {
                    banner("小雨 ${if (t.arrive) "已抵達" else "已離開"} ${place.name}", "剛剛", "place")
                }
            }
        }
    }

    private suspend fun drain(dt: Double) {
        val before = battery
        battery = (battery - dt / 8).coerceAtLeast(1.0)
        if (before > 20 && battery <= 20 && !lowSent) batteryLow()
    }

    private suspend fun batteryLow() {
        lowSent = true
        if (_state.value.partnerSharing?.deviceLive != true) return
        val pct = battery.toInt()
        log(activity("battery_low", PARTNER, now(), "level" to pct))
        banner("小雨 的手機電量剩 $pct%", "可能快聯絡不到了，提醒對方充電吧", "battery_low")
    }

    private fun publishStatus() {
        val stop = ROUTE[routeIndex]
        val dwelling = !moving
        _state.update {
            it.copy(
                partnerStatus = PartnerStatus(
                    DeviceStatus(
                        PARTNER, battery.toInt(), dwelling && stop.charge,
                        if (dwelling && stop.wifi != null) "wifi" else "cellular", if (dwelling) stop.wifi else null,
                        dwelling && (routeIndex == OFFICE || stop.name == "健身房"), steps.toInt(),
                        iso(if (moving && stop.metro) now() else now().minusSeconds(180)), "Google Pixel 8", iso(),
                        deviceName = "小雨的手機", carrier = "中華電信",
                    ),
                    LocationDto(PARTNER, lat, lng, 15.0, if (moving) 1.3 else 0.0, if (!moving) "still" else if (stop.metro) "transit" else "walking", iso(), iso()),
                )
            )
        }
    }

    private suspend fun partnerSpend(kind: String) {
        val text = when (kind) {
            "mart" -> "LINE Pay｜付款完成 NT$${listOf(45, 65, 89, 120).random()} 商店：全家便利商店 松仁店"
            "cafe" -> "【國泰世華】您的卡片末四碼5678消費新台幣${listOf(135, 150, 165, 180).random()}元，商店：星巴克 信義新天地"
            else -> listOf(
                "LINE Pay｜付款完成 NT$135 商店：星巴克 信義新天地門市",
                "【國泰世華】您的卡片末四碼1234消費新台幣1,280元，商店：UNIQLO 微風南山店",
                "街口支付：您已付款 65 元給 全家便利商店 松仁店",
                "Apple Pay 交易：台灣高鐵 NT$1,490",
            ).random()
        }
        val p = PaymentParser.parse(text) ?: return
        val pending = _state.value.partnerExpenses.count { it.waitingForGuess }
        val guessable = pending < 2 && kind != "mart"
        val id = UUID.randomUUID().toString()
        if (guessable) hiddenAmounts[id] = p.amount
        val e = Expense(id, PARTNER, if (guessable) null else p.amount, p.merchant, p.category, p.source, iso(), guessable = guessable)
        _state.update { it.copy(partnerExpenses = listOf(e) + it.partnerExpenses) }
        if (_state.value.partnerSharing?.shareExpenses != true) return
        if (guessable) banner("小雨 有一筆開銷等你猜", "${p.merchant} · ${Catalog.category(p.category).label}", "guess")
    }

    // ---------------------------------------------------------------- 示範控制台
    fun demoAction(action: String, placeId: String? = null) {
        scope.launch {
            when (action) {
                "sad" -> partnerMood(listOf("sad", "anxious", "tired").random(), listOf("報告被退回了", "今天好不順", "頭有點痛").random())
                "happy" -> partnerMood(listOf("happy", "sweet", "missyou").random(), listOf("想你了", "今天超順利", null).random())
                "hug" -> {
                    val c = Catalog.comforts.random()
                    _state.update { it.copy(comforts = listOf(Comfort(id(), senderId = PARTNER, receiverId = ME, kind = c.key, createdAt = iso())) + it.comforts) }
                    log(activity("comfort", PARTNER, now(), "kind" to c.key, "message" to null))
                    banner("小雨 ${c.push} ${c.emoji}", "點開回應一下吧", "comfort")
                }
                "battery" -> {
                    if (!moving && ROUTE[routeIndex].charge) { routeIndex = (routeIndex + 1) % ROUTE.size; moving = true }
                    battery = 19.0; lowSent = false; batteryLow(); publishStatus()
                }
                "spend" -> partnerSpend("random")
                "invite" -> {
                    val title = listOf("吃火鍋", "看夜景", "逛夜市", "去貓咖啡").random()
                    val start = LocalDateTime.of(today.plusDays(3), java.time.LocalTime.of(19, 30)).atZone(ZoneId.systemDefault()).toInstant()
                    val ev = DateEvent(UUID.randomUUID().toString(), createdBy = PARTNER, title = title, startsAt = iso(start), status = "proposed")
                    _state.update { it.copy(events = it.events + ev) }
                    log(activity("event_created", PARTNER, now(), "title" to title, "starts_at" to iso(start)))
                    banner("小雨 約你：$title", "到日曆回覆", "event")
                }
                "pause" -> {
                    val s = _state.value.partnerSharing ?: return@launch
                    val until = if (s.paused) null else iso(now().plus(1, ChronoUnit.HOURS))
                    _state.update { it.copy(partnerSharing = s.copy(pausedUntil = until)) }
                    log(activity("sharing_changed", PARTNER, now(), "paused_until" to until))
                    banner(if (until == null) "小雨 恢復分享了" else "小雨 暫停分享 1 小時", if (until == null) "又可以看到位置與手機狀態" else "時間到會自動恢復", "sharing_changed")
                }
                "guess" -> {
                    val e = _state.value.myExpenses.lastOrNull { it.visibility == "shared" && it.theirGuess == null } ?: return@launch
                    partnerGuesses(e.copy(guessable = true), 600)
                }
                "diary" -> {
                    val e = DayEntry(UUID.randomUUID().toString(), PARTNER, today.toString(),
                        listOf("下班看到超美的夕陽 🌇", "午餐吃了好吃的拉麵 🍜", "跟同事去喝了手搖 🧋").random(), listOf(DemoMedia.SUNSET), iso())
                    _state.update { it.copy(dayEntries = (it.dayEntries + e).sortedWith(DayEntry.ORDER)) }
                    banner("小雨 記錄了 ${today.monthValue}/${today.dayOfMonth} 的生活", e.body.take(40), "diary")
                }
                "memo" -> {
                    val m = Memo(UUID.randomUUID().toString(), PARTNER, "晚餐想吃什麼 🍽️", "火鍋、咖哩、義大利麵，你選一個！", color = "peach",
                        createdAt = iso(), updatedAt = iso())
                    _state.update { it.copy(memos = (it.memos + m).sortedWith(Memo.ORDER)) }
                    banner("小雨 新增了共同備忘錄", m.title, "memo")
                }
                "go" -> {
                    val place = _state.value.places.firstOrNull { it.id == placeId } ?: return@launch
                    val idx = ROUTE.indices.filter { ROUTE[it].dwell > 0 }.minByOrNull { Geo.distance(ROUTE[it].lat, ROUTE[it].lng, place.lat, place.lng) } ?: return@launch
                    routeIndex = idx
                    lat = place.lat; lng = place.lng
                    moving = false; dwellLeft = 20.0
                    tick(0.0)
                }
            }
        }
    }

    private suspend fun partnerMood(key: String, note: String?) {
        val m = Catalog.mood(key) ?: return
        _state.update { it.copy(moods = listOf(Mood(id(), PARTNER, key, note, iso())) + it.moods) }
        log(activity("mood", PARTNER, now(), "mood" to key, "note" to note))
        banner("小雨 現在覺得${m.label} ${m.emoji}", if (m.needsComfort) listOfNotNull(note, "要不要安撫一下？").joinToString("。") else note, "mood")
    }

    private fun partnerGuesses(e: Expense, delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val actual = e.amount ?: return@launch
            val g = (actual * Random.nextDouble(0.7, 1.3) / 5).toInt() * 5.0
            val score = Guess.score(g, actual)
            _state.update { s -> s.copy(myExpenses = s.myExpenses.map { if (it.id == e.id) it.copy(guessable = true, theirGuess = g, theirPoints = score.points) else it }) }
            log(activity("guess", PARTNER, now(), "merchant" to e.merchant, "guess" to g, "actual" to actual, "points" to score.points))
            banner("小雨 猜了你的花費", "${e.merchant}：猜 NT$${g.toInt()}，${score.label}", "guess")
        }
    }

    // ---------------------------------------------------------------- 介面實作
    override suspend fun createInvite() = Invite("K7Q2M9")
    override suspend fun acceptInvite(code: String, togetherSince: String?) = Unit
    override suspend fun unpair() {
        throw AppException("示範模式不能解除配對，可以在「我的」離開示範模式")
    }
    override suspend fun updateProfile(displayName: String) = _state.update { it.copy(me = it.me?.copy(displayName = displayName)) }
    override suspend fun updateAvatar(avatar: String?) = _state.update { it.copy(me = it.me?.copy(avatarUrl = avatar)) }
    override suspend fun setTogetherSince(date: String) = _state.update { it.copy(couple = it.couple?.copy(togetherSince = date)) }

    override suspend fun updateSharing(patch: JsonObject) {
        fun b(k: String) = patch[k]?.jsonPrimitive?.booleanOrNull
        _state.update { s ->
            val cur = s.mySharing ?: return@update s
            s.copy(mySharing = cur.copy(
                shareLocation = b("location") ?: cur.shareLocation, shareDevice = b("device") ?: cur.shareDevice,
                shareExpenses = b("expenses") ?: cur.shareExpenses, shareMood = b("mood") ?: cur.shareMood,
                shareCycle = b("cycle") ?: cur.shareCycle,
                hiddenCategories = patch["hiddenCategories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: cur.hiddenCategories,
            ))
        }
        log(activity("sharing_changed", ME, now()))
    }

    override suspend fun pauseSharing(minutes: Int) {
        val until = if (minutes <= 0) null else iso(now().plus(minutes.toLong(), ChronoUnit.MINUTES))
        _state.update { it.copy(mySharing = it.mySharing?.copy(pausedUntil = until)) }
        log(activity("sharing_changed", ME, now(), "paused_until" to until))
    }

    override suspend fun locatePartner(): Boolean { publishStatus(); return true }

    override suspend fun savePlace(id: String?, body: JsonObject) {
        fun s(k: String) = body[k]?.jsonPrimitive?.contentOrNull
        fun d(k: String) = body[k]?.jsonPrimitive?.doubleOrNull
        fun i(k: String) = body[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = body[k]?.jsonPrimitive?.booleanOrNull
        val watch = when (s("watch")) { "me" -> ME; "both" -> null; "partner" -> PARTNER; else -> "keep" }
        _state.update { st ->
            if (id == null) {
                val p = Place(UUID.randomUUID().toString(), name = s("name") ?: "新標點", icon = s("icon") ?: "pin", lat = d("lat") ?: 25.04, lng = d("lng") ?: 121.56,
                    radiusM = i("radiusM") ?: 150, notifyArrive = b("notifyArrive") ?: true, notifyLeave = b("notifyLeave") ?: true,
                    watchUser = if (watch == "keep") PARTNER else watch, createdBy = ME)
                st.copy(places = st.places + p)
            } else st.copy(places = st.places.map { p ->
                if (p.id != id) p else p.copy(
                    name = s("name") ?: p.name, icon = s("icon") ?: p.icon, lat = d("lat") ?: p.lat, lng = d("lng") ?: p.lng,
                    radiusM = i("radiusM") ?: p.radiusM, notifyArrive = b("notifyArrive") ?: p.notifyArrive, notifyLeave = b("notifyLeave") ?: p.notifyLeave,
                    watchUser = if (watch == "keep") p.watchUser else watch,
                )
            })
        }
        inside = Geo.step(fences(), inside, lat, lng).inside
    }

    override suspend fun deletePlace(id: String) = _state.update { it.copy(places = it.places.filterNot { p -> p.id == id }) }

    override suspend fun saveExpense(id: String?, body: JsonObject) {
        fun s(k: String) = body[k]?.jsonPrimitive?.contentOrNull
        val amount = body["amount"]?.jsonPrimitive?.doubleOrNull
        val guessable = body["guessable"]?.jsonPrimitive?.booleanOrNull
        var created: Expense? = null
        _state.update { st ->
            if (id == null) {
                val merchant = s("merchant") ?: "未命名"
                val e = Expense(UUID.randomUUID().toString(), ME, amount ?: 0.0, merchant, s("category") ?: PaymentParser.categorize(merchant),
                    s("source") ?: "manual", iso(), s("visibility") ?: "shared", s("revealOn"), guessable ?: false)
                created = e
                st.copy(myExpenses = listOf(e) + st.myExpenses)
            } else st.copy(myExpenses = st.myExpenses.map { e ->
                if (e.id != id) e else e.copy(
                    amount = amount ?: e.amount, merchant = s("merchant") ?: e.merchant, category = s("category") ?: e.category,
                    visibility = s("visibility") ?: e.visibility, revealOn = if (body.containsKey("revealOn")) s("revealOn") else e.revealOn,
                    guessable = guessable ?: e.guessable,
                ).also { if (it.guessable && it.theirGuess == null && it.visibility == "shared") created = it }
            })
        }
        created?.takeIf { it.guessable && it.visibility == "shared" }?.let { partnerGuesses(it, 4000) }
    }

    override suspend fun deleteExpense(id: String) = _state.update { it.copy(myExpenses = it.myExpenses.filterNot { e -> e.id == id }) }

    override suspend fun guess(id: String, amount: Double): GuessResult {
        val actual = hiddenAmounts[id] ?: _state.value.partnerExpenses.firstOrNull { it.id == id }?.amount ?: throw AppException("這筆開銷不能猜")
        val e = _state.value.partnerExpenses.first { it.id == id }
        if (e.myGuess != null) throw AppException("每筆只能猜一次")
        val score = Guess.score(amount, actual)
        _state.update { s -> s.copy(partnerExpenses = s.partnerExpenses.map { if (it.id == id) it.copy(amount = actual, myGuess = amount, myPoints = score.points) else it }) }
        log(activity("guess", ME, now(), "merchant" to e.merchant, "guess" to amount, "actual" to actual, "points" to score.points))
        return GuessResult(actual, score.points, score.ratio)
    }

    override suspend fun postMood(mood: String, note: String?) {
        _state.update { it.copy(moods = listOf(Mood(id(), ME, mood, note?.ifBlank { null }, iso())) + it.moods) }
        log(activity("mood", ME, now(), "mood" to mood, "note" to note))
        if (Catalog.mood(mood)?.needsComfort == true) scope.launch {
            delay(3500)
            val c = Catalog.comforts.filter { it.key in listOf("hug", "pat", "here") }.random()
            _state.update { it.copy(comforts = listOf(Comfort(id(), senderId = PARTNER, receiverId = ME, kind = c.key, createdAt = iso())) + it.comforts) }
            log(activity("comfort", PARTNER, now(), "kind" to c.key, "message" to "看到你心情不好"))
            banner("小雨 ${c.push} ${c.emoji}", "看到你心情不好", "comfort")
        }
    }

    override suspend fun sendComfort(kind: String, message: String?) {
        _state.update { it.copy(comforts = listOf(Comfort(id(), senderId = ME, receiverId = PARTNER, kind = kind, message = message, createdAt = iso())) + it.comforts) }
        log(activity("comfort", ME, now(), "kind" to kind, "message" to message))
        scope.launch {
            delay(2600)
            val reply = listOf("收到了，好多了", "嘿嘿謝謝你", "晚點見面再抱一次", "有你真好").random()
            log(activity("comfort", PARTNER, now(), "kind" to "here", "message" to reply))
            banner("小雨 回覆了你", reply, "comfort")
            val pm = _state.value.moods.firstOrNull { it.userId == PARTNER }
            if (pm != null && Catalog.mood(pm.mood)?.needsComfort == true) {
                delay(3000)
                partnerMood("calm", "被安撫了")
            }
        }
    }

    override suspend fun saveAnniversary(id: String?, body: JsonObject) {
        val title = body["title"]?.jsonPrimitive?.contentOrNull ?: return
        val a = Anniversary(id ?: UUID.randomUUID().toString(), title = title, date = body["date"]?.jsonPrimitive?.contentOrNull ?: today.toString(),
            kind = body["kind"]?.jsonPrimitive?.contentOrNull ?: "custom", repeat = body["repeat"]?.jsonPrimitive?.contentOrNull ?: "yearly",
            remindDays = (body["remindDays"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: listOf(0, 1, 7))
        _state.update { s -> s.copy(anniversaries = s.anniversaries.filterNot { it.id == a.id } + a) }
    }

    override suspend fun deleteAnniversary(id: String) = _state.update { it.copy(anniversaries = it.anniversaries.filterNot { a -> a.id == id }) }

    override suspend fun saveEvent(id: String?, body: JsonObject) {
        fun s(k: String) = body[k]?.jsonPrimitive?.contentOrNull
        val ev = DateEvent(id ?: UUID.randomUUID().toString(), createdBy = ME, title = s("title") ?: "約會", startsAt = s("startsAt") ?: iso(),
            locationText = s("locationText"), note = s("note"), status = "proposed",
            remindMinutes = (body["remindMinutes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: listOf(60))
        _state.update { st -> st.copy(events = st.events.filterNot { it.id == ev.id } + ev) }
        log(activity("event_created", ME, now(), "title" to ev.title, "starts_at" to ev.startsAt))
        if (id == null) scope.launch {
            delay(3200)
            _state.update { st -> st.copy(events = st.events.map { if (it.id == ev.id) it.copy(status = "confirmed") else it }) }
            log(activity("event_confirmed", PARTNER, now(), "title" to ev.title))
            banner("小雨 答應了：${ev.title}", "到時候見", "event")
        }
    }

    override suspend fun answerEvent(id: String, status: String) {
        val ev = _state.value.events.firstOrNull { it.id == id } ?: return
        _state.update { st -> st.copy(events = st.events.map { if (it.id == id) it.copy(status = status) else it }) }
        log(activity("event_$status", ME, now(), "title" to ev.title))
    }

    override suspend fun deleteEvent(id: String) = _state.update { it.copy(events = it.events.filterNot { e -> e.id == id }) }

    override suspend fun saveCycleSettings(body: JsonObject) {
        fun i(k: String) = body[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = body[k]?.jsonPrimitive?.booleanOrNull
        _state.update { s ->
            val cur = s.cycle.mine.settings ?: CycleSettingsDto(ME)
            val next = cur.copy(
                cycleLength = if (body.containsKey("cycleLength")) i("cycleLength") else cur.cycleLength,
                periodLength = if (body.containsKey("periodLength")) i("periodLength") else cur.periodLength,
                partnerCanEdit = b("partnerCanEdit") ?: cur.partnerCanEdit, careReminders = b("careReminders") ?: cur.careReminders,
            )
            s.copy(cycle = s.cycle.copy(mine = s.cycle.mine.copy(settings = next)))
        }
    }

    override suspend fun stopCycle() = _state.update { it.copy(cycle = it.cycle.copy(mine = CycleBundle())) }

    override suspend fun addPeriod(forPartner: Boolean, start: String, end: String?) {
        _state.update { s ->
            val owner = if (forPartner) PARTNER else ME
            val bundle = if (forPartner) s.cycle.partner else s.cycle.mine
            val periods = bundle.periods.filterNot { it.startDate == start } + PeriodDto(UUID.randomUUID().toString(), owner, start, end, ME)
            val updated = bundle.copy(periods = periods.sortedBy { it.startDate }, settings = bundle.settings ?: if (!forPartner) CycleSettingsDto(ME) else null)
            s.copy(cycle = if (forPartner) s.cycle.copy(partner = updated) else s.cycle.copy(mine = updated))
        }
    }

    override suspend fun updatePeriod(id: String, body: JsonObject) {
        val start = body["startDate"]?.jsonPrimitive?.contentOrNull
        val hasEnd = body.containsKey("endDate")
        val end = body["endDate"]?.jsonPrimitive?.contentOrNull
        fun upd(b: CycleBundle) = b.copy(periods = b.periods.map { if (it.id == id) it.copy(startDate = start ?: it.startDate, endDate = if (hasEnd) end else it.endDate) else it })
        _state.update { s -> s.copy(cycle = CycleResponse(upd(s.cycle.mine), upd(s.cycle.partner))) }
    }

    override suspend fun deletePeriod(id: String) {
        fun del(b: CycleBundle) = b.copy(periods = b.periods.filterNot { it.id == id })
        _state.update { s -> s.copy(cycle = CycleResponse(del(s.cycle.mine), del(s.cycle.partner))) }
    }

    override suspend fun saveCycleLog(date: String, flow: String?, symptoms: List<String>) {
        _state.update { s ->
            val logs = s.cycle.mine.logs.filterNot { it.logDate == date } + CycleLog(ME, date, flow, symptoms)
            s.copy(cycle = s.cycle.copy(mine = s.cycle.mine.copy(logs = logs)))
        }
    }

    override suspend fun loadMoreHistory() = Unit
    override suspend fun setNotifPref(kind: String, enabled: Boolean) = _state.update { it.copy(notifPrefs = it.notifPrefs + (kind to enabled)) }

    // ---------------------------------------------------------------- 個人資料、備註
    private fun notFound() = AppException(Errors.DB_MESSAGES.getValue("not_found"), "not_found")

    override suspend fun updateProfileInfo(displayName: String, birthday: String?, info: JsonObject) {
        val name = displayName.trim()
        Limits.profile(name, birthday, info)
        _state.update { it.copy(me = it.me?.copy(displayName = name, birthday = birthday, info = info)) }
    }

    override suspend fun saveNote(id: String?, body: String) {
        Limits.note(body)
        if (id != null && _state.value.notes.none { it.id == id }) throw notFound()
        _state.update { st ->
            if (id == null) st.copy(notes = listOf(Note(UUID.randomUUID().toString(), PARTNER, body, iso(), iso())) + st.notes)
            else st.copy(notes = st.notes.map { if (it.id == id) it.copy(body = body, updatedAt = iso()) else it })
        }
    }

    override suspend fun deleteNote(id: String) = _state.update { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }

    // ---------------------------------------------------------------- 日曆的生活紀錄（示範資料都在記憶體裡，不用分段載入）
    override suspend fun loadDayEntries(from: LocalDate, to: LocalDate) = Unit

    override suspend fun saveDayEntry(id: String?, day: LocalDate, body: String, media: List<MediaItem>) {
        Limits.dayEntry(body, media)
        val old = id?.let { i -> _state.value.dayEntries.firstOrNull { it.id == i } ?: throw notFound() }
        if (old != null && old.userId != ME) throw AppException("只能改自己寫的紀錄", "forbidden")
        val e = old?.copy(day = day.toString(), body = body, media = media, updatedAt = iso())
            ?: DayEntry(UUID.randomUUID().toString(), ME, day.toString(), body, media, iso(), iso())
        _state.update { st -> st.copy(dayEntries = (st.dayEntries.filterNot { it.id == e.id } + e).sortedWith(DayEntry.ORDER)) }
    }

    override suspend fun deleteDayEntry(id: String) {
        val e = _state.value.dayEntries.firstOrNull { it.id == id } ?: return
        if (e.userId != ME) throw AppException("只能刪除自己寫的紀錄", "forbidden")
        _state.update { st -> st.copy(dayEntries = st.dayEntries.filterNot { it.id == id }) }
    }

    // ---------------------------------------------------------------- 備忘錄
    override suspend fun saveMemo(id: String?, title: String, body: String, media: List<MediaItem>, shared: Boolean, pinned: Boolean, color: String) {
        Limits.memo(title, body, media, color)
        val st = _state.value
        val old = st.memo(id)
        if (id != null && old == null) throw notFound()
        val now = iso()
        val m = when {
            old == null -> Memo(UUID.randomUUID().toString(), ME, title, body, media, shared && st.paired, pinned, color, now, now)
            old.userId == ME -> old.copy(title = title, body = body, media = media, shared = shared && st.paired, pinned = pinned, color = color, updatedAt = now)
            // 對方共用的：可以改內容，不能改共用設定
            else -> old.copy(title = title, body = body, media = media, pinned = pinned, color = color, updatedAt = now)
        }
        _state.update { s -> s.copy(memos = (s.memos.filterNot { it.id == m.id } + m).sortedWith(Memo.ORDER)) }
    }

    override suspend fun deleteMemo(id: String) {
        val m = _state.value.memo(id) ?: return
        if (m.userId != ME) throw AppException("這是小雨的備忘錄，只有小雨可以刪除", "forbidden")
        _state.update { s -> s.copy(memos = s.memos.filterNot { it.id == id }) }
    }

    // ---------------------------------------------------------------- 照片、語音（存在記憶體）
    override suspend fun uploadMedia(bytes: ByteArray, mime: String, kind: String, shared: Boolean, width: Int?, height: Int?, durationMs: Long?): MediaItem {
        Limits.file(bytes)
        val path = MediaPaths.under("demo/", mime)
        files[path] = bytes
        return MediaItem(path, kind, mime, width, height, durationMs)
    }

    override suspend fun downloadMedia(path: String): ByteArray =
        files[path] ?: withContext(Dispatchers.Default) { DemoMedia.sample(path) }?.also { files[path] = it } ?: throw notFound()

    /** 上傳了沒存的：從記憶體拿掉（還在用的不動） */
    override suspend fun discardMedia(items: List<MediaItem>) {
        val st = _state.value
        val used = (st.memos.flatMap { it.media } + st.dayEntries.flatMap { it.media }).mapTo(HashSet()) { it.path }
        items.forEach { if (it.path !in used) files.remove(it.path) }
    }

    fun buildPlaceBody(name: String, lat: Double, lng: Double) = buildJsonObject { put("name", name); put("lat", lat); put("lng", lng) }
}

/** 示範模式的照片、語音：用程式畫出來，不用網路也不用放圖檔 */
internal object DemoMedia {
    val PICNIC = MediaItem("demo/sample-picnic.png", MediaItem.PHOTO, "image/png", 640, 480)
    val DINNER = MediaItem("demo/sample-dinner.png", MediaItem.PHOTO, "image/png", 480, 640)
    val TRIP = MediaItem("demo/sample-trip.png", MediaItem.PHOTO, "image/png", 640, 480)
    val SUNSET = MediaItem("demo/sample-sunset.png", MediaItem.PHOTO, "image/png", 640, 480)
    val VOICE = MediaItem("demo/sample-voice.wav", MediaItem.VOICE, "audio/wav", durationMs = 3200)

    // 每張照片的漸層顏色
    private val palettes = mapOf(
        PICNIC to (0xFFFFB3C7 to 0xFFFFE3A3),
        DINNER to (0xFFFFC9A8 to 0xFFFF8FB1),
        TRIP to (0xFFB8E0FF to 0xFFD9C2FF),
        SUNSET to (0xFFFFB38A to 0xFFC9A7FF),
    )

    fun sample(path: String): ByteArray? {
        if (path == VOICE.path) return chime(VOICE.durationMs!!)
        val (item, colors) = palettes.entries.firstOrNull { it.key.path == path }?.toPair() ?: return null
        return photo(item.width!!, item.height!!, colors.first.toInt(), colors.second.toInt(), path.hashCode())
    }

    /** 粉粉的漸層，加上幾顆泡泡和一顆愛心（PNG） */
    fun photo(width: Int, height: Int, from: Int, to: Int, seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        val rnd = Random(seed)
        val side = min(width, height).toFloat()
        repeat(14) {
            p.color = Color.argb(rnd.nextInt(40, 110), 255, 255, 255)
            c.drawCircle(rnd.nextFloat() * width, rnd.nextFloat() * height, side * (0.02f + rnd.nextFloat() * 0.1f), p)
        }
        p.color = Color.argb(235, 255, 255, 255)
        c.drawPath(heart(width / 2f, height / 2f, side * 0.2f), p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun heart(cx: Float, cy: Float, r: Float) = Path().apply {
        moveTo(cx, cy + r * 0.9f)
        cubicTo(cx - r * 1.6f, cy - r * 0.1f, cx - r * 0.7f, cy - r * 1.3f, cx, cy - r * 0.45f)
        cubicTo(cx + r * 0.7f, cy - r * 1.3f, cx + r * 1.6f, cy - r * 0.1f, cx, cy + r * 0.9f)
        close()
    }

    /** 叮叮咚咚的小旋律（WAV，16 kHz 單聲道） */
    fun chime(durationMs: Long): ByteArray {
        val rate = 16_000
        val n = (rate * durationMs / 1000).toInt()
        val notes = doubleArrayOf(1046.5, 1318.5, 1568.0, 2093.0)
        val each = n / notes.size
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val k = (i / each).coerceAtMost(notes.size - 1)
            val t = (i - k * each).toDouble() / rate
            data.putShort((sin(2 * PI * notes[k] * i / rate) * exp(-t * 4) * 0.35 * Short.MAX_VALUE).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + n * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(n * 2)
        }
        return header.array() + data.array()
    }
}
