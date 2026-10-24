package tw.xiaoliangkou.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Comfort
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphySearcher
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.CycleRing
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.GiphyPickerDialog
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.StickerImage
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.Wiggle
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.stickersUpgradeHint
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

// ---------------------------------------------------------------- 心情

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodScreen(s: AppState, vm: AppViewModel, giphy: GiphySearcher = vm.container.giphy, back: () -> Unit) {
    val current = Derived.moodToday(s, s.myId)
    var pick by remember { mutableStateOf(current?.mood) }
    var note by remember { mutableStateOf(current?.note ?: "") }
    var sticker by remember { mutableStateOf(current?.sticker?.takeIf(Giphy::isValidId)) }
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("心情", onBack = back) }
        item {
            CuteCard {
                Text("你現在覺得…", style = MaterialTheme.typography.titleLarge)
                GroupedChoices(Catalog.moodGroups, { s.canPick(it.since) || it.key == pick }) { m, modifier ->
                    MoodBubble(m.emoji, m.label, pick == m.key, modifier) { pick = m.key }
                }
                if (Catalog.moods.any { !s.canPick(it.since) }) MoreChoicesHint(s, "心情")
                // 沒有分享心情時對方看不到，貼圖也一樣
                val hint = if (s.mySharing?.shareMood == true) STICKER_HINT else "選填（目前沒有分享心情，對方看不到）"
                StickerChoice(s, vm, sticker, Catalog.mood(pick)?.let { it.label to it.giphyQuery }, giphy, hint) { sticker = it }
                CuteField(note, { note = it.take(100) }, "想多說一點（選填）", placeholder = "例如：今天被誇獎了")
                CuteButton("更新心情", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Send, enabled = pick != null && !vm.busy) {
                    val m = pick ?: return@CuteButton
                    vm.act(if (s.mySharing?.shareMood == true) "已更新，${s.partnerName} 收到通知了 ${Catalog.mood(m)?.emoji}" else "已更新（沒有分享給對方）", hearts = Catalog.mood(m)?.valence ?: 0 > 0, onDone = back) {
                        it.postMood(m, note.trim(), sticker.takeIf { s.stickersReady })
                    }
                }
                TidyText(
                    if (s.mySharing?.shareMood == true) "${s.partnerName} 會收到通知。心情不好時，對方會看到「要不要安撫一下」。" else "你目前沒有分享心情，${s.partnerName} 看不到。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            CuteCard {
                Text("這 7 天", style = MaterialTheme.typography.titleMedium)
                Row {
                    Spacer(Modifier.width(WHO))
                    days.forEach { Text(if (it == today) "今" else Fmt.weekday(it).substring(1), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // 名字可能很長（「梓梓的小宝」）：用頭像，不會被切成「梓梓」
                listOf(Triple(s.me?.displayName ?: "我", s.myId, s.me?.avatarUrl), Triple(s.partnerName, s.partner?.id, s.partner?.avatarUrl)).forEach { (name, id, avatar) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(WHO).clearAndSetSemantics { contentDescription = if (id == s.myId) "我" else name }) {
                            Avatar(name, if (id == s.myId) Cute.colors.me else Cute.colors.partner, 28.dp, image = avatar)
                        }
                        days.forEach { d ->
                            val m = if (id == s.partner?.id && s.partnerSharing?.shareMood != true) null else Derived.moodOn(s, id, d)
                            Text(Catalog.mood(m?.mood)?.emoji ?: "·", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

/** 「這 7 天」左邊頭像那一欄的寬度 */
private val WHO = 36.dp

/** 「加一張 GIPHY 貼圖」下面的說明 */
private const val STICKER_HINT = "選填，對方會看到會動的貼圖"

/**
 * 選填的 GIPHY 貼圖：還沒選是「加一張」（下面寫 [hint]），選了顯示貼圖和「換一張」「移除」。
 * 資料庫還存不了貼圖就只說要更新（送出去也會被擋下來）。[suggestion] 是選貼圖時先幫忙查的
 */
@Composable
private fun StickerChoice(
    s: AppState,
    vm: AppViewModel,
    sticker: String?,
    suggestion: Pair<String, String>?,
    giphy: GiphySearcher,
    hint: String = STICKER_HINT,
    onChange: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    when {
        !s.stickersReady -> TidyText(stickersUpgradeHint(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sticker == null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SoftButton("加一張 GIPHY 貼圖", Modifier.fillMaxWidth(), icon = Icons.Rounded.GifBox) { picking = true }
            TidyText(hint, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StickerImage(sticker, Modifier.size(120.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("換一張", Modifier.fillMaxWidth(), icon = Icons.Rounded.GifBox, small = true) { picking = true }
                SoftButton("移除", Modifier.fillMaxWidth(), icon = Icons.Rounded.Close, small = true) { onChange(null) }
            }
        }
    }
    if (picking) GiphyPickerDialog(s, vm, suggestion, onPick = onChange, onDismiss = { picking = false }, searcher = giphy)
}

/** 一組一組的選項：每組一個小標題，三個一排（最後一排不滿三個也一樣寬）；[show] 是要不要顯示這一個 */
@Composable
internal fun <T> GroupedChoices(groups: List<Catalog.Group<T>>, show: (T) -> Boolean, choice: @Composable (T, Modifier) -> Unit) {
    groups.forEach { g ->
        val items = g.items.filter(show)
        if (items.isEmpty()) return@forEach
        Text(g.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { choice(it, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 資料庫還沒更新到 0.2.3：只顯示原本的選項，說一下怎麼拿到更多 */
@Composable
private fun MoreChoicesHint(s: AppState, what: String) = TidyText(
    if (s.schemaOutdated) "執行 upgrade-v2.sql 就有更多${what}可以選" else "再執行一次 upgrade-v2.sql 就有更多${what}可以選",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
internal fun MoodBubble(emoji: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), label = "moodBg")
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = Spring.DampingRatioHighBouncy), label = "moodScale")
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(24.dp)).background(bg)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(24.dp))
            .bouncyClick(haptic = true, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selected) Wiggle(10f, 900) { Text(emoji, style = MaterialTheme.typography.headlineLarge) } else Text(emoji, style = MaterialTheme.typography.headlineLarge)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- 安撫

@Composable
fun ComfortSheet(s: AppState, vm: AppViewModel, giphy: GiphySearcher = vm.container.giphy, replyTo: Long? = null, close: () -> Unit) {
    var pick by remember { mutableStateOf("hug") }
    var message by remember { mutableStateOf("") }
    var sticker by remember { mutableStateOf<String?>(null) }
    // 點收到安撫的通知打開的：上面放對方傳來的那則，打開就算看過了
    val received = replyTo?.let { id -> s.comforts.firstOrNull { it.id == id && it.receiverId == s.myId } }
    LaunchedEffect(received?.id) {
        if (received != null && received.readAt == null) runCatching { vm.repo.value?.readComfort(received.id) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (received != null) {
            Text("回應 ${s.partnerName} 💌", style = MaterialTheme.typography.titleLarge)
            ReceivedQuote(s, received)
        } else {
            Text("安撫 ${s.partnerName} 🤗", style = MaterialTheme.typography.titleLarge)
            Text("${s.partnerName} 的手機會立刻跳出通知", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GroupedChoices(Catalog.comfortGroups, { s.canPick(it.since) }) { c, modifier ->
            MoodBubble(c.emoji, c.label, pick == c.key, modifier) { pick = c.key }
        }
        if (Catalog.comforts.any { !s.canPick(it.since) }) MoreChoicesHint(s, "安撫")
        StickerChoice(s, vm, sticker, Catalog.comfort(pick)?.let { it.label to it.giphyQuery }, giphy) { sticker = it }
        CuteField(message, { message = it.take(200) }, "悄悄話（選填）", placeholder = "辛苦了，晚上帶你去吃好吃的", singleLine = false)
        CuteButton("送出${Catalog.comfort(pick)?.label}", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Send, enabled = !vm.busy) {
            val label = Catalog.comfort(pick)?.label
            vm.act("已送出$label，${s.partnerName} 收到通知了 💗", hearts = true, onDone = close) { it.sendComfort(pick, message.trim(), sticker.takeIf { s.stickersReady }) }
        }
    }
}

/** 回應時上面放的：對方傳來的那則安撫（哪一種、貼圖、悄悄話） */
@Composable
private fun ReceivedQuote(s: AppState, c: Comfort) {
    val kind = Catalog.comfort(c.kind)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.partnerSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(s.partnerName, Cute.colors.partner, 32.dp, image = s.partner?.avatarUrl)
            Column(Modifier.weight(1f)) {
                TidyText("${s.partnerName} ${kind?.push ?: "安撫了你"} ${kind?.emoji ?: "🤗"}", style = MaterialTheme.typography.titleSmall)
                Text(Fmt.rel(c.createdAt.toInstantOrNull()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        c.sticker?.takeIf(Giphy::isValidId)?.let { StickerImage(it, Modifier.size(96.dp)) }
        c.message?.trim()?.takeIf { it.isNotEmpty() }?.let { TidyText(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

// ---------------------------------------------------------------- 經期

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) {
    val c = Cute.colors
    val today = LocalDate.now()
    val mine = s.cycleOwnerIsMe
    val pr = Derived.prediction(s)
    val canEdit = mine || s.cycle.partner.settings?.partnerCanEdit == true
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("經期", onBack = back) }
        if (!s.cycleVisible) {
            item {
                CuteCard {
                    EmptyState("還沒有經期資料。\n記錄自己的週期，或等 ${s.partnerName} 開啟分享。", "🌸")
                    CuteButton("開始記錄我的週期", Modifier.fillMaxWidth()) { vm.act("開始記錄囉 🌸") { it.saveCycleSettings(buildJsonObject { put("careReminders", true) }) } }
                }
            }
            item { Disclaimer() }
            return@LazyColumn
        }
        val who = if (mine) "我" else s.partnerName
        if (pr == null) {
            item {
                CuteCard {
                    EmptyState("記下最近一次經期開始的日子，就能開始預測。", "📝")
                    if (canEdit) CuteButton("記錄經期", Modifier.fillMaxWidth()) { sheet(SheetSpec.PeriodEdit(!mine)) }
                }
            }
        } else {
            item {
                CuteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$who 的週期", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("平均 ${pr.stats.cycleLength} 天 · 經期 ${pr.stats.periodLength} 天", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val colors = (1..pr.stats.cycleLength).map { k ->
                        when (Cycle.dayPhase(pr.cycleStart.plusDays(k - 1L), s.cyclePeriods, s.cycleSettings, today).phase) {
                            Cycle.Phase.PERIOD -> c.period; Cycle.Phase.FERTILE -> c.fertile; Cycle.Phase.OVULATION -> c.ovulation; else -> c.safe
                        }
                    }
                    CycleRing(colors, if (pr.cycleDay in 1..pr.stats.cycleLength) pr.cycleDay - 1 else null, Modifier.align(Alignment.CenterHorizontally)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (pr.lateDays > 0) "延遲 ${pr.lateDays} 天" else "第 ${pr.cycleDay} 天", style = MaterialTheme.typography.headlineMedium)
                            Text(phaseLabel(pr.today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(c.period to "月經期", c.fertile to "易孕期", c.ovulation to "排卵日", c.safe to "相對安全期").forEach { (col, l) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.foundation.layout.Box(Modifier.width(12.dp).padding(vertical = 0.dp).clip(RoundedCornerShape(4.dp)).background(col).padding(6.dp))
                                Text(l, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(
                        when {
                            pr.lateDays > 0 -> "經期已延遲 ${pr.lateDays} 天" + if (pr.lateDays >= 7) "，延遲超過一週可以考慮驗孕或諮詢醫師" else ""
                            pr.today.phase == Cycle.Phase.PERIOD && !pr.today.predicted -> "經期第 ${pr.cycleDay} 天，好好休息 🍵"
                            else -> "距離下次經期 ${pr.daysUntilNext} 天（${Fmt.md(pr.nextStart)}）"
                        },
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("下次經期", Fmt.md(pr.nextStart), Modifier.weight(1f))
                        StatTile("排卵日", Fmt.md(pr.ovulation), Modifier.weight(1f))
                        StatTile("易孕期", "${Fmt.md(pr.fertileStart)}–${Fmt.md(pr.fertileEnd)}", Modifier.weight(1f))
                    }
                    if (pr.stats.irregular) Note("最近幾個週期長短差比較多，預測可能不準。", "🤔")
                    if (canEdit) {
                        val last = s.cycleBundle.periods.maxByOrNull { it.startDate }
                        // 日期看不懂（例如資料庫裡的 infinity）就當作不在經期中，不要讓畫面當掉
                        val inPeriod = last != null && last.endDate == null && Fmt.parseDate(last.startDate)?.let { it <= today } == true
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 主要的按鈕寬一點：字放大時「今天開始了」才不會被拆成兩行
                            if (inPeriod && last != null) CuteButton("經期結束了", Modifier.weight(1.5f), icon = Icons.Rounded.Check) {
                                vm.act("已記錄經期結束") { it.updatePeriod(last.id, buildJsonObject { put("endDate", today.toString()) }) }
                            } else CuteButton("今天開始了", Modifier.weight(1.5f), icon = Icons.Rounded.WaterDrop) {
                                vm.act("已記錄，預測更新了 🌸") { it.addPeriod(!mine, today.toString(), null) }
                            }
                            SoftButton("補記", Modifier.weight(1f)) { sheet(SheetSpec.PeriodEdit(!mine)) }
                        }
                        if (!mine) TidyText("${s.partnerName} 允許你幫忙記錄，記錄後對方會看到。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!mine) item { CareTips(pr) }
        }
        if (mine) {
            item { TodayBody(s, vm) }
            item {
                val st = s.cycle.mine.settings
                CuteCard {
                    Text("設定", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("分享經期給 ${s.partnerName}", s.mySharing?.shareCycle == true, "對方看得到日期與預測，看不到症狀", "🔐") { v ->
                        vm.act { it.updateSharing(buildJsonObject { put("cycle", v) }) }
                    }
                    ToggleRow("經期前提醒 ${s.partnerName} 多關心", st?.careReminders != false, "經期預計開始前 2 天通知對方", "☕") { v ->
                        vm.act { it.saveCycleSettings(buildJsonObject { put("careReminders", v) }) }
                    }
                    ToggleRow("讓 ${s.partnerName} 幫忙記錄", st?.partnerCanEdit == true, "對方可以記錄開始與結束日期", "✍️") { v ->
                        vm.act { it.saveCycleSettings(buildJsonObject { put("partnerCanEdit", v) }) }
                    }
                }
            }
        }
        val history = s.cycleBundle.periods.sortedByDescending { it.startDate }
        if (history.isNotEmpty()) {
            item { SectionTitle("紀錄", trailing = "${history.size} 次") }
            items(history, key = { it.id }) { p ->
                // 看不懂的日期就跳過這一筆
                val start = Fmt.parseDate(p.startDate) ?: return@items
                val end = Fmt.parseDate(p.endDate)
                val prev = history.getOrNull(history.indexOf(p) + 1)?.let { Fmt.parseDate(it.startDate) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("🌸", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.md(start) + (end?.let { "–${Fmt.md(it)}" } ?: " 開始"), style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(end?.let { "經期 ${java.time.temporal.ChronoUnit.DAYS.between(start, it) + 1} 天" } ?: "進行中", prev?.let { "週期 ${java.time.temporal.ChronoUnit.DAYS.between(it, start)} 天" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canEdit) androidx.compose.material3.IconButton(onClick = { vm.act("已刪除") { it.deletePeriod(p.id) } }) {
                        androidx.compose.material3.Icon(Icons.Rounded.Delete, "刪除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (mine) item { SoftButton("停止記錄經期", Modifier.fillMaxWidth()) { vm.act("已停止記錄") { it.stopCycle() } } }
        item { Disclaimer() }
    }
}

private fun phaseLabel(d: Cycle.DayInfo) = when (d.phase) {
    Cycle.Phase.PERIOD -> if (d.predicted) "預測經期" else "月經期"
    Cycle.Phase.FERTILE -> "易孕期"
    Cycle.Phase.OVULATION -> "排卵日"
    Cycle.Phase.SAFE -> "相對安全期 · " + if (d.follicular == true) "濾泡期" else "黃體期"
    Cycle.Phase.LATE -> "經期延遲"
    null -> ""
}

@Composable
private fun CareTips(pr: Cycle.Prediction) {
    val tips = when {
        pr.today.phase == Cycle.Phase.PERIOD -> listOf("🍵 準備熱水袋或暖暖包", "🧊 少約冰品和冰飲", "🤍 經痛時陪著就好，不用急著給建議")
        pr.daysUntilNext in 0..5 -> listOf("🌧️ 經前情緒起伏是正常的，多一點耐心", "🎒 包包裡幫忙備一片衛生棉", "🎬 約會可以改成在家看電影")
        else -> listOf("☀️ 這段時間通常精神比較好，適合安排出遊", "🔔 經期前兩天 App 會再提醒你")
    }
    CuteCard(color = Cute.colors.partnerSoft) {
        Text("貼心提醒 💗", style = MaterialTheme.typography.titleMedium)
        // 表情符號單獨一欄：換行時第二行對齊文字，不會跑到表情底下
        tips.forEach { tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tip.substringBefore(' '), style = MaterialTheme.typography.bodyMedium)
                TidyText(tip.substringAfter(' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayBody(s: AppState, vm: AppViewModel) {
    val today = LocalDate.now().toString()
    val log = s.cycle.mine.logs.firstOrNull { it.logDate == today }
    var flow by remember(log) { mutableStateOf(log?.flow ?: "") }
    var symptoms by remember(log) { mutableStateOf(log?.symptoms?.toSet() ?: emptySet()) }
    fun save(f: String, sy: Set<String>) = vm.act { it.saveCycleLog(today, f.ifEmpty { null }, sy.toList()) }
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今天的身體狀況", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("只有你看得到 🔒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Segmented(listOf("" to "無", "light" to "少", "medium" to "中", "heavy" to "多"), flow) { flow = it; save(it, symptoms) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.symptoms.forEach { sy ->
                ChoiceChip(sy, sy in symptoms) { symptoms = if (sy in symptoms) symptoms - sy else symptoms + sy; save(flow, symptoms) }
            }
        }
    }
}

@Composable
private fun Disclaimer() = Note(
    "預測依過去週期推算：排卵日約在下次經期前 14 天，易孕期為排卵日前 5 天到後 1 天。「安全期」不是可靠的避孕方法，排卵可能提前或延後；需要避孕請使用保險套等方式，週期異常請諮詢醫師。",
    "⚠️",
)

@Composable
fun PeriodSheet(vm: AppViewModel, forPartner: Boolean, close: () -> Unit) {
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("記錄經期 🌸", style = MaterialTheme.typography.titleLarge)
        DateField("開始", start) { start = it }
        DateField("結束（選填）", end ?: "") { end = it }
        val invalid = end != null && end!! < start
        if (invalid) Text("結束日期不能早於開始日期", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        CuteButton("儲存", Modifier.fillMaxWidth(), enabled = !invalid && !vm.busy && Fmt.parseDate(start)?.let { it <= LocalDate.now() } == true) {
            vm.act("已記錄，預測更新了", onDone = close) { it.addPeriod(forPartner, start, end) }
        }
    }
}
