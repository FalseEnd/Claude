package tw.xiaoliangkou.app.ui.screens

import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.Memo
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.MediaPickerRow
import tw.xiaoliangkou.app.ui.components.MediaThumbs
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.VoiceDraft
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.formatDuration
import tw.xiaoliangkou.app.ui.components.rememberUploads
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.theme.Cute
import tw.xiaoliangkou.app.widget.MemoWidget
import tw.xiaoliangkou.app.widget.MemoWidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets

/** 便利貼的顏色：淺色、深色模式的底色（跟桌面小工具一樣），和選顏色的圓點 */
private class MemoColor(val key: String, val label: String, val light: Color, val dark: Color, val dot: Color)

private val MEMO_COLORS = listOf(
    MemoColor("pink", "粉紅", Color(0xFFFFE1EA), Color(0xFF4A2A37), Color(0xFFFF8FB1)),
    MemoColor("yellow", "鵝黃", Color(0xFFFFF0BD), Color(0xFF463C22), Color(0xFFFFCF4D)),
    MemoColor("mint", "薄荷", Color(0xFFD5F3E8), Color(0xFF1E443B), Color(0xFF5FCBAE)),
    MemoColor("sky", "天空", Color(0xFFDCEDFF), Color(0xFF233752), Color(0xFF72B4FF)),
    MemoColor("lavender", "薰衣草", Color(0xFFEAE3FF), Color(0xFF35295A), Color(0xFFA58BFF)),
    MemoColor("peach", "蜜桃", Color(0xFFFFE3D3), Color(0xFF4B3024), Color(0xFFFFA479)),
)

private fun memoColor(key: String) = MEMO_COLORS.firstOrNull { it.key == key } ?: MEMO_COLORS[0]

@Composable
private fun noteColor(key: String): Color {
    val c = memoColor(key)
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) c.dark else c.light
}

// ---------------------------------------------------------------- 備忘錄列表

@Composable
fun MemosScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) {
    val context = LocalContext.current
    // 資料庫還沒更新：存不了，先不能新增（跟日曆、備註一樣），也先不用提小工具
    val canAdd = !s.schemaOutdated
    val offerWidget = !rememberMemoWidgetPlaced() && canAdd
    val pinned = s.pinnedMemos
    val others = s.memos.filterNot { it.pinned }
    val full = StaggeredGridItemSpan.FullLine
    val open: (Memo) -> Unit = { sheet(SheetSpec.MemoEdit(it.id)) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = full) {
            ScreenHeader("備忘錄", onBack = back) {
                CuteButton("新增備忘錄", icon = Icons.Rounded.Add, small = true, enabled = canAdd) { sheet(SheetSpec.MemoEdit(null)) }
            }
        }
        // 資料庫還沒更新：先說清楚，不要等按了儲存才跳錯誤
        if (s.schemaOutdated) item(span = full) { Note(Errors.SCHEMA_OUTDATED, "🛠️", Cute.colors.butter.copy(alpha = 0.3f)) }
        if (s.memos.isEmpty()) item(span = full) {
            CuteCard {
                EmptyState("還沒有備忘錄\n採買清單、想去的地方、${if (s.paired) "給 ${s.partnerName} 的小紙條" else "給自己的小提醒"}\n都可以記在這裡", "📝")
                CuteButton("新增第一則備忘錄", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = canAdd) { sheet(SheetSpec.MemoEdit(null)) }
            }
        }
        if (pinned.isNotEmpty()) {
            item(span = full) {
                SectionTitle("📌 在桌面", Modifier.padding(top = 4.dp), trailing = if (pinned.size > MemoWidgetSnapshot.MAX) "小工具顯示前 ${MemoWidgetSnapshot.MAX} 則" else "${pinned.size} 則")
            }
            items(pinned, key = { it.id }) { MemoCard(it, s, vm) { open(it) } }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) item(span = full) { SectionTitle("其他備忘錄", Modifier.padding(top = 4.dp), trailing = "${others.size} 則") }
            items(others, key = { it.id }) { MemoCard(it, s, vm) { open(it) } }
        }
        item(span = full) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Note(
                    (if (s.paired) "共用的備忘錄 ${s.partnerName} 也看得到、也能一起改。" else "配對後就可以和另一半共用備忘錄。") +
                        if (!offerWidget) "\n打開「放在桌面」，就會出現在桌面的小工具上。"
                        else "\n打開「放在桌面」的備忘錄會顯示在「備忘錄」小工具上，桌面上還沒有這個小工具，先把它加到桌面吧。",
                    "📌",
                )
                // 桌面上還沒有備忘錄小工具：「放在桌面」打開了也看不到，直接在這裡加
                if (offerWidget) PinMemoWidgetButton(vm, context)
            }
        }
    }
}

/** 一張便利貼：第一張照片、標題、內容、語音，下面寫誰的、誰看得到、什麼時候改的 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoCard(m: Memo, s: AppState, vm: AppViewModel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val photos = m.media.filter { it.isPhoto }
    val voices = m.media.filter { it.isVoice }
    val mine = m.userId == s.myId
    val shadow = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    // 整張一起縮放（底色、陰影、圖釘也是），圖釘併進卡片裡唸：「已放在桌面」
    Box(
        Modifier.fillMaxWidth()
            .bouncyClick(onClick = onClick)
            .semantics { if (m.pinned) stateDescription = "已放在桌面" }
            .shadow(6.dp, shape, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(noteColor(m.color)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            photos.firstOrNull()?.let { p ->
                Box {
                    MemoPhoto(p, vm, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                    if (photos.size > 1) {
                        Text(
                            "+${photos.size - 1}", style = MaterialTheme.typography.labelMedium, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.title.isNotBlank()) {
                    // 標題換行時挑空白、標點的地方換（「週末採買清單 / 🛒」，不會變成「週末採買清 / 單」）
                    TidyText(
                        m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        // 留位置給右上角的圖釘
                        modifier = Modifier.padding(end = if (m.pinned && photos.isEmpty()) 14.dp else 0.dp),
                    )
                }
                if (m.body.isNotBlank()) {
                    val plain = m.title.isBlank()
                    Text(
                        m.body.trim(), style = if (plain) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        maxLines = 8, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (plain) 0.9f else 0.78f),
                    )
                }
                if (voices.isNotEmpty()) {
                    val label = if (voices.size == 1) voices[0].durationMs?.let(::formatDuration) ?: "語音" else "${voices.size} 段語音"
                    Text(
                        "🎙️ $label", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                // 誰的、什麼時候：放不下同一行時，時間換到下一行（不會擠成「🔒 …」）
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (!mine) Avatar(s.partnerName, Cute.colors.partner, 18.dp, image = s.partner?.avatarUrl)
                        Text(
                            if (!mine) s.partnerName else if (m.shared) "👫 共用" else "🔒 只有我",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        Fmt.rel(m.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        if (m.pinned) {
            Text(
                "📌", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp).clearAndSetSemantics {},
            )
        }
    }
}

/** 便利貼上的照片（從 MediaCache 讀，背景執行緒） */
@Composable
private fun MemoPhoto(item: MediaItem, vm: AppViewModel, modifier: Modifier) {
    val cache = vm.container.media
    val image by produceState<ImageBitmap?>(cache.cachedBitmap(item.path, PHOTO_SIDE)?.asImageBitmap(), item.path) {
        if (value == null) value = cache.bitmap(item.path, PHOTO_SIDE)?.asImageBitmap()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, "照片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("📷", Modifier.alpha(0.45f), style = MaterialTheme.typography.titleLarge)
    }
}

private const val PHOTO_SIDE = 512

// ---------------------------------------------------------------- 新增、編輯

@Composable
fun MemoSheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) {
    val original = remember(id) { s.memo(id) }
    if (id != null && (original == null || (s.memo(id) == null && !vm.busy))) {
        // 對方刪掉了，或改成只有自己看得到
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState("這則備忘錄已經不在了", "🫥")
            SoftButton("關閉", Modifier.fillMaxWidth(), onClick = close)
        }
        return
    }
    val mine = original == null || original.userId == s.myId
    val canShare = mine && s.couple != null
    var title by remember(id) { mutableStateOf(original?.title ?: "") }
    var body by remember(id) { mutableStateOf(original?.body ?: "") }
    var color by remember(id) { mutableStateOf(original?.color?.takeIf { it in Memo.COLORS } ?: "pink") }
    var shared by remember(id) { mutableStateOf(original?.shared ?: true) }
    var pinned by remember(id) { mutableStateOf(original?.pinned ?: false) }
    var media by remember(id) { mutableStateOf(original?.media ?: emptyList()) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    var voice by remember(id) { mutableStateOf(VoiceDraft.NONE) }
    val recording = voice != VoiceDraft.NONE
    // 這次上傳了沒存的照片、語音：面板關掉時刪掉（不然留在 Storage 裡，共用的對方還列得出來）
    val uploads = rememberUploads(vm, id)
    val widgetPlaced = rememberMemoWidgetPlaced()
    val context = LocalContext.current
    // 實際上存成共用還是私人：沒配對只能私人，對方的一定是共用。新上傳的照片、語音也跟著放到對的地方
    val nowShared = if (mine) shared && canShare else true
    val partner = s.partnerName
    val hasContent = title.isNotBlank() || body.isNotBlank() || media.isNotEmpty()
    // 寫了還沒儲存（或正在錄音）：滑掉面板、按返回前先問
    tw.xiaoliangkou.app.ui.SheetDraftGuard(recording || title != (original?.title ?: "") || body != (original?.body ?: "") || media != original?.media.orEmpty())

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    original == null -> "新增備忘錄 📝"
                    mine -> "編輯備忘錄 ✏️"
                    else -> "$partner 的備忘錄 💌"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (original != null) {
                val at = Fmt.rel(original.time)
                Text(
                    // 「剛剛更新」、「昨天 22:30 更新」
                    "${if (mine) "你" else partner}寫的 · $at${if (at.last().isDigit()) " " else ""}更新",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 寫在便利貼上：顏色跟著換
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(noteColor(color)).padding(vertical = 4.dp)) {
            NoteField(title, { title = it.take(Limits.MEMO_TITLE) }, "標題", "標題", MaterialTheme.typography.titleMedium, singleLine = true)
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            NoteField(body, { body = it.take(Limits.MEMO_BODY) }, "內容", "寫點什麼…\n例如：要買的東西、想去的地方", MaterialTheme.typography.bodyLarge, minLines = 5)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("便利貼顏色", style = MaterialTheme.typography.labelLarge)
            MemoColorPicker(color) { color = it }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("照片和語音", style = MaterialTheme.typography.labelLarge)
            MediaPickerRow(
                vm, shared = nowShared, onAdded = { uploads.added += it; media = media + it }, remaining = Limits.MEDIA - media.size,
                onVoiceChange = { voice = it },
            )
            MediaThumbs(media, vm, size = 76.dp, onRemove = { r -> media = media - r })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("誰看得到", style = MaterialTheme.typography.labelLarge)
            when {
                !mine -> Note("這是 $partner 共用的備忘錄，你也可以一起改；只有 $partner 可以刪除或改成只有自己看得到。", "💌")
                !canShare -> Note("還沒配對，備忘錄只有你看得到。", "🔒")
                else -> {
                    Segmented(listOf("shared" to "👫 共用", "private" to "🔒 只有我"), if (shared) "shared" else "private") { shared = it == "shared" }
                    Text(
                        if (shared) "$partner 也看得到、也可以一起改" else "只有你看得到，$partner 不會知道有這則備忘錄",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    // 照片、語音放的地方不一樣：儲存時 App 會搬過去
                    if (original != null && original.shared != shared && original.media.isNotEmpty()) {
                        Note(if (shared) "儲存時會把照片和語音一起分享給 $partner。" else "儲存時會把照片和語音搬到只有你看得到的地方，$partner 就看不到了。", "📦")
                    }
                }
            }
        }

        ToggleRow(
            "放在桌面小工具", pinned,
            subtitle = if (nowShared && s.couple != null) "兩個人桌面上的「備忘錄」小工具都會顯示" else "桌面上的「備忘錄」小工具會顯示這則",
            emoji = "📌",
        ) { pinned = it }
        // 桌面上還沒有備忘錄小工具：放在桌面也看不到，順便加
        if (pinned && !widgetPlaced) PinMemoWidgetButton(vm, context)

        CuteButton(voice.saveLabel ?: "儲存", Modifier.fillMaxWidth(), enabled = hasContent && !recording && !vm.busy) {
            val list = media
            // 送出儲存的就不能刪了（可能已經存進資料庫）
            uploads.kept += list.map { it.path }
            vm.act(if (original == null) "已新增備忘錄 📝" else "已儲存 📝", hearts = original == null && nowShared, onDone = close) {
                it.saveMemo(id, title.trim(), body.trim(), list, nowShared, pinned, color)
            }
        }
        if (mine && original != null) {
            if (!confirmDelete) {
                SoftButton("刪除", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy) { confirmDelete = true }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("先不要", Modifier.weight(1f)) { confirmDelete = false }
                    CuteButton("確定刪除", Modifier.weight(1f), color = Cute.colors.bad, contentColor = Color.White, enabled = !vm.busy) {
                        vm.act("已刪除", onDone = close) { it.deleteMemo(original.id) }
                    }
                }
            }
        }
    }
}

/** 便利貼上的輸入框：沒有框線，直接寫在紙上。[label] 給讀螢幕用（打了字之後提示字就不見了） */
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, style: TextStyle, singleLine: Boolean = false, minLines: Int = 1) {
    val clear = Color.Transparent
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        textStyle = style,
        placeholder = { Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = clear, unfocusedContainerColor = clear, disabledContainerColor = clear,
            focusedIndicatorColor = clear, unfocusedIndicatorColor = clear, disabledIndicatorColor = clear,
        ),
    )
}

@Composable
private fun MemoColorPicker(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
        MEMO_COLORS.forEach { c ->
            val on = c.key == selected
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .border(2.5.dp, if (on) c.dot else Color.Transparent, CircleShape)
                    .bouncyClick(role = Role.RadioButton) { onPick(c.key) }
                    .semantics { contentDescription = c.label; this.selected = on }
                    .padding(6.dp).clip(CircleShape).background(c.dot),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 桌面小工具

/** 桌面上有沒有放「備忘錄」小工具：回到 App 時再看一次（可能剛去桌面加好） */
@Composable
private fun rememberMemoWidgetPlaced(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var placed by remember { mutableStateOf(MemoWidget.isPlaced(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) placed = MemoWidget.isPlaced(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return placed
}

/** 把「備忘錄」小工具加到桌面（桌面不支援直接加入時，說明怎麼手動加） */
private fun pinMemoWidget(vm: AppViewModel, context: android.content.Context) {
    if (Widgets.pin(context, MemoWidget::class.java)) vm.toast("跟著畫面上的提示放到桌面就好了 ✨")
    else vm.toast("這個桌面不能直接加入，請長按桌面空白處 › 小工具 › 小倆口")
}

@Composable
private fun PinMemoWidgetButton(vm: AppViewModel, context: android.content.Context) =
    SoftButton("把備忘錄小工具加到桌面", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.AddToHomeScreen) { pinMemoWidget(vm, context) }

// ---------------------------------------------------------------- 桌面小工具頁的預覽

/** 「桌面小工具」頁：備忘錄小工具的預覽（用真的小工具版面）和「加到桌面」 */
@Composable
fun MemoWidgetCard(s: AppState, vm: AppViewModel) {
    val context = LocalContext.current
    val snap = remember(s) { MemoWidgetSnapshot.of(s) }
    val thumbs by produceState(emptyMap<String, Bitmap>(), snap) { value = MemoWidget.thumbs(snap, vm.container.media) }
    val views = remember(snap, thumbs) { MemoWidget.render(context, snap, PREVIEW_W, PREVIEW_H, thumbs, interactive = false) }
    CuteCard {
        Text("備忘錄", style = MaterialTheme.typography.titleMedium)
        Text(
            "放在桌面的備忘錄（最多 3 則），有照片會顯示第一張。可以縮成 2×1 只看第一則，也可以拉大",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    runCatching { frame.addView(views.apply(frame.context, frame)) }
                },
                modifier = Modifier.width(PREVIEW_W.dp).height(PREVIEW_H.dp),
            )
        }
        CuteButton("加到桌面", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.AddToHomeScreen) { pinMemoWidget(vm, context) }
    }
}

private const val PREVIEW_W = 260f
private const val PREVIEW_H = 190f
