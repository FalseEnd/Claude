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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DayEntry
import tw.xiaoliangkou.app.data.Limits
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

/** 換月份時在背景載入那段期間的紀錄；載入過的區間（含啟動時的預設區間）不再重複載入 */
@Composable
internal fun LoadJournal(s: AppState, vm: AppViewModel, month: YearMonth) {
    val repo by vm.repo.collectAsState()
    LoadJournal(repo, month, ready = s.paired && !s.schemaOutdated, onError = vm::toast)
}

@Composable
internal fun LoadJournal(repo: CoupleRepository?, month: YearMonth, ready: Boolean, onError: (String) -> Unit) {
    val loaded = remember(repo) { mutableListOf(RemoteRepository.defaultDayRange()) }
    var warned by remember(repo) { mutableStateOf(false) }
    LaunchedEffect(repo, month, ready) {
        val r = repo ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val (from, to) = journalRange(month)
        if (loaded.any { !from.isBefore(it.first) && !to.isAfter(it.second) }) return@LaunchedEffect
        // 連續翻頁時只載入最後停下來的那個月
        delay(250)
        try {
            withContext(Dispatchers.IO) { r.loadDayEntries(from, to) }
            loaded += from to to
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

/** 日曆的「這天的生活」：兩個人那天的紀錄，自己寫的點了可以編輯 */
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

@Composable
private fun DayEntryRow(s: AppState, vm: AppViewModel, e: DayEntry, onEdit: () -> Unit) {
    val mine = e.userId == s.myId
    val who = if (mine) s.me else s.partner
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .then(if (mine) Modifier.bouncyClick(onClick = onEdit) else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(who?.displayName ?: if (mine) "我" else s.partnerName, if (mine) Cute.colors.me else Cute.colors.partner, 36.dp, image = who?.avatarUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (mine) "我" else s.partnerName, style = MaterialTheme.typography.titleSmall)
                Text(entryTime(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                if (mine) Icon(Icons.Rounded.Edit, "編輯", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if (e.body.isNotBlank()) Text(e.body, style = MaterialTheme.typography.bodyMedium)
            if (e.media.isNotEmpty()) MediaThumbs(e.media, vm, size = 84.dp)
        }
    }
}

/** 寫下的時間：當天寫的只顯示幾點，事後補寫的加上日期 */
private fun entryTime(e: DayEntry): String {
    val written = Fmt.localDate(e.time)
    val at = if (written.toString() == e.day) Fmt.time(e.time) else "${Fmt.md(written)} ${Fmt.time(e.time)} 補寫"
    val edited = e.updatedAt?.toInstantOrNull()?.isAfter(e.time.plusSeconds(60)) == true
    return if (edited) "$at · 改過" else at
}

/** 新增或編輯那天的生活紀錄：文字、照片、語音（放在兩個人共用的地方） */
@Composable
fun DayEntrySheet(s: AppState, vm: AppViewModel, id: String?, date: LocalDate, close: () -> Unit) {
    val e = id?.let { x -> s.dayEntries.firstOrNull { it.id == x } }
    if (e != null && e.userId != s.myId) return PartnerEntry(s, vm, e)
    var day by remember(id) { mutableStateOf(e?.day ?: date.toString()) }
    var body by remember(id) { mutableStateOf(e?.body ?: "") }
    var media by remember(id) { mutableStateOf(e?.media ?: emptyList()) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
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
        MediaPickerRow(vm, shared = true, onAdded = { media = media + it }, remaining = Limits.MEDIA - media.size)
        CuteButton("儲存", Modifier.fillMaxWidth(), enabled = (body.isNotBlank() || media.isNotEmpty()) && !vm.busy) {
            val d = Fmt.parseDate(day) ?: date
            vm.act(if (e == null) "記下來了，${s.partnerName} 也看得到 📔" else "已更新", hearts = e == null, onDone = close) {
                it.saveDayEntry(e?.id, d, body.trim(), media)
            }
        }
        if (e != null) {
            SoftButton(if (confirmDelete) "再按一次就刪除" else "刪除這則紀錄", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy) {
                if (confirmDelete) vm.act("已刪除", onDone = close) { it.deleteDayEntry(e.id) } else confirmDelete = true
            }
        }
        Note("兩個人都看得到這天的紀錄，新增時 ${s.partnerName} 會收到通知。", "💞")
    }
}

/** 對方寫的紀錄：只能看 */
@Composable
private fun PartnerEntry(s: AppState, vm: AppViewModel, e: DayEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${s.partnerName} 的紀錄", style = MaterialTheme.typography.titleLarge)
        Text(entryTime(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.body.isNotBlank()) Text(e.body, style = MaterialTheme.typography.bodyLarge)
        MediaThumbs(e.media, vm)
        Note("只有 ${s.partnerName} 可以修改這則紀錄", "🔒")
    }
}

/** 還沒做好的畫面 */
@Composable
internal fun Placeholder(title: String, back: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(title, onBack = back)
        Note("做到一半，下一版就會出現 🛠️", "🐣")
        Text("")
    }
}
