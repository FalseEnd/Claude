package tw.xiaoliangkou.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Place
import tw.xiaoliangkou.app.data.toInstantOrNull
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
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute

fun placeEmoji(icon: String) = when (icon) {
    "home" -> "🏠"; "work" -> "💼"; "school" -> "🏫"; "gym" -> "🏋️"; "cafe" -> "☕"; "food" -> "🍜"; "shop" -> "🛍️"; "heart" -> "💗"; else -> "📍"
}

@Composable
fun MapScreen(s: AppState, vm: AppViewModel, sheet: (SheetSpec) -> Unit) {
    var focus by remember { mutableStateOf(0) } // 0 兩人、1 對方、2 我
    var focusTick by remember { mutableStateOf(0) }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("地圖") }
        item {
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(380.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (LocalInspectionMode.current) Text("地圖", Modifier.align(Alignment.Center))
                else OsmMap(s, focus, focusTick) { lat, lng -> sheet(SheetSpec.PlaceEdit(null, lat, lng)) }
                Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(onClick = { focus = 1; focusTick++ }, containerColor = Cute.colors.card) { Text("💗") }
                    SmallFloatingActionButton(onClick = { focus = 2; focusTick++ }, containerColor = Cute.colors.card) { Icon(Icons.Rounded.MyLocation, "我的位置", tint = Cute.colors.me) }
                    FloatingActionButton(
                        onClick = {
                            val c = s.myLocation ?: s.partnerStatus.location?.let { tw.xiaoliangkou.app.data.LatLng(it.lat, it.lng) }
                            sheet(SheetSpec.PlaceEdit(null, c?.lat ?: 25.0330, c?.lng ?: 121.5654))
                        },
                        containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp),
                    ) { Icon(Icons.Rounded.Add, "新增標點", tint = MaterialTheme.colorScheme.onPrimary) }
                }
                Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { Pill("長按地圖可以新增標點 📍", Cute.colors.card.copy(alpha = 0.92f)) }
            }
        }
        item { PartnerLine(s, vm) }
        item { SectionTitle("標點與通知", Modifier.padding(horizontal = 16.dp), trailing = "${s.places.size} 個") }
        items(s.places, key = { it.id }) { p -> PlaceRow(s, p) { sheet(SheetSpec.PlaceEdit(p.id, p.lat, p.lng)) } }
        item {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Note("標點兩個人都看得到。你新增會偵測 ${s.partnerName} 的標點時，${s.partnerName} 一定會收到通知，也能隨時修改或關閉位置分享。", "🛡️")
            }
        }
    }
}

@Composable
private fun PartnerLine(s: AppState, vm: AppViewModel) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        CuteCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(s.partnerName, Cute.colors.partner, 40.dp)
                Column(Modifier.weight(1f)) {
                    if (s.partnerSharing?.locationLive == true) {
                        Text("${s.partnerName} ${Derived.partnerWhere(s)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(Derived.distanceToPartner(s)?.let { "距離你 ${Fmt.distance(it)}" }, "更新於 ${Fmt.rel(s.partnerStatus.location?.updatedAt?.toInstantOrNull())}").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(s.partnerName, style = MaterialTheme.typography.titleMedium)
                        Text(if (s.partnerSharing?.paused == true) "暫停分享中 🙈" else "沒有分享位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (s.partnerSharing?.locationLive == true) {
                    Text(
                        "找找看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
                            .bouncyClick { vm.act { r -> vm.toast(if (r.locatePartner()) "已請對方的手機回報位置 📍" else "對方的手機目前不在線上") } }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

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
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall)
            Text("$watch · 半徑 ${p.radiusM} 公尺 · ${flags}通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (here) Pill("${s.partnerName} 在這 💗", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
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

/** 圓形頭像（地圖標記用的 Bitmap） */
private fun avatarBitmap(context: Context, text: String, color: Int, sizeDp: Int = 32): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val size = (sizeDp * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    c.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
    p.color = android.graphics.Color.WHITE
    p.textSize = size * 0.45f
    p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.DEFAULT_BOLD
    c.drawText(text, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(context.resources, bmp)
}

private fun emojiBitmap(context: Context, emoji: String, bg: Int): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val size = (26 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = bg
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    p.textSize = size * 0.5f
    p.textAlign = Paint.Align.CENTER
    c.drawText(emoji, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(context.resources, bmp)
}

@Composable
private fun OsmMap(s: AppState, focus: Int, focusTick: Int, onLongPress: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val partnerColor = Cute.colors.partner.toArgb()
    val meColor = Cute.colors.me.toArgb()
    val threadColor = Cute.colors.thread.toArgb()
    val partnerSoft = Cute.colors.partnerSoft.toArgb()
    val meSoft = Cute.colors.meSoft.toArgb()
    val longPress by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.5)
            controller.setCenter(GeoPoint(25.0400, 121.5600))
            isTilesScaledToDpi = true
        }
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
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(focusTick) {
        val partner = s.partnerStatus.location?.let { GeoPoint(it.lat, it.lng) }
        val me = s.myLocation?.let { GeoPoint(it.lat, it.lng) }
        val target = when (focus) { 1 -> partner; 2 -> me; else -> null }
        if (target != null) map.controller.animateTo(target, 16.0, 600L)
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize()) { mv ->
        mv.overlays.clear()
        mv.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean { if (p != null) longPress(p.latitude, p.longitude); return true }
        })
        mv.overlays += FenceOverlay(s.places.map { p -> Triple(GeoPoint(p.lat, p.lng), p.radiusM.toFloat(), if (p.watchUser == s.myId) meColor else partnerColor) })
        s.places.forEach { p ->
            val watchMe = p.watchUser == s.myId
            mv.overlays += Marker(mv).apply {
                position = GeoPoint(p.lat, p.lng)
                icon = emojiBitmap(context, placeEmoji(p.icon), if (watchMe) meSoft else partnerSoft)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = p.name
            }
        }
        val me = s.myLocation?.let { GeoPoint(it.lat, it.lng) }
        val partner = s.partnerStatus.location?.takeIf { s.partnerSharing?.locationLive == true }?.let { GeoPoint(it.lat, it.lng) }
        if (me != null && partner != null) {
            // 紅線：兩人之間畫一條微彎的線
            val mid = GeoPoint((me.latitude + partner.latitude) / 2 + (partner.longitude - me.longitude) * 0.15, (me.longitude + partner.longitude) / 2 - (partner.latitude - me.latitude) * 0.15)
            val pts = (0..24).map { i ->
                val t = i / 24.0
                val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val c = t * t
                GeoPoint(a * me.latitude + b * mid.latitude + c * partner.latitude, a * me.longitude + b * mid.longitude + c * partner.longitude)
            }
            mv.overlays += Polyline(mv).apply { setPoints(pts); outlinePaint.color = threadColor; outlinePaint.strokeWidth = 7f; outlinePaint.strokeCap = Paint.Cap.ROUND }
        }
        if (me != null) mv.overlays += Marker(mv).apply {
            position = me; icon = avatarBitmap(context, (s.me?.displayName ?: "我").takeLast(1), meColor); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "我"
        }
        if (partner != null) mv.overlays += Marker(mv).apply {
            position = partner; icon = avatarBitmap(context, s.partnerName.takeLast(1), partnerColor, 36); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = s.partnerName
        }
        if (!centered && (me != null || partner != null)) {
            centered = true
            val pts = listOfNotNull(me, partner)
            if (pts.size == 2) {
                mv.post { runCatching { mv.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.3f), false) } }
            } else mv.controller.setCenter(pts.first())
        }
        mv.invalidate()
    }
}
