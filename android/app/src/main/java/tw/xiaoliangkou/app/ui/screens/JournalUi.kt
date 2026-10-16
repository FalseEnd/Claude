package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DayEntry
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.MediaPickerRow
import tw.xiaoliangkou.app.ui.components.MediaThumbs
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate
import java.time.YearMonth

// ---------------------------------------------------------------- 日曆：記錄那天的生活

/** 月曆格子上的小標記：有照片是 📷，其他（文字、語音）是小點 */
enum class JournalMark { DOT, PHOTO }

fun journalMarks(entries: List<DayEntry>): Map<LocalDate, JournalMark> =
    entries.groupBy { it.day }.mapNotNull { (day, list) ->
        val d = Fmt.parseDate(day) ?: return@mapNotNull null
        d to if (list.any { e -> e.media.any { it.isPhoto } }) JournalMark.PHOTO else JournalMark.DOT
    }.toMap()

/** 月曆翻到這個月要載入的區間：前後多 7 天（格子會露出上下個月的幾天） */
fun journalRange(month: YearMonth): Pair<LocalDate, LocalDate> = month.atDay(1).minusDays(7) to month.atEndOfMonth().plusDays(7)

/**
 * 這個區間要不要載入：在預設區間裡面（啟動、資料有變動時都會重抓）或剛剛才載入過的就不用。
 * RemoteRepository 有變動時只重抓預設區間＋最近載入的那段，所以翻回之前看過的月份要再載一次，畫面上的月份才會即時更新。
 */
internal fun journalNeedsLoad(
    range: Pair<LocalDate, LocalDate>, last: Pair<LocalDate, LocalDate>?,
    default: Pair<LocalDate, LocalDate> = RemoteRepository.defaultDayRange(),
): Boolean = range != last && (range.first.isBefore(default.first) || range.second.isAfter(default.second))

/** 換月份時在背景載入那段期間的紀錄 */
@Composable
internal fun LoadJournal(s: AppState, vm: AppViewModel, month: YearMonth) {
    val repo by vm.repo.collectAsState()
    LoadJournal(repo, month, ready = s.paired && !s.schemaOutdated, onError = vm::toast)
}

@Composable
internal fun LoadJournal(repo: CoupleRepository?, month: YearMonth, ready: Boolean, onError: (String) -> Unit) {
    var last by remember(repo) { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var warned by remember(repo) { mutableStateOf(false) }
    LaunchedEffect(repo, month, ready) {
        val r = repo ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val range = journalRange(month)
        if (!journalNeedsLoad(range, last)) return@LaunchedEffect
        // 連續翻頁時只載入最後停下來的那個月
        delay(250)
        try {
            withContext(Dispatchers.IO) { r.loadDayEntries(range.first, range.second) }
            last = range
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                onError((e as? AppException)?.message ?: "這個月的紀錄載入失敗，等一下再試試看")
            }
        }
    }
}

/** 日曆的「這天的生活」：兩個人那天的紀錄，點了打開（自己寫的可以編輯，對方的可以看全文） */
@Composable
fun DayJournal(s: AppState, vm: AppViewModel, date: LocalDate, sheet: (SheetSpec) -> Unit) {
    val entries = s.dayEntriesOn(date)
    val today = LocalDate.now()
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmojiBubble("📔", Cute.colors.butter.copy(alpha = 0.32f), 38.dp)
            Column(Modifier.weight(1f)) {
                Text("這天的生活", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (entries.isEmpty()) "文字、照片、語音都可以" else "${entries.size} 則 · 兩個人都看得到",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        entries.forEachIndexed { i, e ->
            key(e.id) {
                if (i > 0) HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DayEntryRow(s, vm, e) { sheet(SheetSpec.DayEntryEdit(e.id, date)) }
            }
        }
        when {
            // 資料庫還沒更新：存不了，先說清楚，不要等寫完才失敗
            s.schemaOutdated -> Note("資料庫要先更新才能記錄生活：到 Supabase 的 SQL Editor 執行 upgrade-v2.sql（說明在「我的 › Supabase 與帳號」）", "🛠️")
            else -> {
                if (entries.isEmpty()) {
                    Text(
                        when {
                            date.isAfter(today) -> "這天還沒到，到時候再來寫吧 🌱"
                            date == today -> "今天過得怎麼樣？寫幾句、拍張照或錄段話 ✨"
                            else -> "這天還沒有紀錄，補一點回憶吧 ✨"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SoftButton("記錄這天 ✍️", Modifier.fillMaxWidth()) { sheet(SheetSpec.DayEntryEdit(null, date)) }
            }
        }
    }
}

@Composable
private fun DayEntryRow(s: AppState, vm: AppViewModel, e: DayEntry, onOpen: () -> Unit) {
    val mine = e.userId == s.myId
    val who = if (mine) s.me else s.partner
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).bouncyClick(onClick = onOpen).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(who?.displayName ?: if (mine) "我" else s.partnerName, if (mine) Cute.colors.me else Cute.colors.partner, 36.dp, image = who?.avatarUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (mine) "我" else s.partnerName, style = MaterialTheme.typography.titleSmall)
                Text(
                    entryTime(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (mine) Icon(Icons.Rounded.Edit, "編輯", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            // 很長的只顯示前幾行，點開看全文
            if (e.body.isNotBlank()) Text(e.body, style = MaterialTheme.typography.bodyMedium, maxLines = BODY_LINES, overflow = TextOverflow.Ellipsis)
            if (e.media.isNotEmpty()) MediaThumbs(e.media, vm, size = 84.dp)
        }
    }
}

/** 列表裡的紀錄最多顯示幾行 */
private const val BODY_LINES = 6

/** 寫下的時間：當天寫的只顯示幾點；事後補寫、提前寫的加上是哪天寫的 */
internal fun entryTime(e: DayEntry, today: LocalDate = LocalDate.now()): String {
    val written = Fmt.localDate(e.time)
    val day = e.date
    val clock = Fmt.time(e.time)
    val at = when {
        day == null || written == day -> clock
        written.isAfter(day) -> "${writtenOn(written, today)} $clock 補寫"
        else -> "${writtenOn(written, today)} $clock 提前寫"
    }
    val edited = e.updatedAt?.toInstantOrNull()?.isAfter(e.time.plusSeconds(60)) == true
    return if (edited) "$at · 改過" else at
}

private fun writtenOn(d: LocalDate, today: LocalDate) = when (d) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> if (d.year == today.year) Fmt.md(d) else "${d.year}/${Fmt.md(d)}"
}

/** 新增或編輯那天的生活紀錄：文字、照片、語音（放在兩個人共用的地方）；對方寫的只能看 */
@Composable
fun DayEntrySheet(s: AppState, vm: AppViewModel, id: String?, date: LocalDate, close: () -> Unit) {
    val live = id?.let { x -> s.dayEntries.firstOrNull { it.id == x } }
    // 自己按了刪除：面板收起來的動畫期間照樣顯示原本的內容
    val opened = remember(id) { live }
    var deleting by remember(id) { mutableStateOf(false) }
    val e = live ?: opened?.takeIf { deleting }
    when {
        // 已經被刪掉、或改到還沒載入的日子：不要變成「新增」，不然一存就多一則
        id != null && e == null -> EntryGone(close)
        e != null && e.userId != s.myId -> PartnerEntry(s, vm, e)
        else -> EntryForm(s, vm, e, date, close) { deleting = true }
    }
}

/** 這次在面板裡上傳的照片、語音：最後沒存起來的，面板關掉時刪掉 */
private class Uploads {
    val added = mutableListOf<MediaItem>()
    /** 送出儲存過的（可能已經存進資料庫，不能刪） */
    val kept = mutableSetOf<String>()
}

@Composable
private fun EntryForm(s: AppState, vm: AppViewModel, e: DayEntry?, date: LocalDate, close: () -> Unit, onDeleting: () -> Unit) {
    val entryId = e?.id
    var day by remember(entryId) { mutableStateOf(e?.day ?: date.toString()) }
    var body by remember(entryId) { mutableStateOf(e?.body ?: "") }
    var media by remember(entryId) { mutableStateOf(e?.media ?: emptyList()) }
    var confirmDelete by remember(entryId) { mutableStateOf(false) }
    var recording by remember(entryId) { mutableStateOf(false) }
    val uploads = remember(entryId) { Uploads() }
    DisposableEffect(uploads) {
        onDispose {
            val drop = uploads.added.filter { it.path !in uploads.kept }
            val r = vm.repo.value
            if (drop.isNotEmpty() && r != null) vm.container.scope.launch(Dispatchers.IO) { runCatching { r.discardMedia(drop) } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (e == null) "記錄這天的生活 ✍️" else "編輯這天的紀錄", style = MaterialTheme.typography.titleLarge)
        DateField("日期", day) { day = it }
        CuteField(
            body, { body = it.take(Limits.DAY_BODY) }, "想記下什麼", Modifier.heightIn(min = 140.dp),
            placeholder = "吃了什麼、去了哪裡、想跟 ${s.partnerName} 說的話…", singleLine = false,
            supporting = if (body.length > Limits.DAY_BODY - 200) "${body.length} / ${Limits.DAY_BODY}" else null,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("照片、語音", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (media.isNotEmpty()) Text("${media.size} / ${Limits.MEDIA}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (media.isNotEmpty()) MediaThumbs(media, vm, onRemove = { m -> media = media - m })
        MediaPickerRow(
            vm, shared = true, onAdded = { uploads.added += it; media = media + it }, remaining = Limits.MEDIA - media.size,
            onRecordingChange = { recording = it },
        )
        // 錄音中先不能存：存了面板就關掉，還沒停的那段錄音會不見
        CuteButton(
            if (recording) "先停止錄音再儲存" else "儲存", Modifier.fillMaxWidth(),
            enabled = !recording && (body.isNotBlank() || media.isNotEmpty()) && !vm.busy,
        ) {
            val d = Fmt.parseDate(day) ?: date
            val list = media
            uploads.kept += list.map { it.path }
            vm.act(if (e == null) "記下來了，${s.partnerName} 也看得到 📔" else "已更新", hearts = e == null, onDone = close) {
                it.saveDayEntry(e?.id, d, body.trim(), list)
            }
        }
        if (e != null) {
            SoftButton(if (confirmDelete) "再按一次就刪除" else "刪除這則紀錄", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy && !recording) {
                if (confirmDelete) {
                    onDeleting()
                    vm.act("已刪除", onDone = close) { it.deleteDayEntry(e.id) }
                } else confirmDelete = true
            }
        }
        Note("兩個人都看得到這天的紀錄，新增時 ${s.partnerName} 會收到通知。", "💞")
    }
}

/** 對方寫的紀錄：可以看全文，只有對方能改 */
@Composable
private fun PartnerEntry(s: AppState, vm: AppViewModel, e: DayEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(s.partner?.displayName ?: s.partnerName, Cute.colors.partner, 44.dp, image = s.partner?.avatarUrl)
            Column(Modifier.weight(1f)) {
                Text("${s.partnerName} 的紀錄", style = MaterialTheme.typography.titleLarge)
                val d = e.date
                Text(
                    listOfNotNull(d?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日（${Fmt.weekday(it).substring(1)}）" }, entryTime(e)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (e.body.isNotBlank()) Text(e.body, style = MaterialTheme.typography.bodyLarge)
        MediaThumbs(e.media, vm)
        Note("只有 ${s.partnerName} 可以修改這則紀錄", "🔒")
    }
}

/** 打開的紀錄已經不在了 */
@Composable
private fun EntryGone(close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("找不到這則紀錄", style = MaterialTheme.typography.titleLarge)
        Note("這則紀錄已經不在了，可能被刪掉或改到別天。", "🍃")
        SoftButton("關閉", Modifier.fillMaxWidth()) { close() }
    }
}
