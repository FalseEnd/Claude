package tw.xiaoliangkou.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.PhotoTools
import tw.xiaoliangkou.app.data.VoiceRecorder
import tw.xiaoliangkou.app.data.compressPhoto
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.theme.Cute
import java.io.File

/** 語音長度：0:12、1:05 */
fun formatDuration(ms: Long): String {
    val s = ((ms + 500) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** 照片、語音：照片排成縮圖格（點了看大圖，可以左右滑），語音是可以播放的小膠囊。[onRemove] 有給就顯示移除按鈕 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaThumbs(media: List<MediaItem>, vm: AppViewModel, size: Dp = 86.dp, onRemove: ((MediaItem) -> Unit)? = null) {
    var viewing by remember { mutableStateOf<Int?>(null) }
    val photos = media.filter { it.isPhoto }
    val voices = media.filter { it.isVoice }
    if (photos.isEmpty() && voices.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (photos.isNotEmpty()) {
            // 可以移除時，叉叉的按鈕有一半在照片外面：縮圖之間留寬一點，按叉叉才不會點到隔壁那張
            val gap = if (onRemove != null) REMOVE_OUT + 2.dp else 8.dp
            FlowRow(
                Modifier.padding(top = if (onRemove != null) REMOVE_OUT / 2 else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                photos.forEachIndexed { i, p ->
                    key(p.path) {
                        Box {
                            PhotoThumb(p, vm, size) { viewing = i }
                            if (onRemove != null) RemoveDot(Modifier.align(Alignment.TopEnd).offset(REMOVE_OUT, -REMOVE_OUT)) { onRemove(p) }
                        }
                    }
                }
            }
        }
        voices.forEach { v ->
            key(v.path) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceChip(v, vm)
                    if (onRemove != null) RemoveDot { onRemove(v) }
                }
            }
        }
    }
    viewing?.takeIf { photos.isNotEmpty() }?.let { start ->
        PhotoViewer(photos, start.coerceIn(0, photos.lastIndex), vm) { viewing = null }
    }
}

/** 語音：▶️／⏸ 播放，顯示長度（播放時顯示播到哪） */
@Composable
fun VoiceChip(item: MediaItem, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val player = vm.container.voice
    // 有沒有在播看共用的播放器：播完、被別段語音打斷都會跟著變
    var file by remember(item.path) { mutableStateOf<File?>(null) }
    val current by player.playing.collectAsState()
    val playing = file != null && current == file
    var loading by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    // 離開畫面就停掉
    DisposableEffect(item.path) { onDispose { file?.let(player::stop) } }
    LaunchedEffect(playing) {
        while (playing) { position = player.positionMs; delay(200) }
        position = 0
    }

    fun toggle() {
        if (playing) { file?.let(player::stop); return }
        if (loading) return
        loading = true
        scope.launch {
            try {
                val f = vm.container.media.file(item.path) ?: throw AppException("語音載入失敗，等一下再試試看", "not_found")
                file = f
                withContext(Dispatchers.IO) { player.play(f) {} }
            } catch (e: AppException) {
                vm.toast(e.message ?: "這段語音播不出來")
            } finally {
                loading = false
            }
        }
    }

    val total = item.durationMs
    val label = when {
        playing && total != null -> "${formatDuration(position)} / ${formatDuration(total)}"
        total != null -> formatDuration(total)
        else -> "語音"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
            .bouncyClick { toggle() }
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暫停" else "播放",
                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp),
            )
        }
        Text("🎙️ $label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
    }
}

/** 面板裡的錄音：錄音中、錄好了還沒傳上去，都還不能儲存（存了面板就關掉，那段錄音會不見） */
enum class VoiceDraft(
    /** 儲存按鈕上要顯示的字；null 就是可以存 */
    val saveLabel: String?,
) {
    NONE(null),
    RECORDING("先停止錄音再儲存"),
    UNSENT("錄音還沒傳上去"),
}

/** 這次在面板裡上傳的照片、語音：最後沒存起來的，面板關掉時從 Storage 刪掉 */
class Uploads {
    val added = mutableListOf<MediaItem>()
    /** 送出儲存過的（可能已經存進資料庫，不能刪） */
    val kept = mutableSetOf<String>()
}

/** 面板用的 [Uploads]：換一則編輯（[key] 不一樣）或面板關掉時，上傳了沒存的刪掉 */
@Composable
fun rememberUploads(vm: AppViewModel, key: Any?): Uploads {
    val uploads = remember(key) { Uploads() }
    DisposableEffect(uploads) {
        onDispose {
            val drop = uploads.added.filter { it.path !in uploads.kept }
            val r = vm.repo.value
            if (drop.isNotEmpty() && r != null) vm.container.scope.launch(Dispatchers.IO) { runCatching { r.discardMedia(drop) } }
        }
    }
    return uploads
}

/** 錄好了但傳不上去的錄音 */
private class UnsentVoice(val file: File, val durationMs: Long)

/**
 * 「📷 照片」「🎙️ 錄音」：選照片（一次最多 9 張，壓縮後上傳）、錄音（按一下開始、再按一下停止，停止後上傳）。
 * 上傳好一個就呼叫 [onAdded]；上傳期間 vm.busy。[shared] 決定檔案放在兩個人共用的地方還是只有自己的地方。
 * [remaining] 是還能加幾個（每則最多 [Limits.MEDIA] 個）。
 * 錄音的狀態變了就呼叫 [onVoiceChange]：錄音中、錄好了還沒傳上去時先別讓人按儲存，不然那段錄音會不見。
 * 錄音傳不上去（網路不好）不會丟掉，可以重新上傳；App 跑到背景時先停下來，把錄到的存起來。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPickerRow(
    vm: AppViewModel, shared: Boolean, onAdded: (MediaItem) -> Unit, enabled: Boolean = true, remaining: Int = Limits.MEDIA,
    onVoiceChange: (VoiceDraft) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val added by rememberUpdatedState(onAdded)
    val voiceChanged by rememberUpdatedState(onVoiceChange)
    val isShared by rememberUpdatedState(shared)
    val left by rememberUpdatedState(remaining)
    var status by remember { mutableStateOf<String?>(null) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf<File?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    // 錄好了但傳不上去：先留在手機裡，可以重新上傳或丟掉（錄音不像照片可以再選一次）
    var unsent by remember { mutableStateOf<UnsentVoice?>(null) }
    // 麥克風權限被拒絕到系統不再詢問：問要不要去設定打開
    var micBlocked by remember { mutableStateOf(false) }

    suspend fun upload(bytes: ByteArray, mime: String, kind: String, width: Int? = null, height: Int? = null, durationMs: Long? = null) {
        val r = vm.repo.value ?: throw AppException(Errors.RELOGIN, "not_authenticated")
        val item = r.uploadMedia(bytes, mime, kind, isShared, width, height, durationMs)
        vm.container.media.put(item.path, bytes)
        added(item)
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val list = uris.take(left.coerceAtLeast(0))
        if (list.size < uris.size) vm.toast("照片和語音最多 ${Limits.MEDIA} 個" + if (list.isNotEmpty()) "，先加前 ${list.size} 張" else "")
        if (list.isEmpty()) return
        scope.launch {
            vm.uploading {
                vm.reportErrors {
                    try {
                        // 讀不到的照片先跳過，其他的照樣上傳
                        var skipped = 0
                        list.forEachIndexed { i, uri ->
                            status = if (list.size > 1) "上傳中… ${i + 1}/${list.size}" else "上傳中…"
                            val photo = withContext(Dispatchers.IO) { compressPhoto(context, uri) }
                            if (photo == null) { skipped++; return@forEachIndexed }
                            upload(photo.first, PhotoTools.MIME, MediaItem.PHOTO, photo.second.width, photo.second.height)
                        }
                        if (skipped > 0) vm.toast(if (list.size == 1) "讀不到這張照片，換一張試試看" else "有 $skipped 張照片讀不到，先略過")
                    } finally {
                        status = null
                    }
                }
            }
        }
    }

    fun startRecording() {
        scope.launch {
            vm.reportErrors {
                vm.container.voice.stop()
                val f = withContext(Dispatchers.IO) { recorder.start() }
                elapsed = 0
                recording = f
            }
        }
    }

    /** 上傳錄好的語音：傳好了才刪掉手機裡的檔案，傳不上去就留著（[unsent]）讓人重新上傳 */
    suspend fun sendVoice(file: File, ms: Long) {
        unsent = null
        try {
            status = "上傳中…"
            upload(withContext(Dispatchers.IO) { file.readBytes() }, VoiceRecorder.MIME, MediaItem.VOICE, durationMs = ms)
        } catch (e: Exception) {
            // 面板已經關掉（被取消）就不用留了
            if (e !is CancellationException && currentCoroutineContext().isActive) unsent = UnsentVoice(file, ms)
            throw e
        } finally {
            status = null
            if (unsent?.file != file) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    fun stopRecording() {
        if (recording == null) return
        recording = null
        scope.launch {
            vm.uploading {
                vm.reportErrors {
                    val (file, ms) = withContext(Dispatchers.IO) { recorder.stop() }
                    sendVoice(file, ms)
                }
            }
        }
    }

    fun cancelRecording() {
        val f = recording ?: return
        recording = null
        // 只丟這一段：馬上又按錄音的話，新的那段不會被取消掉
        vm.container.scope.launch(Dispatchers.IO) { recorder.cancel(f) }
    }

    fun retryVoice() {
        val v = unsent ?: return
        if (left <= 0) { vm.toast("照片和語音最多 ${Limits.MEDIA} 個，先移除一個再重新上傳"); return }
        scope.launch { vm.uploading { vm.reportErrors { sendVoice(v.file, v.durationMs) } } }
    }

    fun dropVoice() {
        val v = unsent ?: return
        unsent = null
        vm.container.scope.launch(Dispatchers.IO) { v.file.delete() }
    }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { addPhotos(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { addPhotos(listOfNotNull(it)) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val activity = context.findActivity()
        when {
            ok -> startRecording()
            // 拒絕過兩次，系統就不會再跳出詢問了：只能到設定裡打開
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) -> micBlocked = true
            else -> vm.toast("要允許使用麥克風才能錄音 🎙️")
        }
    }

    val voice = when {
        recording != null -> VoiceDraft.RECORDING
        unsent != null -> VoiceDraft.UNSENT
        else -> VoiceDraft.NONE
    }
    LaunchedEffect(voice) { voiceChanged(voice) }
    LaunchedEffect(recording) {
        while (recording != null) { elapsed = recorder.elapsedMs; delay(250) }
    }
    // 錄音中螢幕不要自己關掉：關掉就跟跑到背景一樣，後面都錄不到聲音
    val keepOn = recording != null
    DisposableEffect(view, keepOn) {
        if (keepOn) view.keepScreenOn = true
        onDispose { if (keepOn) view.keepScreenOn = false }
    }
    // 按主畫面鍵、切到別的 App、關螢幕：系統只給背景的 App 靜音，先停下來把錄到的存起來
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && recording != null) {
                val kept = recorder.elapsedMs >= VoiceRecorder.MIN_MS
                stopRecording()
                if (kept) vm.toast("離開 App 時錄音先停下來了，錄到的部分會加進來 🎙️")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(recorder) {
        // 錄到 3 分鐘會自己停：直接上傳
        recorder.onLimit = { scope.launch { stopRecording() } }
        // 離開畫面：沒停的錄音直接丟掉，麥克風放開（還在開麥克風的也會馬上關掉）；沒傳上去的錄音也刪掉
        onDispose {
            recorder.onLimit = {}
            voiceChanged(VoiceDraft.NONE)
            val leftover = unsent?.file
            vm.container.scope.launch(Dispatchers.IO) { recorder.release(); leftover?.delete() }
        }
    }

    val idle = enabled && !vm.busy && recording == null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 字放大時一排放不下就換到下一排，按鈕不會被擠成「取 / 消」
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("📷 照片", small = true, enabled = idle) {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                when {
                    left <= 0 -> vm.toast("照片和語音最多 ${Limits.MEDIA} 個")
                    left == 1 -> pickOne.launch(request)
                    else -> pickMany.launch(request)
                }
            }
            if (recording != null) {
                CuteButton("⏹ 停止 ${formatDuration(elapsed)}", small = true, color = Cute.colors.bad, contentColor = Color.White) { stopRecording() }
                SoftButton("取消", small = true) { cancelRecording() }
            } else {
                // 還有一段沒傳上去的錄音：先處理那段
                SoftButton("🎙️ 錄音", small = true, enabled = idle && unsent == null) {
                    when {
                        left <= 0 -> vm.toast("照片和語音最多 ${Limits.MEDIA} 個")
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        val pending = unsent
        when {
            recording != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordingDot()
                TidyText("錄音中… 再按一下停止（最長 3 分鐘）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(status ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pending != null -> Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cute.colors.butter.copy(alpha = 0.3f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "🎙️ ${formatDuration(pending.durationMs)} 的錄音還沒傳上去，錄音還留著，網路好一點再重新上傳就好",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CuteButton("重新上傳", small = true, enabled = enabled && !vm.busy) { retryVoice() }
                    SoftButton("丟掉這段", small = true, enabled = !vm.busy) { dropVoice() }
                }
            }
        }
    }

    if (micBlocked) AlertDialog(
        onDismissRequest = { micBlocked = false },
        title = { Text("要打開麥克風權限") },
        text = { Text("麥克風權限被關掉了，系統不會再跳出詢問。按「去設定」› 權限 › 麥克風，選擇允許，回來就可以錄音了。") },
        confirmButton = { TextButton(onClick = { micBlocked = false; openAppSettings(context) }) { Text("去設定") } },
        dismissButton = { TextButton(onClick = { micBlocked = false }) { Text("先不要") } },
    )
}

/** 面板裡的 context 可能被包了好幾層：一路拆到 Activity */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 打開這個 App 在系統設定裡的頁面（權限被拒絕到不再詢問時，只能從這裡打開） */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 一次最多選幾張照片 */
private const val MAX_PICK = 9
/** 看大圖時解碼的大小（跟上傳時一樣大） */
private const val FULL_SIDE = PhotoTools.MAX_SIDE

/** 照片載入的狀態 */
private sealed interface Pic {
    data object Loading : Pic
    data object Missing : Pic
    data class Ready(val image: ImageBitmap) : Pic
}

/** 從 MediaCache 讀照片（背景執行緒下載、解碼；記憶體裡有就直接顯示） */
@Composable
private fun rememberPhoto(vm: AppViewModel, path: String, maxSide: Int): Pic {
    val cache = vm.container.media
    val initial = remember(path, maxSide) { cache.cachedBitmap(path, maxSide)?.let { Pic.Ready(it.asImageBitmap()) } ?: Pic.Loading }
    return produceState(initial, path, maxSide) {
        value = initial
        if (initial !is Pic.Loading) return@produceState
        val bmp = cache.bitmap(path, maxSide)
        // 畫面的狀態在主執行緒改
        withContext(Dispatchers.Main.immediate) { value = bmp?.let { Pic.Ready(it.asImageBitmap()) } ?: Pic.Missing }
    }.value
}

/** 縮圖要解碼多大：比格子大一點，裁成正方形也清楚；只用幾種大小，快取比較好命中 */
private fun thumbSide(px: Int) = listOf(256, 512, 1024).firstOrNull { it >= px * 3 / 2 } ?: 1024

@Composable
private fun PhotoThumb(item: MediaItem, vm: AppViewModel, size: Dp, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val pic = rememberPhoto(vm, item.path, thumbSide(px))
    Box(
        Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(Cute.colors.peach.copy(alpha = 0.22f)).bouncyClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (pic) {
            is Pic.Ready -> Image(pic.image, "照片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Pic.Loading -> Text("📷", Modifier.alpha(0.45f), style = MaterialTheme.typography.titleLarge)
            Pic.Missing -> Text("🖼️", Modifier.alpha(0.35f), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 全螢幕看大圖，左右滑看下一張 */
@Composable
private fun PhotoViewer(photos: List<MediaItem>, start: Int, vm: AppViewModel, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { photos.size }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].path }) { page ->
                val pic = rememberPhoto(vm, photos[page].path, FULL_SIDE)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (pic) {
                        is Pic.Ready -> Image(pic.image, "照片 ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Pic.Loading -> CircularProgressIndicator(color = Color.White)
                        Pic.Missing -> Text("這張照片讀不到 😢", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "關閉", tint = Color.White)
            }
            if (photos.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${photos.size}", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                )
            }
        }
    }
}

/** 叉叉的按鈕有多少在照片外面 */
private val REMOVE_OUT = 12.dp

/**
 * 縮圖角落的小叉叉：看起來是 24dp 的小圓點，可以按的範圍有 40dp。
 * 照片上的往外推一半（[REMOVE_OUT]），按的範圍大一點也不會蓋掉太多照片，不會想移除卻打開了大圖
 */
@Composable
private fun RemoveDot(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).bouncyClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "移除", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** 錄音中一閃一閃的小紅點 */
@Composable
private fun RecordingDot() {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recAlpha")
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(Cute.colors.bad))
}

/** 上傳、錄音的錯誤用 toast 顯示（取消不算錯誤） */
private suspend fun AppViewModel.reportErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppException) {
        toast(e.message ?: "發生錯誤")
    } catch (e: Exception) {
        toast("發生錯誤：${e.message ?: e.javaClass.simpleName}")
    }
}
