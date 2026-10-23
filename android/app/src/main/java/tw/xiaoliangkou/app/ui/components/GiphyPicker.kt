package tw.xiaoliangkou.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphyException
import tw.xiaoliangkou.app.data.GiphyItem
import tw.xiaoliangkou.app.data.GiphyKind
import tw.xiaoliangkou.app.data.GiphyQuery
import tw.xiaoliangkou.app.data.GiphySearcher
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.theme.Cute
import kotlin.coroutines.cancellation.CancellationException

/**
 * 選一張 GIPHY 貼圖：全螢幕的視窗，心情頁、安撫面板都用這一個（面板裡放不下會捲動的格子）。
 * 上面切換貼圖／GIF、搜尋、快速關鍵字，下面是結果和「Powered by GIPHY」。
 * [suggestion] 是依選的心情、安撫先幫忙查的（中文標籤 to 英文關鍵字），沒有就是熱門的。
 * 免費的 key 一小時只能查 100 次：打字停下來才查、查過的頁面不重查（[Giphy] 記在記憶體裡）、捲到底才載下一頁。
 * 還沒有 key 先教怎麼申請；示範模式不搜尋 GIPHY（示範的貼圖一樣從 GIPHY 載入），還沒配對不會連到 GIPHY。[searcher] 測試時換成假的。
 * 這是另一個視窗：App 下面的提示（vm.toast）會被蓋住，這裡的訊息都直接寫在畫面上
 */
@Composable
fun GiphyPickerDialog(
    s: AppState,
    vm: AppViewModel,
    suggestion: Pair<String, String>?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    searcher: GiphySearcher = vm.container.giphy,
) {
    val prefs by vm.prefs.collectAsState()
    val recent = prefs?.recentStickers.orEmpty()
    val pick: (String) -> Unit = { id ->
        // 視窗馬上就關了：記到「最近用過」交給 ViewModel 做完
        vm.viewModelScope.launch { runCatching { vm.container.addRecentSticker(id) } }
        onPick(id)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("選一張貼圖", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "關閉") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val key = s.giphyKey
                when {
                    !s.paired -> PickerState("💞", "配對後才能用 GIPHY 貼圖", "兩個人共用一個 GIPHY 的 key，配對好再來選吧")
                    s.demo -> DemoStickers(recent, pick)
                    !s.stickersReady -> PickerState("📱", stickersUpgradeHint(s), "步驟在「我的 › Supabase 與帳號」，兩個人其中一個做就好")
                    key == null -> SetupScroll { GiphySetupCard(s, vm, searcher) }
                    else -> GiphySearch(s, vm, key, suggestion, recent, searcher, pick)
                }
            }
            // GIPHY 的規定：用到 API 的地方一定要看得到「Powered by GIPHY」
            GiphyAttribution(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 10.dp))
        }
    }
}

/** 資料庫還存不了貼圖時的那一句：還沒跑過 upgrade-v2.sql 的 0.1 資料庫不是「再」執行一次 */
fun stickersUpgradeHint(s: AppState) = if (s.schemaOutdated) "執行 upgrade-v2.sql 就能用 GIPHY 貼圖" else "再執行一次 upgrade-v2.sql 就能用 GIPHY 貼圖"

/** 官方的「Powered by GIPHY」標誌：淺色、深色背景各一張 */
@Composable
fun GiphyAttribution(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painterResource(if (dark) R.drawable.giphy_attribution_dark else R.drawable.giphy_attribution_light), "Powered by GIPHY",
        // 官方標誌包的大張（641×71）：16 dp 高在高解析度的手機上也清楚
        modifier.height(16.dp).aspectRatio(641f / 71f), contentScale = ContentScale.Fit,
    )
}

/** 一組搜尋（[q]：種類＋關鍵字）載到哪裡了 */
private class Results(val q: GiphyQuery) {
    var items by mutableStateOf<List<GiphyItem>>(emptyList())
    var next by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(false)
    /** 第一頁查完了（有結果、沒結果或出錯） */
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<GiphyException.Reason?>(null)
}

@Composable
private fun GiphySearch(
    s: AppState,
    vm: AppViewModel,
    key: String,
    suggestion: Pair<String, String>?,
    recent: List<String>,
    searcher: GiphySearcher,
    pick: (String) -> Unit,
) {
    val start = suggestion?.second.orEmpty()
    var kind by remember { mutableStateOf(GiphyKind.STICKERS) }
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(start) }
    var showRecent by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    // 種類、關鍵字、key 沒變就是同一組：畫面重畫不會再查一次
    val results = remember(key, kind, query, attempt) { Results(GiphyQuery(key, kind, query)) }
    // 換了關鍵字從最上面開始看
    val grid = remember(results) { LazyGridState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    suspend fun load(r: Results, offset: Int) {
        r.loading = true
        r.error = null
        try {
            val page = searcher.page(r.q.copy(offset = offset))
            // 照 GIPHY 給的順序接在後面（不能重新排序、挑掉）
            r.items = r.items + page.items
            r.next = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            r.error = (e as? GiphyException)?.reason ?: GiphyException.Reason.SERVER
        } finally {
            r.loading = false
        }
        r.loaded = true
    }

    fun choose(q: String) {
        showRecent = false
        text = ""
        query = q
    }

    LaunchedEffect(results, showRecent) {
        if (showRecent) return@LaunchedEffect
        if (!results.loaded) load(results, 0)
        // 捲到最後一格才載下一頁（最多 96 張）；出錯就停，按「重試」再載。
        // 要真的捲過：平板、很高的螢幕不用捲就看得到最後一格，不會自己一頁一頁載下去（改按「載入更多」）
        snapshotFlow {
            val scrolled = grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0
            Triple(scrolled, grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, results.items.size)
        }.collect { (scrolled, last, size) ->
            val next = results.next
            if (scrolled && size > 0 && last >= size - 1 && next != null && !results.loading && results.error == null) load(results, next)
        }
    }
    // 打字時等停下來才查（一小時只有 100 次）；還在選注音的字（「ㄅㄠˋ」）不查，按搜尋鍵才查
    LaunchedEffect(text) {
        val q = Giphy.query(text)
        if (q.isEmpty() || Giphy.hasZhuyin(q)) return@LaunchedEffect
        delay(Giphy.DEBOUNCE_MS)
        showRecent = false
        query = q
    }

    // key 不能用了：直接換一個（換了、或同一個 key 又能用了，就再查一次）
    if (results.error == GiphyException.Reason.BAD_KEY) {
        SetupScroll { GiphySetupCard(s, vm, searcher, badKey = true) { attempt++ } }
        return
    }

    val chips = remember(suggestion) { stickerChips(suggestion) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf(GiphyKind.STICKERS.path to "貼圖", GiphyKind.GIFS.path to "GIF"), kind.path) { k ->
                showRecent = false
                kind = GiphyKind.entries.first { it.path == k }
            }
            SearchBar(
                text,
                onChange = { text = it.take(Giphy.MAX_QUERY) },
                onSearch = {
                    keyboard?.hide()
                    Giphy.query(text).takeIf { it.isNotEmpty() }?.let { showRecent = false; query = it }
                },
                onClear = { choose(start) },
            )
        }
        // 膠囊都不放表情：一樣高，排起來才整齊
        LazyRow(
            Modifier.padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recent.isNotEmpty()) item { ChoiceChip("最近用過", showRecent) { showRecent = true; text = "" } }
            items(chips) { (label, q) -> ChoiceChip(label, !showRecent && text.isEmpty() && query == q) { choose(q) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            val error = results.error
            when {
                showRecent -> IdGrid(recent, { i -> "最近用過的貼圖 ${i + 1}" }, pick)
                results.items.isEmpty() && error == GiphyException.Reason.RATE_LIMITED ->
                    PickerState("⏳", error.text, if (recent.isNotEmpty()) "可以先從「最近用過」選一張" else null)
                results.items.isEmpty() && error != null -> PickerState(
                    if (error == GiphyException.Reason.NETWORK) "📡" else "🛠️", error.text,
                    if (error == GiphyException.Reason.NETWORK) "確認網路之後再試一次" else null,
                ) { SoftButton("重試", icon = Icons.Rounded.Refresh) { attempt++ } }
                results.items.isEmpty() && results.loaded -> PickerState("🔍", "找不到，換個關鍵字試試")
                else -> ResultGrid(results, grid, rememberAnimationsOff(), pick) {
                    results.next?.let { offset -> scope.launch { if (!results.loading) load(results, offset) } }
                }
            }
        }
    }
}

/**
 * 快速關鍵字：依心情、安撫推薦的放最前面，再來是熱門（沒有關鍵字）和常用的。
 * 跟推薦的同一個關鍵字、或同一個名字的不再放一次（選了「加油」時推薦的是 you can do it，常用的「加油」是 you got this）
 */
internal fun stickerChips(suggestion: Pair<String, String>?): List<Pair<String, String>> =
    listOfNotNull(suggestion, "熱門" to "") + Catalog.stickerQueries.filter { it.second != suggestion?.second && it.first != suggestion?.first }

@Composable
private fun SearchBar(text: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(Cute.colors.card)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("搜尋（英文的結果比較多）", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
            },
        )
        if (text.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp).clip(CircleShape).bouncyClick(onClick = onClear).padding(8.dp),
            )
        }
    }
}

/** 搜尋結果：網址照 API 給的顯示；系統關掉動畫時用不會動的那張。[more] 載下一頁（重試、載入更多） */
@Composable
private fun ResultGrid(results: Results, grid: LazyGridState, still: Boolean, pick: (String) -> Unit, more: () -> Unit) {
    PickerGrid(grid) {
        items(results.items.size) { i ->
            val item = results.items[i]
            GiphyCell(item.description, { pick(item.id) }) { UrlImage(if (still) item.still else item.animated) }
        }
        // 第一頁還沒回來：先放淡淡的格子
        if (results.items.isEmpty() && !results.loaded) items(12) {
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(STICKER_CELL.copy(alpha = 0.6f)))
        }
        if (results.items.isNotEmpty() && results.loading) wide { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { LoadingDots() } }
        val error = results.error
        if (results.items.isNotEmpty() && error != null) wide {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TidyText(error.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != GiphyException.Reason.RATE_LIMITED) SoftButton("重試", icon = Icons.Rounded.Refresh, small = true, onClick = more)
            }
        }
        // 還有下一頁：捲到這裡就會自己載；不用捲就看得到這裡（平板、很高的螢幕）時按了才載
        if (results.items.isNotEmpty() && results.next != null && !results.loading && error == null) wide {
            SoftButton("載入更多", Modifier.fillMaxWidth(), small = true, onClick = more)
        }
    }
}

/** 用 id 顯示的貼圖（最近用過、示範用的）：不用查 GIPHY；載好了才有 [label] */
@Composable
private fun IdGrid(ids: List<String>, label: (Int) -> String, pick: (String) -> Unit) {
    PickerGrid(rememberLazyGridState()) {
        items(ids.size) { i -> GiphyCell(null, { pick(ids[i]) }) { StickerImage(ids[i], Modifier.fillMaxSize(), label(i)) } }
    }
}

@Composable
private fun PickerGrid(state: LazyGridState, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 佔滿一整排（載入中、出錯） */
private fun LazyGridScope.wide(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun GiphyCell(description: String?, onClick: () -> Unit, image: @Composable () -> Unit) {
    Box(
        // 跟選好的貼圖一樣的淺粉紅底：透明背景的貼圖在深色模式也看得清楚，淺色模式也看得出一格一格
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(STICKER_CELL)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .bouncyClick(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { image() }
}

@Composable
private fun UrlImage(url: String) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(url).build() },
        contentDescription = null,
        imageLoader = context.container.stickerImages,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

/** 示範模式不能搜尋 GIPHY（不呼叫 API）：先用示範的貼圖（和最近用過的）試試；圖片一樣是從 GIPHY 載入的 */
@Composable
private fun DemoStickers(recent: List<String>, pick: (String) -> Unit) {
    val ids = remember(recent) { (listOf(DemoRepository.STICKER) + recent).distinct() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 標題、說明分開：都是短短的幾行，換行才換得漂亮
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🧪")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TidyText("示範模式不能搜尋 GIPHY", style = MaterialTheme.typography.titleSmall)
                TidyText(
                    "用你們的 Supabase 登入、設定好 key，就能搜尋各種貼圖。先用這張試試看 👇（示範的貼圖一樣是從 GIPHY 載入的）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IdGrid(ids, { i -> if (i == 0) "示範貼圖" else "最近用過的貼圖 $i" }, pick)
    }
}

/** 沒有結果、出錯、不能用的時候：一個表情、一句話，可能有按鈕 */
@Composable
private fun PickerState(emoji: String, title: String, sub: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.displayMedium.unscaled())
        TidyText(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (sub != null) TidyText(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
private fun SetupScroll(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) { content() }
}

// ---------------------------------------------------------------- GIPHY 的 key

/**
 * 還沒有 GIPHY 的 key（或 key 不能用了）：為什麼要、怎麼申請、貼上 key；對方可能已經設定了，可以再檢查一次。
 * [onRetry]：key 存好了、或同一個 key 又能用了，請搜尋再查一次（key 沒變的話畫面不會自己重查）
 */
@Composable
fun GiphySetupCard(s: AppState, vm: AppViewModel, searcher: GiphySearcher, badKey: Boolean = false, onRetry: () -> Unit = {}) {
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmojiBubble(if (badKey) "😵" else "🔑", Cute.colors.butter.copy(alpha = 0.35f), 44.dp)
            TidyText(if (badKey) "GIPHY 的 key 不能用了" else "先設定 GIPHY 的 key", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        // 說明用整個寬度：字放大時一句一行，不會被表情擠得東一段西一段
        TidyText(
            if (badKey) "可能被停用了，或沒有複製完整。照下面的步驟換一個新的"
            else "貼圖來自 GIPHY，需要一個免費的 key。只要設定一次，你和 ${s.partnerName} 都能用",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GiphyKeySteps()
        GiphyDashboardButton()
        GiphyKeyForm(s, vm, searcher, onSaved = onRetry)
        GiphyRecheck(s, vm, searcher, badKey, onRetry)
    }
}

/** 「Supabase 與帳號」裡的 GIPHY 貼圖：設定好了沒、換一個 key、移除（兩個人共用同一個 key） */
@Composable
fun GiphyKeyCard(s: AppState, vm: AppViewModel, searcher: GiphySearcher) {
    val key = s.giphyKey
    var removing by remember { mutableStateOf(false) }
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBubble(Icons.Rounded.GifBox, Cute.colors.lavender, Cute.colors.lavender.copy(alpha = 0.25f), 44.dp, 24.dp)
            TidyText("GIPHY 貼圖", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (key != null) Pill("設定好了 ✓", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            else Pill("還沒設定", Cute.colors.butter.copy(alpha = 0.4f))
        }
        TidyText(
            "心情和安撫可以附上會動的貼圖。兩個人共用同一個 key：設定一次，你和 ${s.partnerName} 的手機都能用",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!s.stickersReady) {
            // 0.1 的資料庫還沒跑過 upgrade-v2.sql：不是「再」執行一次
            Note("資料庫還存不了貼圖和 key：照上面的步驟${if (s.schemaOutdated) "" else "再"}執行一次 upgrade-v2.sql 就能設定", "📱")
            return@CuteCard
        }
        if (key != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("目前的 key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 只露出開頭幾個字，認得出是哪一個就好
                Text(masked(key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        } else GiphyKeySteps()
        GiphyDashboardButton()
        GiphyKeyForm(s, vm, searcher, if (key != null) "換成新的 key" else "GIPHY 的 API key", if (key != null) "換成這個 key" else "儲存")
        if (key != null) SoftButton("移除 key", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy) { removing = true }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("移除 GIPHY 的 key？") },
        text = { Text("你和 ${s.partnerName} 都不能再選新的貼圖，已經送出的貼圖還是看得到。之後可以再設定一次。") },
        confirmButton = {
            TextButton(onClick = { removing = false; vm.act("已移除 GIPHY 的 key") { it.setGiphyKey(null) } }) { Text("移除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } },
    )
}

/** key 只顯示前 4 個字 */
private fun masked(key: String) = key.take(4) + "•••"

/** 到 GIPHY Developers 申請 key 的三個步驟（跟網站上的按鈕一樣；按鈕的名字用不換行的空白連起來，不會被拆成兩行） */
@Composable
fun GiphyKeySteps() {
    listOf(
        "登入 GIPHY Developers（免費註冊），按「Create\u00A0an\u00A0API\u00A0Key」",
        "選「API」→「Next\u00A0Step」，填 App 名稱、勾選同意，按「Create\u00A0API\u00A0Key」",
        "複製那一串 key，貼到下面按「儲存」",
    ).forEachIndexed { i, t ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            TidyText(t, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun GiphyDashboardButton() {
    val uriHandler = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    SoftButton("打開 GIPHY Developers", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew) {
        failed = runCatching { uriHandler.openUri(Giphy.DASHBOARD) }.isFailure
    }
    if (failed) TidyText("打不開瀏覽器，請到 developers.giphy.com 申請", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/**
 * 貼上 key → 先用這個 key 查一次熱門貼圖（只花一次額度），能用才存進你們的 Supabase，存好了呼叫 [onSaved]。
 * 錯誤直接寫在欄位下面（在選貼圖的視窗裡時，App 的提示會被蓋住）
 */
@Composable
fun GiphyKeyForm(s: AppState, vm: AppViewModel, searcher: GiphySearcher, label: String = "GIPHY 的 API key", button: String = "儲存", onSaved: () -> Unit = {}) {
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CuteField(
        key, { key = it.filterNot(Char::isWhitespace).take(128); error = null }, label,
        password = true, isError = error != null, supporting = error ?: "存在你們的 Supabase，只用來搜尋貼圖",
    )
    CuteButton(if (checking) "檢查中…" else button, Modifier.fillMaxWidth(), icon = Icons.Rounded.Key, enabled = key.isNotEmpty() && !checking) {
        val k = key
        val repo = vm.repo.value ?: return@CuteButton
        if (!Giphy.isValidKey(k)) {
            error = RemoteRepository.BAD_GIPHY_KEY
            return@CuteButton
        }
        checking = true
        error = null
        // 按了「儲存」就做完，視窗關掉也一樣
        vm.viewModelScope.launch {
            try {
                val problem = keyCheck(searcher, k)?.let(::keyProblem)
                if (problem != null) {
                    error = problem
                    return@launch
                }
                repo.setGiphyKey(k)
                key = ""
                vm.toast("設定好了 🎉 ${s.partnerName} 的手機也能用")
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? AppException)?.message ?: "存不進去：${e.message ?: e.javaClass.simpleName}"
            } finally {
                checking = false
            }
        }
    }
}

/** 用這個 key 試查一次熱門貼圖：能用是 null，不能用是原因 */
private suspend fun keyCheck(searcher: GiphySearcher, key: String): GiphyException.Reason? = try {
    searcher.checkKey(key)
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    (e as? GiphyException)?.reason ?: GiphyException.Reason.SERVER
}

/** 試查失敗的說明；這個小時用完了的 key 還是好的，照樣存 */
private fun keyProblem(reason: GiphyException.Reason): String? = when (reason) {
    GiphyException.Reason.BAD_KEY -> "GIPHY 說這個 key 不能用：確認選的是「API」，整串都有複製到"
    GiphyException.Reason.RATE_LIMITED -> null
    GiphyException.Reason.NETWORK -> "連不到 GIPHY，確認網路後再按一次「儲存」"
    GiphyException.Reason.SERVER -> reason.text
}

/**
 * 對方可能已經設定好了：重新讀一次配對的資料（結果寫在按鈕下面）。
 * key 不能用了（[badKey]）、對方也還沒換：同一個 key 再問 GIPHY 一次，又能用了就 [onRetry]，還是不行就說要換一個新的
 */
@Composable
private fun GiphyRecheck(s: AppState, vm: AppViewModel, searcher: GiphySearcher, badKey: Boolean, onRetry: () -> Unit) {
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    BesideOrBelow({ TidyText("${s.partnerName} 已經設定好了？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        SoftButton(if (checking) "檢查中…" else "再檢查一次", icon = Icons.Rounded.Refresh, small = true, enabled = !checking) {
            val repo = vm.repo.value ?: return@SoftButton
            checking = true
            note = null
            vm.viewModelScope.launch {
                note = try {
                    val before = repo.state.value.giphyKey
                    repo.refreshAll()
                    val now = repo.state.value.giphyKey
                    when {
                        now == null -> "${s.partnerName} 也還沒設定，照上面的步驟設定一下吧"
                        badKey && now == before -> when (keyCheck(searcher, now)) {
                            // 這個小時用完了的 key 還是好的：回到搜尋，那裡會說等一下再試
                            null, GiphyException.Reason.RATE_LIMITED -> { onRetry(); null }
                            GiphyException.Reason.BAD_KEY -> "還是原來那個 key：請照上面的步驟換一個新的"
                            GiphyException.Reason.NETWORK -> "連不到 GIPHY，確認網路後再檢查一次"
                            GiphyException.Reason.SERVER -> GiphyException.Reason.SERVER.text
                        }
                        // 有了（或換了新的）：這張卡片會自己換成搜尋
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    (e as? AppException)?.message ?: "連不到 Supabase，等一下再試"
                } finally {
                    checking = false
                }
            }
        }
    }
    note?.let { TidyText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** 說明在左、按鈕在右；說明放不下一行（字放大、名字很長）時按鈕移到說明下面靠右，說明不會被擠成好幾行 */
@Composable
private fun BesideOrBelow(text: @Composable () -> Unit, action: @Composable () -> Unit) {
    Layout(content = { text(); Box { action() } }, modifier = Modifier.fillMaxWidth()) { (textM, actionM), c ->
        val gap = 8.dp.roundToPx()
        val w = c.maxWidth
        val a = actionM.measure(Constraints(maxWidth = w))
        val beside = textM.maxIntrinsicWidth(Constraints.Infinity) + gap + a.width <= w
        val t = textM.measure(Constraints(maxWidth = if (beside) w - a.width - gap else w))
        val h = if (beside) maxOf(t.height, a.height) else t.height + gap + a.height
        layout(w, h) {
            if (beside) {
                t.placeRelative(0, (h - t.height) / 2)
                a.placeRelative(w - a.width, (h - a.height) / 2)
            } else {
                t.placeRelative(0, 0)
                a.placeRelative(w - a.width, t.height + gap)
            }
        }
    }
}
