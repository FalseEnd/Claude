package tw.xiaoliangkou.app.ui.screens

import tw.xiaoliangkou.app.ui.components.IllustratedIcon
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.data.LatLng
import tw.xiaoliangkou.app.data.Place
import tw.xiaoliangkou.app.data.PlaceHit
import tw.xiaoliangkou.app.data.PlaceSearcher
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.service.Locator
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.drawIllustratedSymbol
import tw.xiaoliangkou.app.ui.components.findActivity
import tw.xiaoliangkou.app.ui.components.openAppSettings
import tw.xiaoliangkou.app.ui.theme.Cute

fun placeEmoji(icon: String) = when (icon) {
    "home" -> "🏠"; "work" -> "💼"; "school" -> "🏫"; "gym" -> "🏋️"; "cafe" -> "☕"; "food" -> "🍜"; "shop" -> "🛍️"; "heart" -> "💗"; else -> "📍"
}

/** 請地圖飛過去；每次都是新的物件，連按同一個按鈕也會再飛一次 */
private class FlyTo(val lat: Double, val lng: Double, val zoom: Double)

/**
 * 地圖目前看的位置。拖曳時只改欄位、不觸發重畫；切到別的分頁再回來時接著用。
 * [settled]：已經自動對準過兩個人，或使用者自己動過地圖，之後就不再自動移動。
 */
private class MapCamera(var lat: Double = Double.NaN, var lng: Double = Double.NaN, var zoom: Double = Double.NaN, var settled: Boolean = false) {
    val known get() = !lat.isNaN() && !lng.isNaN() && !zoom.isNaN()
    fun from(map: MapView) {
        val c = map.mapCenter
        lat = c.latitude; lng = c.longitude; zoom = map.zoomLevelDouble
    }
}

private val MapCameraSaver = Saver<MapCamera, DoubleArray>(
    save = { doubleArrayOf(it.lat, it.lng, it.zoom, if (it.settled) 1.0 else 0.0) },
    restore = { MapCamera(it[0], it[1], it[2], it[3] == 1.0) },
)

/** 搜尋框：輸入的字、搜尋中、結果清單、提示訊息、目前標出來的地點 */
private class MapSearchState {
    var query by mutableStateOf("")
    var busy by mutableStateOf(false)
    var results by mutableStateOf<List<PlaceHit>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var pin by mutableStateOf<PlaceHit?>(null)
}

@Composable
fun MapScreen(s: AppState, vm: AppViewModel, sheet: (SheetSpec) -> Unit, searcher: PlaceSearcher = vm.container.placeSearch) {
    val camera = rememberSaveable(saver = MapCameraSaver) { MapCamera() }
    val search = remember { MapSearchState() }
    var flyTo by remember { mutableStateOf<FlyTo?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var locating by remember { mutableStateOf(false) }
    /** 自動定位還沒好時使用者按了 📍：抓到後要飛過去 */
    var flyWhenFound by remember { mutableStateOf(false) }
    var askedAt by remember { mutableStateOf(0L) }

    /**
     * 我的位置：直接問手機（不用打開位置分享，也不會上傳）。
     * 沒權限就請使用者允許、定位關著就帶去設定；[quiet] 是打開地圖時自己抓一次，失敗也不打擾。
     */
    fun locateMe(quiet: Boolean = false, ask: () -> Unit = {}) {
        if (vm.demo != null) {
            s.myLocation?.let { if (!quiet) flyTo = FlyTo(it.lat, it.lng, 16.0) }
            return
        }
        when (Locator.status(context)) {
            Locator.Status.NO_PERMISSION -> if (!quiet) ask()
            Locator.Status.OFF -> if (!quiet) {
                vm.toast("手機的「定位」沒有打開，打開後再按一次")
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            Locator.Status.OK -> {
                if (!quiet) flyWhenFound = true
                if (!locating) {
                    locating = true
                    scope.launch {
                        try {
                            val loc = Locator.current(context)
                            if (loc != null) {
                                vm.repo.value?.setMyLocation(loc.latitude, loc.longitude)
                                if (flyWhenFound) flyTo = FlyTo(loc.latitude, loc.longitude, 16.0)
                            } else if (flyWhenFound) vm.toast("暫時抓不到位置，到窗邊或戶外再試一次")
                        } finally {
                            locating = false
                            flyWhenFound = false
                        }
                    }
                }
            }
        }
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            // 服務要重新以「定位」類型啟動，位置分享才會在背景繼續
            ShareService.start(context)
            locateMe()
        } else {
            val activity = context.findActivity()
            // 對話框根本沒出現（馬上就被拒絕）＝之前選了「不再詢問」，只能到設定打開；自己關掉對話框的話不用跳去設定
            val blocked = System.currentTimeMillis() - askedAt < 500 &&
                activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            if (blocked) {
                vm.toast("定位權限被關掉了，請在設定的「權限 › 位置」允許小倆口")
                openAppSettings(context)
            } else vm.toast("沒有定位權限，地圖找不到你在哪裡")
        }
    }
    val requestLocation = {
        askedAt = System.currentTimeMillis()
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // 打開地圖時還不知道你在哪：有權限就安靜地抓一次，地圖會自動對準
    LaunchedEffect(Unit) { if (s.myLocation == null) locateMe(quiet = true) }

    // 地圖佔畫面一半多一點，留位置給下面的標點清單
    val mapHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).coerceIn(360f, 520f).dp

    fun choose(hit: PlaceHit) {
        search.results = emptyList()
        search.message = null
        search.pin = hit
        flyTo = FlyTo(hit.lat, hit.lng, hit.zoom)
    }

    fun runSearch() {
        val q = search.query.trim()
        if (q.isEmpty() || search.busy) return
        keyboard?.hide()
        focusManager.clearFocus()
        search.busy = true
        search.message = null
        search.results = emptyList()
        val near = if (camera.known) LatLng(camera.lat, camera.lng) else s.myLocation
        scope.launch {
            try {
                val hits = searcher.search(q, near)
                when (hits.size) {
                    0 -> search.message = "找不到「$q」，試試加上縣市或路名"
                    1 -> choose(hits.first())
                    else -> search.results = hits
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                search.message = "搜尋失敗，請確認網路連線"
            } finally {
                search.busy = false
            }
        }
    }

    // 不用 LazyColumn：地圖捲出畫面時才不會被拆掉重建（位置會跑掉）
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("地圖")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("map")) {
                if (LocalInspectionMode.current) Text("地圖", Modifier.align(Alignment.Center))
                else OsmMap(
                    s, camera, flyTo, search.pin,
                    onTap = { search.results = emptyList(); focusManager.clearFocus() },
                    onLongPress = { lat, lng -> sheet(SheetSpec.PlaceEdit(null, lat, lng)) },
                )
                Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(
                        onClick = {
                            val loc = s.partnerStatus.location?.takeIf { s.partnerSharing?.locationLive == true }
                            if (loc != null) flyTo = FlyTo(loc.lat, loc.lng, 16.0) else vm.toast("${s.partnerName} 目前沒有分享位置")
                        },
                        containerColor = Cute.colors.card,
                    ) { IllustratedIcon("💗", Modifier.size(24.dp), "查看對方的位置") }
                    SmallFloatingActionButton(
                        onClick = { locateMe(ask = requestLocation) },
                        containerColor = Cute.colors.card,
                    ) {
                        if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cute.colors.me)
                        else Icon(Icons.Rounded.MyLocation, "我的位置", tint = Cute.colors.me)
                    }
                    FloatingActionButton(
                        onClick = {
                            val c = s.myLocation ?: s.partnerStatus.location?.let { LatLng(it.lat, it.lng) }
                            sheet(SheetSpec.PlaceEdit(null, c?.lat ?: 25.0330, c?.lng ?: 121.5654))
                        },
                        containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp),
                    ) { Icon(Icons.Rounded.Add, "新增標點", tint = MaterialTheme.colorScheme.onPrimary) }
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 84.dp, bottom = 12.dp)) {
                    Pill("長按地圖也能新增標點", Cute.colors.card.copy(alpha = 0.92f), maxLines = 2)
                }
                // 搜尋框放最後，蓋在地圖和按鈕上面
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                    MapSearchBar(search, onSearch = { runSearch() })
                    val pin = search.pin
                    when {
                        search.results.isNotEmpty() -> SearchResults(search.results) { choose(it) }
                        search.message != null -> SearchMessage(search.message.orEmpty()) { search.message = null }
                        pin != null -> PinCard(pin, onAdd = { sheet(SheetSpec.PlaceEdit(null, pin.lat, pin.lng, pin.title)) }, onClose = { search.pin = null })
                    }
                }
            }
            Text(
                "地圖資料 © OpenStreetMap 貢獻者", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
            )
        }
        PartnerLine(s, vm)
        SectionTitle("標點與通知", Modifier.padding(horizontal = 16.dp), trailing = "${s.places.size} 個")
        s.places.forEach { p -> key(p.id) { PlaceRow(s, p) { sheet(SheetSpec.PlaceEdit(p.id, p.lat, p.lng)) } } }
        Box(Modifier.padding(horizontal = 16.dp)) {
            Note("標點兩個人都看得到。你新增會偵測 ${s.partnerName} 的標點時，${s.partnerName} 一定會收到通知，也能隨時修改或關閉位置分享。", "🛡️")
        }
    }
}

@Composable
private fun MapSearchBar(search: MapSearchState, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).shadow(6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            .background(Cute.colors.card).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = search.query,
            onValueChange = { search.query = it.take(100); search.message = null },
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (search.query.isEmpty()) {
                        Text("搜尋地址或地點", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                    }
                    field()
                }
            },
        )
        when {
            search.busy -> CircularProgressIndicator(Modifier.padding(horizontal = 10.dp).size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            search.query.isNotEmpty() -> {
                Icon(
                    Icons.Rounded.Close, "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp).clip(CircleShape).bouncyClick { search.query = ""; search.results = emptyList(); search.message = null }.padding(8.dp),
                )
                if (focused) {
                    Text(
                        "搜尋", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).bouncyClick(onClick = onSearch)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResults(hits: List<PlaceHit>, onPick: (PlaceHit) -> Unit) {
    Column(
        Modifier.padding(top = 8.dp).fillMaxWidth().shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Cute.colors.card).padding(vertical = 6.dp),
    ) {
        hits.forEach { h ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(h) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IllustratedIcon("📍", Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.subtitle.isNotEmpty()) {
                        Text(h.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(top = 8.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Cute.colors.card)
            .clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IllustratedIcon("🔍", Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 搜尋到的地點：顯示名稱和地址，可以直接在這裡新增標點 */
@Composable
private fun PinCard(pin: PlaceHit, onAdd: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Cute.colors.card)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IllustratedIcon("📌", Modifier.padding(top = 2.dp).size(24.dp))
        // 「新增標點」放在地址下面：名稱、地址用整個寬度，才認得出找到的是哪裡
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pin.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pin.subtitle.isNotEmpty()) {
                Text(pin.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "新增標點", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).bouncyClick(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Icon(
            Icons.Rounded.Close, "不要標出這個地點", tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp).clip(CircleShape).bouncyClick(onClick = onClose).padding(6.dp),
        )
    }
}

@Composable
private fun PartnerLine(s: AppState, vm: AppViewModel) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        CuteCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(s.partnerName, Cute.colors.partner, 40.dp, image = s.partner?.avatarUrl)
                Column(Modifier.weight(1f)) {
                    // 字放大時換成「小雨 在 / 小雨公司」「距離你 2.4 公里 · / 更新於 剛剛」，不會切在詞中間
                    if (s.partnerSharing?.locationLive == true) {
                        TidyText("${s.partnerName} ${Derived.partnerWhere(s)}", style = MaterialTheme.typography.titleMedium)
                        TidyText(
                            listOfNotNull(Derived.distanceText(s, "距離你"), "更新於 ${Fmt.rel(s.partnerStatus.location?.let { it.recordedAt ?: it.updatedAt }?.toInstantOrNull())}").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TidyText(s.partnerName, style = MaterialTheme.typography.titleMedium)
                        TidyText(if (s.partnerSharing?.paused == true) "暫停分享中" else "沒有分享位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (s.partnerSharing?.locationLive == true) {
                    Text(
                        "找找看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
                            .bouncyClick { vm.act { r -> vm.toast(if (r.locatePartner()) "已請對方的手機回報位置" else "對方的手機目前不在線上") } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceRow(s: AppState, p: Place, onClick: () -> Unit) {
    val watch = when (p.watchUser) { null -> "偵測兩人"; s.myId -> "偵測我"; else -> "偵測 ${s.partnerName}" }
    val flags = listOfNotNull(if (p.notifyArrive) "抵達" else null, if (p.notifyLeave) "離開" else null).joinToString("、").ifEmpty { "不" }
    val here = s.partnerInside.any { it.id == p.id } && s.partnerSharing?.locationLive == true
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Cute.colors.card).bouncyClick(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBubble(placeEmoji(p.icon), if (p.watchUser == s.myId) Cute.colors.meSoft else Cute.colors.partnerSoft, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 「在這」跟在名稱後面（放不下就換到下一行），偵測誰、半徑、通知用整個寬度，放不下就換行
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall)
                if (here) Pill("${s.partnerName} 在這", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.align(Alignment.CenterVertically))
            }
            TidyText(
                "$watch · 半徑 ${p.radiusM} 公尺 · ${flags}通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 標點的範圍圈：半透明填色 + 外框，半徑依地圖縮放換算成像素 */
private class FenceOverlay(private val fences: List<Triple<GeoPoint, Float, Int>>) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    override fun draw(c: Canvas, pj: org.osmdroid.views.Projection) {
        fences.forEach { (gp, meters, color) ->
            val pt = pj.toPixels(gp, null)
            val r = pj.metersToPixels(meters)
            fill.color = (color and 0x00FFFFFF) or 0x30000000
            stroke.color = color
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, stroke)
        }
    }
}

/** 圓形頭像（地圖標記用）：照片、表情或暱稱的最後一個字，外圈白邊 */
private fun avatarBitmap(context: Context, avatar: String?, name: String, color: Int, sizeDp: Int = 32): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    return BitmapDrawable(context.resources, Avatars.circle(context, avatar, name, color, (sizeDp * d).toInt(), 3 * d))
}

/** 地點標記也使用手繪小圖，避免系統 emoji 字型改變地圖的樣子。 */
private fun emojiBitmap(context: Context, emoji: String, bg: Int): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val size = (34 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = bg
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    drawIllustratedSymbol(c, emoji, size * 0.18f, size * 0.18f, size * 0.64f)
    return BitmapDrawable(context.resources, bmp)
}

/** 搜尋結果的水滴形大頭針，尖端對準地點 */
private fun pinBitmap(context: Context, color: Int): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val w = (30 * d).toInt()
    val h = (40 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val r = w / 2f - 2 * d
    val cy = r + 2 * d
    val shape = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        op(Path().apply { moveTo(cx - r * 0.7f, cy + r * 0.7f); lineTo(cx, h - 2 * d); lineTo(cx + r * 0.7f, cy + r * 0.7f); close() }, Path.Op.UNION)
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = color
    c.drawPath(shape, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 2.5f * d
    p.color = android.graphics.Color.WHITE
    c.drawPath(shape, p)
    p.style = Paint.Style.FILL
    c.drawCircle(cx, cy, r * 0.38f, p)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * 外層的頁面可以上下捲動，手指一按在地圖上就請外層不要搶走這次觸控，
 * 不然往上下拖地圖時，拖到一半會變成整頁在捲（地圖「斷觸」）。
 */
private class TouchMapView(context: Context, private val onTouchStart: () -> Unit) : MapView(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            onTouchStart()
        }
        return super.dispatchTouchEvent(ev)
    }
}

private data class MapColors(val me: Int, val partner: Int, val meSoft: Int, val partnerSoft: Int, val thread: Int, val pin: Int)

/** 地圖上要畫的東西；跟上次一樣就不重畫（App 狀態常常更新，例如對方的電量，不該每次都重建標記） */
private data class MapContent(
    val places: List<Place>,
    val myId: String?,
    val me: LatLng?,
    val partner: LatLng?,
    val myName: String,
    val myAvatar: String?,
    val partnerName: String,
    val partnerAvatar: String?,
    val pin: PlaceHit?,
    val colors: MapColors,
)

/** 管理地圖上的圖層：範圍圈、標點、紅線、兩人頭像、搜尋到的地點；圖示畫一次就留著重複用 */
private class MapLayers(private val context: Context, private val map: MapView, private val base: Overlay) {
    private var shown: MapContent? = null
    private val icons = HashMap<String, BitmapDrawable>()

    private fun icon(key: String, make: () -> BitmapDrawable) = icons.getOrPut(key, make)

    fun show(c: MapContent) {
        if (c == shown) return
        shown = c
        InfoWindow.closeAllInfoWindowsOn(map)
        val col = c.colors
        val layers = mutableListOf<Overlay>(base)
        layers += FenceOverlay(c.places.map { p -> Triple(GeoPoint(p.lat, p.lng), p.radiusM.toFloat(), if (p.watchUser == c.myId) col.me else col.partner) })
        c.places.forEach { p ->
            val bg = if (p.watchUser == c.myId) col.meSoft else col.partnerSoft
            val emoji = placeEmoji(p.icon)
            layers += Marker(map).apply {
                position = GeoPoint(p.lat, p.lng)
                icon = icon("place:$emoji:$bg") { emojiBitmap(context, emoji, bg) }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = p.name
            }
        }
        val me = c.me?.let { GeoPoint(it.lat, it.lng) }
        val partner = c.partner?.let { GeoPoint(it.lat, it.lng) }
        if (me != null && partner != null) {
            // 紅線：兩人之間畫一條微彎的線
            val mid = GeoPoint((me.latitude + partner.latitude) / 2 + (partner.longitude - me.longitude) * 0.15, (me.longitude + partner.longitude) / 2 - (partner.latitude - me.latitude) * 0.15)
            val pts = (0..24).map { i ->
                val t = i / 24.0
                val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val d = t * t
                GeoPoint(a * me.latitude + b * mid.latitude + d * partner.latitude, a * me.longitude + b * mid.longitude + d * partner.longitude)
            }
            layers += Polyline(map).apply {
                setPoints(pts)
                outlinePaint.color = col.thread; outlinePaint.strokeWidth = 7f; outlinePaint.strokeCap = Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> false } // 點到線不要跳出空白的說明框
            }
        }
        if (me != null) layers += Marker(map).apply {
            position = me; title = "我"
            icon = icon("me:${c.myName}:${c.myAvatar.hashCode()}:${col.me}") { avatarBitmap(context, c.myAvatar, c.myName, col.me) }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        if (partner != null) layers += Marker(map).apply {
            position = partner; title = c.partnerName
            icon = icon("partner:${c.partnerName}:${c.partnerAvatar.hashCode()}:${col.partner}") { avatarBitmap(context, c.partnerAvatar, c.partnerName, col.partner, 36) }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        c.pin?.let { hit ->
            layers += Marker(map).apply {
                position = GeoPoint(hit.lat, hit.lng); title = hit.title; snippet = hit.subtitle
                icon = icon("pin:${col.pin}") { pinBitmap(context, col.pin) }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        }
        map.overlays.clear()
        map.overlays.addAll(layers)
        map.invalidate()
    }
}

@Composable
private fun OsmMap(s: AppState, camera: MapCamera, flyTo: FlyTo?, pin: PlaceHit?, onTap: () -> Unit, onLongPress: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MapColors(
        me = Cute.colors.me.toArgb(), partner = Cute.colors.partner.toArgb(),
        meSoft = Cute.colors.meSoft.toArgb(), partnerSoft = Cute.colors.partnerSoft.toArgb(),
        thread = Cute.colors.thread.toArgb(), pin = MaterialTheme.colorScheme.primary.toArgb(),
    )
    val loading = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val map = remember {
        TouchMapView(context) { camera.settled = true }.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0 // 一個人出國時也能同時看到兩個人
            maxZoomLevel = 19.0
            // 圖磚還沒載入時用和外框一樣的底色，不要灰色格線
            overlayManager.tilesOverlay.loadingBackgroundColor = loading
            overlayManager.tilesOverlay.loadingLineColor = loading
            if (camera.known) {
                controller.setZoom(camera.zoom)
                controller.setCenter(GeoPoint(camera.lat, camera.lng))
            } else {
                controller.setZoom(14.5)
                controller.setCenter(GeoPoint(25.0400, 121.5600))
            }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { camera.from(this@apply); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { camera.from(this@apply); return false }
            })
        }
    }
    val layers = remember {
        MapLayers(context, map, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { tap(); return false }
            override fun longPressHelper(p: GeoPoint?): Boolean { if (p != null) longPress(p.latitude, p.longitude); return true }
        }))
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); map.onDetach() }
    }
    LaunchedEffect(flyTo) {
        val target = flyTo ?: return@LaunchedEffect
        camera.settled = true
        map.controller.animateTo(GeoPoint(target.lat, target.lng), target.zoom, 700L)
    }
    val me = s.myLocation
    val partner = s.partnerStatus.location?.takeIf { s.partnerSharing?.locationLive == true }?.let { LatLng(it.lat, it.lng) }
    val content = MapContent(s.places, s.myId, me, partner, s.me?.displayName ?: "我", s.me?.avatarUrl, s.partnerName, s.partner?.avatarUrl, pin, colors)
    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize()) { mv ->
        layers.show(content)
        // 第一次拿到位置時對準兩個人；使用者動過地圖就不再自動移動
        if (!camera.settled && (me != null || partner != null)) {
            camera.settled = true
            val pts = listOfNotNull(me, partner).map { GeoPoint(it.lat, it.lng) }
            if (pts.size == 2) {
                mv.post { runCatching { mv.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.3f), false) } }
            } else mv.controller.setCenter(pts.first())
        }
    }
}
