package tw.xiaoliangkou.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.data.AppLock
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.findActivity
import tw.xiaoliangkou.app.ui.theme.Cute

/**
 * App 鎖上時蓋住整個 App。鎖著的時候不畫 App 的內容（對話框、底部面板也不會露出來），
 * 解鎖後用 [rememberSaveableStateHolder] 回到原本的頁面。
 * [onForgot] 是「忘記密碼」（登出再登入），null 就不顯示；[onBack] 是在鎖定畫面按返回
 */
@Composable
fun LockGate(lock: AppLock, biometrics: BiometricAuth, onForgot: (() -> Unit)?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val locked by lock.locked.collectAsState()
    val holder = rememberSaveableStateHolder()
    if (locked) {
        LockScreen(lock, biometrics, onForgot, onBack)
    } else {
        holder.SaveableStateProvider("app") { content() }
    }
}

@Composable
fun LockScreen(lock: AppLock, biometrics: BiometricAuth, onForgot: (() -> Unit)?, onBack: () -> Unit) {
    val context = LocalContext.current
    val bio = lock.biometric && biometrics.problem(context) == null
    var asking by remember { mutableStateOf(false) }
    fun askBiometric() {
        val a = context.findActivity() ?: return
        if (asking) return
        asking = true
        biometrics.prompt(a, "解鎖小倆口", onSuccess = { asking = false; lock.unlockWithBiometric() }, onCancel = { asking = false })
    }
    // 一打開就問指紋或臉部，取消了再輸入密碼
    LaunchedEffect(Unit) { if (bio) askBiometric() }
    BackHandler(onBack = onBack)
    var forgot by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EmojiBubble("🔒", Cute.colors.partnerSoft, 72.dp)
            Text("小倆口已上鎖", style = MaterialTheme.typography.headlineSmall)
            val how = if (lock.type == AppLock.Type.PATTERN) "畫出圖形" else "輸入密碼"
            PinEntry(
                lock = lock,
                hint = if (bio) "${how}，或用指紋、臉部解鎖" else "${how}解鎖",
                extraKey = if (bio) ({ PadIcon("用指紋或臉部解鎖") { askBiometric() } }) else null,
                onOk = {},
            )
            if (onForgot != null) TextButton(onClick = { forgot = true }) { Text(if (lock.type == AppLock.Type.PATTERN) "忘記圖形？" else "忘記密碼？") }
        }
    }
    if (forgot && onForgot != null) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text(if (lock.type == AppLock.Type.PATTERN) "忘記圖形" else "忘記密碼") },
            text = { Text("登出後用 Email 和密碼重新登入，App 鎖會關掉，再到「我的 › App 安全性」重新設定。你們的資料都存在 Supabase，不會不見。") },
            confirmButton = { TextButton(onClick = { forgot = false; onForgot() }) { Text("登出") } },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("取消") } },
        )
    }
}

/** 輸入密碼並用 [lock] 檢查（鎖定畫面、關掉 App 鎖前確認）：對了呼叫 [onOk] */
@Composable
private fun PinEntry(lock: AppLock, hint: String, extraKey: (@Composable () -> Unit)?, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableLongStateOf(lock.waitSeconds()) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(1000)
            wait = lock.waitSeconds()
        }
    }
    fun submit(p: String) {
        when (val r = lock.check(p)) {
            AppLock.Result.Ok -> { error = null; onOk() }
            is AppLock.Result.Wrong -> error = "${if (lock.type == AppLock.Type.PATTERN) "圖形" else "密碼"}不對，還可以試 ${r.left} 次"
            is AppLock.Result.Wait -> { error = null; wait = r.seconds }
        }
        pin = ""
        if (lock.locked.value || error != null) scope.launch {
            for (x in listOf(14f, -12f, 8f, -4f, 0f)) shake.animateTo(x)
        }
    }
    TidyText(
        when {
            wait > 0 -> "錯太多次了，$wait 秒後再試"
            error != null -> error!!
            else -> hint
        },
        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = if (wait > 0 || error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (lock.type == AppLock.Type.PATTERN) {
        PatternPad(enabled = wait == 0L, error = error != null, modifier = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }) { p ->
            if (p.length < AppLock.MIN_PATTERN) error = "至少連 ${AppLock.MIN_PATTERN} 個點" else submit(p)
        }
        // 指紋的按鈕放在圖形下面
        extraKey?.invoke()
        return
    }
    PinDots(lock.pinLength, pin.length, Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) })
    PinPad(enabled = wait == 0L, extraKey = extraKey, onDigit = { d ->
        if (pin.length < lock.pinLength) {
            pin += d
            if (pin.length == lock.pinLength) submit(pin)
        }
    }, onDelete = { pin = pin.dropLast(1) })
}

@Composable
fun PinDots(total: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = "已輸入 $filled 位" }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .then(if (i < filled) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)),
            )
        }
    }
}

/** 數字鍵盤：1～9、左下角放 [extraKey]（指紋）、0、刪除 */
@Composable
fun PinPad(enabled: Boolean = true, extraKey: (@Composable () -> Unit)? = null, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { r.forEach { d -> PadKey(d.toString(), enabled) { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(PAD), contentAlignment = Alignment.Center) { extraKey?.invoke() }
            PadKey("0", enabled) { onDigit('0') }
            Box(
                Modifier.size(PAD).clip(CircleShape).bouncyClick(enabled = enabled, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Backspace, "刪除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private val PAD = 68.dp

@Composable
private fun PadKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(PAD).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.8f else 0.35f))
            .bouncyClick(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
}

@Composable
private fun PadIcon(description: String, onClick: () -> Unit) {
    Box(Modifier.size(PAD).clip(CircleShape).bouncyClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Fingerprint, description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
    }
}

/**
 * 設定或確認密碼的視窗。
 * [verify] 是 true：輸入目前的密碼或圖形（關掉 App 鎖、變更前），對了呼叫 [onDone]（帶 null）；
 * false：設定新的 [newType]，兩次一樣才呼叫 [onDone]（帶新的密碼或圖形）
 */
@Composable
fun PinDialog(lock: AppLock, verify: Boolean, onDismiss: () -> Unit, newType: AppLock.Type = AppLock.Type.PIN, onDone: (String?) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (verify) {
                Text(if (lock.type == AppLock.Type.PATTERN) "畫出目前的圖形" else "輸入目前的密碼", style = MaterialTheme.typography.titleLarge)
                PinEntry(lock, "確認是你本人", null) { onDone(null) }
            } else if (newType == AppLock.Type.PATTERN) {
                NewPattern(onDone)
            } else {
                NewPin(onDone)
            }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}

/** 設定新密碼：4～8 位數字，再輸入一次確認 */
@Composable
private fun NewPin(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val confirming = first != null
    Text(if (confirming) "再輸入一次" else "設定密碼", style = MaterialTheme.typography.titleLarge)
    TidyText(
        error ?: if (confirming) "確認一下剛剛的密碼" else "${AppLock.MIN_PIN}～${AppLock.MAX_PIN} 位數字，忘記的話要登出再登入",
        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PinDots(if (confirming) first!!.length else maxOf(AppLock.MIN_PIN, pin.length), pin.length)
    PinPad(onDigit = { d ->
        val max = first?.length ?: AppLock.MAX_PIN
        if (pin.length < max) pin += d
        val f = first
        if (f != null && pin.length == f.length) {
            if (pin == f) onDone(pin) else { error = "兩次不一樣，請重新設定"; first = null }
            pin = ""
        }
    }, onDelete = { pin = pin.dropLast(1) })
    if (!confirming) {
        CuteButton("下一步", Modifier.fillMaxWidth().height(48.dp), enabled = AppLock.isValidPin(pin), onClick = { first = pin; pin = ""; error = null })
    }
}

/** 設定新圖形：至少連 4 個點，再畫一次確認 */
@Composable
private fun NewPattern(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(if (first != null) "再畫一次" else "畫出圖形", style = MaterialTheme.typography.titleLarge)
    TidyText(
        error ?: if (first != null) "確認一下剛剛的圖形" else "至少連 ${AppLock.MIN_PATTERN} 個點，忘記的話要登出再登入",
        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PatternPad(error = error != null) { p ->
        val f = first
        when {
            f == null && !AppLock.isValidPattern(p) -> error = "至少連 ${AppLock.MIN_PATTERN} 個點"
            f == null -> { first = p; error = null }
            p == f -> onDone(p)
            else -> { error = "兩次不一樣，請重新畫"; first = null }
        }
    }
}

/** 圖形的幾何：3×3 的點，邊長 [size] 的正方形裡 */
internal object PatternGrid {
    /** 手指在 ([x], [y])：碰到哪一個點（0～8），沒有是 null。點的感應範圍是格子的 0.38 倍半徑 */
    fun nodeAt(x: Float, y: Float, size: Float): Int? {
        val cell = size / 3
        val col = (x / cell).toInt()
        val row = (y / cell).toInt()
        if (col !in 0..2 || row !in 0..2) return null
        val dx = x - (col + 0.5f) * cell
        val dy = y - (row + 0.5f) * cell
        return if (dx * dx + dy * dy <= (cell * 0.38f) * (cell * 0.38f)) row * 3 + col else null
    }

    /** 從 [a] 直接連到 [b] 中間跳過的點（例如 0 到 2 經過 1），沒有是 null */
    fun between(a: Int, b: Int): Int? {
        val (ra, ca) = a / 3 to a % 3
        val (rb, cb) = b / 3 to b % 3
        return if ((ra + rb) % 2 == 0 && (ca + cb) % 2 == 0) (ra + rb) / 2 * 3 + (ca + cb) / 2 else null
    }

    fun center(i: Int, size: Float): Offset = Offset((i % 3 + 0.5f) * size / 3, (i / 3 + 0.5f) * size / 3)
}

/** 畫圖形：手指按下去經過的點連起來，放開時呼叫 [onComplete]（經過的點，例如 "0125"） */
@Composable
fun PatternPad(modifier: Modifier = Modifier, enabled: Boolean = true, error: Boolean = false, onComplete: (String) -> Unit) {
    val path = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline
    Canvas(
        modifier.size(PATTERN).semantics { contentDescription = "畫圖形的地方：3 乘 3 的點" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val side = size.width.toFloat()
                fun hit(o: Offset) {
                    val n = PatternGrid.nodeAt(o.x, o.y, side) ?: return
                    if (n in path) return
                    path.lastOrNull()?.let { last -> PatternGrid.between(last, n)?.takeIf { it !in path }?.let { path += it } }
                    path += n
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    path.clear()
                    finger = down.position
                    hit(down.position)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        finger = c.position
                        hit(c.position)
                        c.consume()
                    }
                    finger = null
                    val p = path.joinToString("")
                    path.clear()
                    if (p.isNotEmpty()) onComplete(p)
                }
            },
    ) {
        val side = size.width
        val stroke = 6.dp.toPx()
        path.zipWithNext().forEach { (a, b) -> drawLine(color, PatternGrid.center(a, side), PatternGrid.center(b, side), stroke, StrokeCap.Round) }
        val last = path.lastOrNull()
        val f = finger
        if (last != null && f != null) drawLine(color.copy(alpha = 0.5f), PatternGrid.center(last, side), f, stroke, StrokeCap.Round)
        for (i in 0 until 9) {
            val c = PatternGrid.center(i, side)
            if (i in path) {
                drawCircle(color.copy(alpha = 0.18f), 26.dp.toPx(), c)
                drawCircle(color, 9.dp.toPx(), c)
            } else {
                drawCircle(idle, 7.dp.toPx(), c)
            }
        }
    }
}

private val PATTERN = 260.dp
