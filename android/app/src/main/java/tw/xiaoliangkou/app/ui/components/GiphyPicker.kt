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
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** 格子的底色：淺淺的，透明背景的貼圖在深色模式也看得清楚 */
private val CELL = Color(0xFFFFF4F7)

/**
 * 選一張 GIPHY 貼圖：全螢幕的視窗，心情頁、安撫面板都用這一個（面板裡放不下會捲動的格子）。
 * 上面切換貼圖／GIF、搜尋、快速關鍵字，下面是結果和「Powered by GIPHY」。
 * [suggestion] 是依選的心情、安撫先幫忙查的（中文標籤 to 英文關鍵字），沒有就是熱門的。
 * 免費的 key 一小時只能查 100 次：打字停下來才查、查過的頁面不重查（[Giphy] 記在記憶體裡）、捲到底才載下一頁。
 * 還沒有 key 先教怎麼申請；示範模式、還沒配對不會連到 GIPHY。[searcher] 測試時換成假的
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
                    s.stickersOutdated -> PickerState("📱", "再執行一次 upgrade-v2.sql 就能用 GIPHY 貼圖", "步驟在「我的 › Supabase 與帳號」，兩個人其中一個做就好")
                    key == null -> SetupScroll { GiphySetupCard(s, vm, searcher) }
                    else -> GiphySearch(s, vm, key, suggestion, recent, searcher, pick)
                }
            }
            // GIPHY 的規定：用到 API 的地方一定要看得到「Powered by GIPHY」
            GiphyAttribution(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 10.dp))
        }
    }
}

/** 官方的「Powered by GIPHY」標誌：淺色、深色背景各一張 */
@Composable
fun GiphyAttribution(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painterResource(if (dark) R.drawable.giphy_attribution_dark else R.drawable.giphy_attribution_light), "Powered by GIPHY",
        modifier.height(16.dp).aspectRatio(200f / 22f), contentScale = ContentScale.Fit,
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
            r.loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            r.error = (e as? GiphyException)?.reason ?: GiphyException.Reason.SERVER
            r.loaded = true
        } finally {
            r.loading = false
        }
    }

    LaunchedEffect(results, showRecent) {
        if (showRecent) return@LaunchedEffect
        if (!results.loaded) load(results, 0)
        // 捲到最後一格才載下一頁（最多 96 張）；出錯就停，按「重試」再載
        snapshotFlow { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to results.items.size }.collect { (last, size) ->
            val next = results.next
            if (size > 0 && last >= size - 1 && next != null && !results.loading && results.error == null) load(results, next)
        }
    }
    // 打字時等停下來才查（一小時只有 100 次）
    LaunchedEffect(text) {
        val q = Giphy.query(text)
        if (q.isEmpty()) return@LaunchedEffect
        delay(Giphy.DEBOUNCE_MS)
        showRecent = false
        query = q
    }

    // key 不能用了：直接換一個
    if (results.error == GiphyException.Reason.BAD_KEY) {
        SetupScroll { GiphySetupCard(s, vm, searcher, badKey = true) }
        return
    }

    val chips = remember(suggestion) { listOfNotNull(suggestion) + Catalog.stickerQueries.filter { it.second != suggestion?.second } }
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
                onClear = { text = ""; query = start },
            )
        }
        LazyRow(
            Modifier.padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recent.isNotEmpty()) item { ChoiceChip("最近用過", showRecent, "🕘") { showRecent = true; text = "" } }
            items(chips) { (label, q) ->
                ChoiceChip(label, !showRecent && text.isEmpty() && query == q) { showRecent = false; text = ""; query = q }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            val error = results.error
            when {
                showRecent -> IdGrid(recent, { i -> "最近用過的貼圖 ${i + 1}" }, pick)
                results.items.isEmpty() && error == GiphyException.Reason.RATE_LIMITED ->
                    PickerState("⏳", GiphyException.Reason.RATE_LIMITED.text, if (recent.isNotEmpty()) "可以先從「最近用過」選一張" else null)
                results.items.isEmpty() && error != null -> PickerState(
                    if (error == GiphyException.Reason.NETWORK) "📡" else "🛠️", error.text,
                    if (error == GiphyException.Reason.NETWORK) "確認網路之後再試一次" else null,
                ) { SoftButton("重試", icon = Icons.Rounded.Refresh) { attempt++ } }
                results.items.isEmpty() && results.loaded -> PickerState("🔍", "找不到，換個關鍵字試試")
                else -> ResultGrid(results, grid, rememberAnimationsOff(), pick) {
                    results.next?.let { offset -> scope.launch { load(results, offset) } }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(text: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(Cute.colors.card)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(start = 14.dp, end = 4.dp),
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
                        Text("搜尋（英文結果較多）", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
            },
        )
        if (text.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        else Spacer(Modifier.width(10.dp))
    }
}

/** 搜尋結果：網址照 API 給的顯示；系統關掉動畫時用不會動的那張 */
@Composable
private fun ResultGrid(results: Results, grid: LazyGridState, still: Boolean, pick: (String) -> Unit, retry: () -> Unit) {
    PickerGrid(grid) {
        items(results.items.size) { i ->
            val item = results.items[i]
            GiphyCell(item.description, { pick(item.id) }) { UrlImage(if (still) item.still else item.animated) }
        }
        // 第一頁還沒回來：先放淡淡的格子
        if (results.items.isEmpty() && results.loading) items(12) { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) }
        if (results.items.isNotEmpty() && results.loading) wide { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { LoadingDots() } }
        val error = results.error
        if (results.items.isNotEmpty() && error != null) wide {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TidyText(error.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != GiphyException.Reason.RATE_LIMITED) SoftButton("重試", icon = Icons.Rounded.Refresh, small = true, onClick = retry)
            }
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
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(CELL)
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

/** 示範模式不會連到 GIPHY：先用示範的貼圖（和最近用過的）試試 */
@Composable
private fun DemoStickers(recent: List<String>, pick: (String) -> Unit) {
    val ids = remember(recent) { (listOf(DemoRepository.STICKER) + recent).distinct() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            Note("示範模式不能連到 GIPHY 搜尋。用你們的 Supabase 登入、設定好 GIPHY 的 key，就能找各種貼圖。先用這張試試看 👇", "🧪")
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

/**
 * 還沒有 GIPHY 的 key（或 key 不能用了）：為什麼要、怎麼申請、貼上 key。
 * [recheck]：對方可能已經設定了，重新整理一下就能用
 */
@Composable
fun GiphySetupCard(s: AppState, vm: AppViewModel, searcher: GiphySearcher, badKey: Boolean = false, recheck: Boolean = true) {
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmojiBubble(if (badKey) "😵" else "🔑", Cute.colors.butter.copy(alpha = 0.35f), 44.dp)
            Column(Modifier.weight(1f)) {
                TidyText(if (badKey) "GIPHY 的 key 不能用了" else "先設定 GIPHY 的 key", style = MaterialTheme.typography.titleMedium)
                TidyText(
                    if (badKey) "可能被停用了，或複製時少了一段。照下面的步驟換一個新的" else "GIPHY 的 API key 是免費的，只要設定一次，${s.partnerName} 的手機也能用",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GiphyKeySteps()
        GiphyDashboardButton(vm)
        GiphyKeyForm(s, vm, searcher)
        if (recheck) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TidyText("${s.partnerName} 已經設定好了？", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SoftButton("再檢查一次", icon = Icons.Rounded.Refresh, small = true, enabled = !vm.busy) {
                vm.act { r ->
                    r.refreshAll()
                    if (r.state.value.giphyKey == null) vm.toast("${s.partnerName} 也還沒設定，照上面的步驟設定一下吧")
                }
            }
        }
    }
}

/** 到 GIPHY Developers 申請 key 的三個步驟（跟網站上的按鈕一樣） */
@Composable
fun GiphyKeySteps() {
    listOf(
        "登入 GIPHY Developers（沒有帳號就免費註冊），按「Create an API Key」",
        "選「API」→「Next Step」，App 名稱填「小倆口」，勾選同意條款後按「Create API Key」",
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
fun GiphyDashboardButton(vm: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    SoftButton("打開 GIPHY Developers", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew) {
        runCatching { uriHandler.openUri(Giphy.DASHBOARD) }.onFailure { vm.toast("打不開瀏覽器，請到 developers.giphy.com 申請") }
    }
}

/** 貼上 key → 先用這個 key 查一次熱門貼圖（只花一次額度），能用才存起來 */
@Composable
fun GiphyKeyForm(s: AppState, vm: AppViewModel, searcher: GiphySearcher, onSaved: () -> Unit = {}) {
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    CuteField(
        key, { key = it.filterNot(Char::isWhitespace).take(128); error = null }, "GIPHY 的 API key",
        password = true, isError = error != null, supporting = error ?: "存在你們的 Supabase，只用來搜尋貼圖",
    )
    CuteButton(if (checking) "檢查中…" else "儲存", Modifier.fillMaxWidth(), icon = Icons.Rounded.Key, enabled = key.isNotEmpty() && !checking && !vm.busy) {
        val k = key
        if (!Giphy.isValidKey(k)) {
            error = RemoteRepository.BAD_GIPHY_KEY
            return@CuteButton
        }
        checking = true
        scope.launch {
            val problem = try {
                searcher.checkKey(k)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                keyProblem((e as? GiphyException)?.reason ?: GiphyException.Reason.SERVER)
            } finally {
                checking = false
            }
            if (problem != null) error = problem
            else vm.act("設定好了 🎉 ${s.partnerName} 的手機也能用", onDone = { key = ""; onSaved() }) { it.setGiphyKey(k) }
        }
    }
}

/** 試查失敗的說明；這個小時用完了的 key 還是好的，照樣存 */
private fun keyProblem(reason: GiphyException.Reason): String? = when (reason) {
    GiphyException.Reason.BAD_KEY -> "GIPHY 說這個 key 不能用：確認選的是「API」，整串都有複製到"
    GiphyException.Reason.RATE_LIMITED -> null
    GiphyException.Reason.NETWORK -> "連不到 GIPHY，確認網路後再按一次「儲存」"
    GiphyException.Reason.SERVER -> reason.text
}
