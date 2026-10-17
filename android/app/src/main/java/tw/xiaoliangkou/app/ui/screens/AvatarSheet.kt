package tw.xiaoliangkou.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.cuteSlider
import tw.xiaoliangkou.app.ui.theme.Cute
import kotlin.math.min
import kotlin.math.roundToInt

/** 設定自己的頭像：相簿裡的照片（可以移動、縮放）、可愛的表情，或改回用暱稱 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarSheet(s: AppState, vm: AppViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    val name = s.me?.displayName ?: "我"
    val current = s.me?.avatarUrl
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { Avatars.load(context, uri) }
            if (bmp == null) vm.toast("讀不到這張照片，換一張試試看") else picked = bmp
        }
    }
    fun save(avatar: String?) = vm.act("頭像已更新 ✨", onDone = close) { it.updateAvatar(avatar) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("設定頭像 📷", style = MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(name, Cute.colors.me, 96.dp, image = current) }
        CuteButton("從相簿選照片", Modifier.fillMaxWidth(), icon = Icons.Rounded.PhotoLibrary, enabled = !vm.busy) {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        Text("或選一個可愛的頭像", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = Avatars.emojiOf(current)
            Avatars.emojis.forEach { e ->
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(Avatars.softColor(Cute.colors.me.toArgb())))
                        .then(if (e == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .bouncyClick(enabled = !vm.busy) { save(Avatars.emoji(e)) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = MaterialTheme.typography.headlineSmall) }
            }
        }
        if (current != null) {
            SoftButton("改回用暱稱「${Avatars.initial(name)}」", Modifier.fillMaxWidth(), icon = Icons.Rounded.TextFields, enabled = !vm.busy) { save(null) }
        }
        Note("頭像存在你們自己的 Supabase，只有你們兩個看得到。照片會裁成正方形、縮小後才上傳。", "🔒")
    }

    picked?.let { src ->
        AvatarCropDialog(src, onCancel = { picked = null }) { square ->
            picked = null
            vm.act("頭像已更新 ✨", onDone = close) { r -> r.updateAvatar(withContext(Dispatchers.Default) { Avatars.encodePhoto(square) }) }
        }
    }
}

/** 裁切照片：兩指縮放、拖曳移動，圓圈裡的部分就是頭像 */
@Composable
fun AvatarCropDialog(src: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val image = remember(src) { src.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val box = 260.dp
    val viewport = with(LocalDensity.current) { box.toPx() }
    fun clamp(o: Offset, z: Float): Offset {
        val (mx, my) = Avatars.maxOffset(src.width, src.height, viewport, z)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.background).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("移動、縮放照片", style = MaterialTheme.typography.titleMedium)
            Canvas(
                Modifier.size(box).clip(RoundedCornerShape(20.dp)).background(Color.Black)
                    .pointerInput(src) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val z = (zoom * gestureZoom).coerceIn(1f, 5f)
                            zoom = z
                            offset = clamp(offset + pan, z)
                        }
                    },
            ) {
                val scale = size.minDimension / min(src.width, src.height) * zoom
                val w = src.width * scale
                val h = src.height * scale
                drawImage(
                    image,
                    dstOffset = IntOffset((size.width / 2 + offset.x - w / 2).roundToInt(), (size.height / 2 + offset.y - h / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                // 圓圈外面調暗，看得出頭像會是哪一塊
                clipPath(Path().apply { addOval(Rect(Offset.Zero, size)) }, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
                drawCircle(Color.White, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            Slider(zoom, { z -> zoom = z; offset = clamp(offset, z) }, valueRange = 1f..5f, colors = cuteSlider())
            Text("兩指縮放、拖曳移動，圓圈裡的部分會變成頭像", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ButtonRow(weights = listOf(1f, 1.4f), spacing = 10.dp) {
                SoftButton("取消") { onCancel() }
                CuteButton("使用這張") {
                    onDone(Avatars.crop(src, Avatars.cropOf(src.width, src.height, viewport, zoom, offset.x, offset.y)))
                }
            }
        }
    }
}
