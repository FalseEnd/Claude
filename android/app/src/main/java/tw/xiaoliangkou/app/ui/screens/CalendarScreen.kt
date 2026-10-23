package tw.xiaoliangkou.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DateEvent
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CountUpText
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [jumpTo]：從通知、存好的約會指定要看的那天，跳過去以後呼叫 [onJumped] 清掉（只跳一次）；
 * [jumpId] 不一樣時，同一天也會再跳一次
 */
@Composable
fun CalendarScreen(s: AppState, vm: AppViewModel, sheet: (SheetSpec) -> Unit, jumpTo: LocalDate? = null, jumpId: Long = 0L, onJumped: () -> Unit = {}) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(jumpTo ?: today)) }
    var selected by remember { mutableStateOf(jumpTo ?: today) }
    val showCycle = s.cycleVisible && s.cyclePeriods.isNotEmpty()
    val eventsByDay = s.events.groupBy { it.localDate }
    val annByDay = s.anniversaries.mapNotNull { a ->
        val d = Fmt.parseDate(a.date) ?: return@mapNotNull null
        val occ = if (a.repeat == "yearly") Dates.nextOccurrence(d, LocalDate.of(month.year, 1, 1), true).date else d
        occ to a
    }.groupBy({ it.first }, { it.second })
    val journal = remember(s.dayEntries) { journalMarks(s.dayEntries) }
    LoadJournal(s, vm, month)
    // 從通知、存好的約會指定了日期（已經在日曆上也一樣）：跳到那一天
    LaunchedEffect(jumpTo, jumpId) {
        val d = jumpTo ?: return@LaunchedEffect
        selected = d; month = YearMonth.from(d)
        onJumped()
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenHeader("日曆") { CuteButton("約會", icon = Icons.Rounded.Add, small = true) { sheet(SheetSpec.EventEdit(null, selected)) } }
        }
        item {
            CuteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Rounded.ChevronLeft, "上個月") }
                    AnimatedContent(month, Modifier.weight(1f), transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it * dir / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 2 } + fadeOut())
                    }, label = "month") { m ->
                        Text("${m.year} 年 ${m.monthValue} 月", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Rounded.ChevronRight, "下個月") }
                }
                Row { listOf("日", "一", "二", "三", "四", "五", "六").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value % 7
                val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
                (0 until cells).chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { i ->
                            val d = first.plusDays((i - offset).toLong())
                            val phase = if (showCycle) Cycle.dayPhase(d, s.cyclePeriods, s.cycleSettings, today) else null
                            DayCell(
                                d, Modifier.weight(1f), inMonth = d.month == month.month, today = d == today, selected = d == selected,
                                phase = phase, event = eventsByDay[d] != null, ann = annByDay[d] != null,
                                milestone = s.togetherSince?.let { Dates.isMilestone(Dates.daysTogether(it, d)) } == true,
                                journal = journal[d],
                            ) { selected = d; if (d.month != month.month) month = YearMonth.from(d) }
                        }
                    }
                }
                Legend(showCycle)
            }
        }
        item { DayPanel(s, vm, selected, eventsByDay[selected].orEmpty(), annByDay[selected].orEmpty().map { it.title to it.kind }, showCycle, sheet) }
        item { DayJournal(s, vm, selected, sheet) }
        val upcoming = s.events.filter { !it.localDate.isBefore(today) && it.status != "cancelled" }.sortedBy { it.start }
        item { SectionTitle("接下來的約會", trailing = "${upcoming.size} 個") }
        if (upcoming.isEmpty()) item { CuteCard { EmptyState("還沒有約會，約一下吧", "🗓️") } }
        items(upcoming, key = { it.id }) { e -> EventRow(s, vm, e, withDate = true) { sheet(SheetSpec.EventEdit(e.id, e.localDate)) } }
    }
}

@Composable
private fun DayCell(
    d: LocalDate, modifier: Modifier, inMonth: Boolean, today: Boolean, selected: Boolean,
    phase: Cycle.DayInfo?, event: Boolean, ann: Boolean, milestone: Boolean, journal: JournalMark?, onClick: () -> Unit,
) {
    val c = Cute.colors
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        phase?.phase == Cycle.Phase.PERIOD && !phase.predicted -> c.periodSoft
        phase?.phase == Cycle.Phase.FERTILE || phase?.phase == Cycle.Phase.OVULATION -> c.fertileSoft
        else -> Color.Transparent
    }
    val border = when {
        selected -> Color.Transparent
        phase?.phase == Cycle.Phase.PERIOD && phase.predicted -> c.period
        phase?.phase == Cycle.Phase.OVULATION -> c.fertile
        today -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    // 讀螢幕時：格子裡的小標記改用文字說明
    val marks = listOfNotNull(
        if (today) "今天" else null,
        if (event) "有約會" else null,
        if (ann) "紀念日" else null,
        if (milestone) "里程碑" else null,
        when (journal) { JournalMark.PHOTO -> "有生活紀錄和照片"; JournalMark.DOT -> "有生活紀錄"; null -> null },
        when (phase?.phase) {
            Cycle.Phase.PERIOD -> if (phase.predicted) "預測經期" else "經期"
            Cycle.Phase.FERTILE -> "易孕期"
            Cycle.Phase.OVULATION -> "排卵日"
            else -> null
        },
    ).joinToString("、")
    Column(
        modifier.aspectRatio(0.92f).clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .semantics { if (marks.isNotEmpty()) stateDescription = marks; this.selected = selected }
            .bouncyClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge,
            color = when { selected -> MaterialTheme.colorScheme.onPrimary; !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f); today -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface },
        )
        Row(Modifier.height(10.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val dot = if (selected) MaterialTheme.colorScheme.onPrimary else null
            if (event) Box(Modifier.size(5.dp).clip(CircleShape).background(dot ?: MaterialTheme.colorScheme.onSurfaceVariant))
            if (ann) Icon(Icons.Rounded.Favorite, null, tint = dot ?: c.thread, modifier = Modifier.size(8.dp))
            if (milestone) Box(Modifier.size(5.dp).clip(CircleShape).background(dot ?: c.butter))
            // 有照片是小相機，其他是小點；跟小點同色，深色模式、選到的格子也看得清楚
            when (journal) {
                JournalMark.PHOTO -> Icon(Icons.Rounded.PhotoCamera, null, tint = dot ?: MaterialTheme.colorScheme.secondary, modifier = Modifier.size(10.dp))
                JournalMark.DOT -> Box(Modifier.size(5.dp).clip(CircleShape).background(dot ?: MaterialTheme.colorScheme.secondary))
                null -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(cycle: Boolean) {
    val c = Cute.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem("約會") { Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant)) }
        // 用向量圖示：「♥」會變成彩色表情，行高比較高，旁邊的字會往下掉
        LegendItem("紀念日") { Icon(Icons.Rounded.Favorite, null, tint = c.thread, modifier = Modifier.size(12.dp)) }
        LegendItem("里程碑") { Box(Modifier.size(6.dp).clip(CircleShape).background(c.butter)) }
        LegendItem("生活紀錄") { Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary)) }
        LegendItem("有照片") { Icon(Icons.Rounded.PhotoCamera, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(12.dp)) }
        if (cycle) {
            LegendItem("經期") { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(c.periodSoft)) }
            LegendItem("預測經期") { Box(Modifier.size(12.dp).border(1.5.dp, c.period, RoundedCornerShape(4.dp))) }
            LegendItem("易孕期") { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(c.fertileSoft)) }
            LegendItem("排卵日") { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(c.fertileSoft).border(1.5.dp, c.fertile, RoundedCornerShape(4.dp))) }
        }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        swatch()
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayPanel(s: AppState, vm: AppViewModel, d: LocalDate, events: List<DateEvent>, anns: List<Pair<String, String>>, showCycle: Boolean, sheet: (SheetSpec) -> Unit) {
    val today = LocalDate.now()
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.monthValue} 月 ${d.dayOfMonth} 日（${Fmt.weekday(d).substring(1)}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(if (d == today) "今天" else Fmt.daysLeft(Dates.between(today, d)))
        }
        anns.forEach { (title, kind) -> Line(if (kind == "birthday") "🎂" else "💝", title, "紀念日 · 兩個人都會收到提醒") }
        s.togetherSince?.let { since -> val n = Dates.daysTogether(since, d); if (Dates.isMilestone(n)) Line("✨", "在一起第 $n 天", "里程碑") }
        events.forEach { e -> EventRow(s, vm, e, withDate = false) { sheet(SheetSpec.EventEdit(e.id, e.localDate)) } }
        if (showCycle) {
            val ph = Cycle.dayPhase(d, s.cyclePeriods, s.cycleSettings, today)
            if (ph.phase != null) {
                val label = when (ph.phase) { Cycle.Phase.PERIOD -> if (ph.predicted) "預測經期" else "經期"; Cycle.Phase.FERTILE -> "易孕期"; Cycle.Phase.OVULATION -> "排卵日"; else -> "相對安全期" }
                Line("🌸", "${if (s.cycleOwnerIsMe) "我" else s.partnerName} · $label", "週期第 ${ph.cycleDay} 天" + (ph.follicular?.let { if (it) " · 濾泡期" else " · 黃體期" } ?: ""))
            }
        }
        if (events.isEmpty() && anns.isEmpty()) Text("這天還沒有安排 🍃", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftButton("在這天約會", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) { sheet(SheetSpec.EventEdit(null, d)) }
    }
}

@Composable
private fun Line(emoji: String, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EmojiBubble(emoji, MaterialTheme.colorScheme.surfaceVariant, 38.dp)
        Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventRow(s: AppState, vm: AppViewModel, e: DateEvent, withDate: Boolean, onClick: () -> Unit) {
    val mine = e.createdBy == s.myId
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).bouncyClick(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val invited = e.status == "proposed" && !mine
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmojiBubble("📅", if (mine) Cute.colors.meSoft else Cute.colors.partnerSoft)
            Column(Modifier.weight(1f)) {
                // 狀態放在名稱旁邊，下面的日期、時間、地點可以用整個寬度
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TidyText(e.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    when {
                        e.status == "confirmed" -> Pill("已確認", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        e.status == "declined" -> Pill("已婉拒")
                        e.status == "proposed" && mine -> Pill("等 ${s.partnerName} 回覆", Cute.colors.butter.copy(alpha = 0.4f))
                        else -> Unit // 對方約你：標籤放在下面的答應、婉拒旁邊
                    }
                }
                // 日期、時間、地點、備註：放不下就換行，不要把時間切掉；備註太長才會「…」（點進去看得到全部）
                TidyText(
                    listOfNotNull(if (withDate) Fmt.dayLabel(e.localDate) else null, Fmt.time(e.start), e.locationText, e.note).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (invited) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("${s.partnerName} 約你 💌", MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(Alignment.CenterVertically))
                CuteButton("答應", icon = Icons.Rounded.Check, small = true) { vm.act("已答應，${s.partnerName} 收到通知了 🥳", hearts = true) { it.answerEvent(e.id, "confirmed") } }
                SoftButton("婉拒", small = true) { vm.act("已婉拒") { it.answerEvent(e.id, "declined") } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventSheet(s: AppState, vm: AppViewModel, id: String?, date: LocalDate, close: () -> Unit) {
    val e = id?.let { x -> s.events.firstOrNull { it.id == x } }
    val zone = ZoneId.systemDefault()
    val start = e?.start?.atZone(zone)
    var title by remember { mutableStateOf(e?.title ?: "") }
    var day by remember { mutableStateOf((start?.toLocalDate() ?: date).toString()) }
    var time by remember { mutableStateOf(start?.toLocalTime() ?: LocalTime.of(19, 0)) }
    var place by remember { mutableStateOf(e?.locationText ?: "") }
    var note by remember { mutableStateOf(e?.note ?: "") }
    var remind by remember { mutableStateOf(e?.remindMinutes?.firstOrNull() ?: 60) }
    var pickTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (e == null) "約 ${s.partnerName} 💌" else "編輯約會", style = MaterialTheme.typography.titleLarge)
        CuteField(title, { title = it.take(40) }, "要做什麼", placeholder = "例如：吃火鍋、看夜景")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("🍲 吃火鍋", "🎬 看電影", "🌃 看夜景", "🧺 野餐", "☕ 咖啡廳").forEach { idea -> ChoiceChip(idea, title == idea.substringAfter(" ")) { title = idea.substringAfter(" ") } }
        }
        // 日期換成兩行（別年的日期、字放大）時，旁邊的時間也一樣高
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { DateField("日期", day, Modifier.fillMaxHeight()) { day = it } }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Cute.colors.card).bouncyClick { pickTime = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("時間", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%02d:%02d".format(time.hour, time.minute), style = MaterialTheme.typography.titleMedium)
            }
        }
        CuteField(place, { place = it.take(40) }, "地點", placeholder = "選一個標點或自己輸入")
        // 小膠囊放不下就換到下一行，不要被擠成一字一行
        if (s.places.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.places.take(3).forEach { p -> ChoiceChip(p.name, place == p.name, placeEmoji(p.icon)) { place = p.name } }
        }
        CuteField(note, { note = it.take(100) }, "備註（選填）")
        Text("提醒", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 分鐘前", 60 to "1 小時前", 1440 to "前一天").forEach { (m, l) -> ChoiceChip(l, remind == m) { remind = m } }
        }
        CuteButton(if (e == null) "送出邀請" else "儲存", Modifier.fillMaxWidth(), enabled = title.isNotBlank() && !vm.busy) {
            val instant = LocalDateTime.of(LocalDate.parse(day), time).atZone(zone).toInstant()
            val body = buildJsonObject {
                put("title", title.trim()); put("startsAt", instant.toString())
                put("locationText", place.trim()); put("note", note.trim())
                putJsonArray("remindMinutes") { add(JsonPrimitive(remind)) }
            }
            vm.act(if (e == null) "已送出邀請，等 ${s.partnerName} 回覆 💌" else "已更新", hearts = e == null, onDone = close) { it.saveEvent(id, body) }
        }
        if (e != null && e.createdBy == s.myId) SoftButton("刪除約會", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete) { vm.act("已刪除", onDone = close) { it.deleteEvent(e.id) } }
        Text("${s.partnerName} 答應後，兩個人都會在約會前收到提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pickTime) TimePickerPopup(time, { pickTime = false }) { time = it; pickTime = false }
}

// ---------------------------------------------------------------- 紀念日

@Composable
fun AnniversaryScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) {
    val today = LocalDate.now()
    val since = s.togetherSince
    val occ = s.anniversaries.mapNotNull { a -> Fmt.parseDate(a.date)?.let { a to Dates.nextOccurrence(it, today, a.repeat == "yearly") } }.filter { it.second.daysLeft >= 0 }.sortedBy { it.second.daysLeft }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("紀念日", onBack = back) { CuteButton("新增", icon = Icons.Rounded.Add, small = true) { sheet(SheetSpec.AnniversaryEdit(null)) } } }
        if (since != null) item {
            CuteCard(color = Cute.colors.partnerSoft) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeartBeat { Text("💞", style = MaterialTheme.typography.displayMedium) }
                    Text("從 ${since.year}/${since.monthValue}/${since.dayOfMonth} 開始", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CountUpText(Dates.daysTogether(since, today), MaterialTheme.typography.displayLarge, suffix = " 天", color = MaterialTheme.colorScheme.primary)
                    Text("在一起的每一天都算數", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (since != null) {
            item { SectionTitle("里程碑") }
            items(Dates.upcomingMilestones(since, today, 3), key = { "m${it.day}" }) { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmojiBubble(if (m.day == 520) "💗" else if (m.day == 1314) "💍" else "✨", Cute.colors.butter.copy(alpha = 0.3f))
                    Column(Modifier.weight(1f)) { Text("第 ${m.day} 天", style = MaterialTheme.typography.titleSmall); TidyText(fullDay(m.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Pill(Fmt.daysLeft(m.daysLeft), if (m.daysLeft <= 7) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        item { SectionTitle("紀念日", trailing = "${s.anniversaries.size} 個") }
        if (occ.isEmpty()) item { CuteCard { EmptyState("還沒有紀念日，新增第一個吧", "🎈") } }
        items(occ, key = { it.first.id }) { (a, o) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).bouncyClick { sheet(SheetSpec.AnniversaryEdit(a.id)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmojiBubble(when (a.kind) { "birthday" -> "🎂"; "first_date" -> "🌹"; "together" -> "💞"; else -> "💝" }, Cute.colors.partnerSoft)
                Column(Modifier.weight(1f)) {
                    // 還有幾天放在名稱旁邊，下面的日期和提醒可以用整個寬度
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 「第 2 年」「27 歲」用不換行空格連起來，換行時整個一起換
                        TidyText(
                            a.title + if (o.years > 0) (if (a.kind == "birthday") " · ${o.years}\u00A0歲" else " · 第\u00A0${o.years}\u00A0年") else "",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        )
                        if (o.daysLeft == 0) HeartBeat { Pill("就是今天 🎉", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
                        else Pill(Fmt.daysLeft(o.daysLeft), if (o.daysLeft <= 7) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    }
                    // 日期和提醒：放不下就在「·」後面換行，不會被切掉
                    TidyText(
                        "${fullDay(o.date)} · 提醒：" + a.remindDays.joinToString("、") { if (it == 0) "當天" else "前\u00A0$it\u00A0天" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Note("兩個人都會收到提醒。2/29 的紀念日在平年會在 2/28 提醒。", "🔔") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnniversarySheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) {
    val a = id?.let { x -> s.anniversaries.firstOrNull { it.id == x } }
    var title by remember { mutableStateOf(a?.title ?: "") }
    var date by remember { mutableStateOf(a?.date ?: LocalDate.now().toString()) }
    var kind by remember { mutableStateOf(a?.kind ?: "custom") }
    var yearly by remember { mutableStateOf((a?.repeat ?: "yearly") == "yearly") }
    var remind by remember { mutableStateOf(a?.remindDays?.toSet() ?: setOf(0, 1)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (a == null) "新增紀念日 🎈" else "編輯紀念日", style = MaterialTheme.typography.titleLarge)
        CuteField(title, { title = it.take(30) }, "名稱", placeholder = "例如：第一次出國")
        DateField("日期", date) { date = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("custom" to "💝 自訂", "birthday" to "🎂 生日", "first_date" to "🌹 第一次約會").forEach { (k, l) -> ChoiceChip(l, kind == k) { kind = k } }
        }
        Text("提醒時間", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "當天", 1 to "前 1 天", 3 to "前 3 天", 7 to "前 7 天").forEach { (d, l) -> ChoiceChip(l, d in remind) { remind = if (d in remind) remind - d else remind + d } }
        }
        ToggleRow("每年重複", yearly, emoji = "🔁") { yearly = it }
        CuteButton("儲存", Modifier.fillMaxWidth(), enabled = title.isNotBlank() && !vm.busy) {
            val body = buildJsonObject {
                put("title", title.trim()); put("date", date); put("kind", kind); put("repeat", if (yearly) "yearly" else "none")
                putJsonArray("remindDays") { (remind.ifEmpty { setOf(0) }).sorted().forEach { add(JsonPrimitive(it)) } }
            }
            vm.act("已儲存，兩個人都會收到提醒 🔔", hearts = a == null, onDone = close) { it.saveAnniversary(id, body) }
        }
        if (a != null) SoftButton("刪除", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete) { vm.act("已刪除", onDone = close) { it.deleteAnniversary(a.id) } }
    }
}

/** 2027/4/21（三）：比 2027-04-21（週三）短，字放大時也放得下一行 */
private fun fullDay(d: LocalDate) = "${d.year}/${d.monthValue}/${d.dayOfMonth}（${Fmt.weekday(d).substring(1)}）"

// ---------------------------------------------------------------- 日期與時間選擇

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerPopup(value: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val initial = Fmt.parseDate(value) ?: LocalDate.now()
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } ?: onDismiss()
            }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerPopup(value: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(value.hour, value.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(Cute.colors.card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("選時間 ⏰", style = MaterialTheme.typography.titleLarge)
            TimePicker(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("取消", small = true, onClick = onDismiss)
                CuteButton("確定", small = true) { onPick(LocalTime.of(state.hour, state.minute)) }
            }
        }
    }
}

