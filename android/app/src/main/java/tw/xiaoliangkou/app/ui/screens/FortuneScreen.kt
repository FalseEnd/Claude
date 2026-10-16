package tw.xiaoliangkou.app.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.core.Horoscope
import tw.xiaoliangkou.app.core.Omikuji
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt
import kotlin.random.Random

internal object FortuneTab {
    const val HOROSCOPE = "horoscope"
    const val OMIKUJI = "omikuji"
}

/** 存在手機裡：上次看的分頁、手動選的星座、抽過的籤（最近 10 支） */
internal class FortuneStore(context: Context) {
    private val prefs = context.getSharedPreferences("fortune", Context.MODE_PRIVATE)

    data class Draw(val no: Int, val at: LocalDateTime) {
        val lot: Omikuji.Lot? get() = Omikuji.lots.firstOrNull { it.no == no }
    }

    var tab: String
        get() = prefs.getString("tab", null) ?: FortuneTab.HOROSCOPE
        set(v) = prefs.edit().putString("tab", v).apply()

    /** 沒填生日時手動選的星座（[key] 分開記自己和對方的） */
    fun sign(key: String): Horoscope.Sign? = Horoscope.signs.getOrNull(prefs.getInt("sign:$key", -1))
    fun setSign(key: String, sign: Horoscope.Sign) = prefs.edit().putInt("sign:$key", sign.id).apply()

    /** 新的在前 */
    fun draws(): List<Draw> = prefs.getString("draws", null).orEmpty().split(';').mapNotNull { e ->
        val parts = e.split('@').takeIf { it.size == 2 } ?: return@mapNotNull null
        val no = parts[0].toIntOrNull()?.takeIf { it in 1..Omikuji.lots.size } ?: return@mapNotNull null
        val at = runCatching { LocalDateTime.parse(parts[1]) }.getOrNull() ?: return@mapNotNull null
        Draw(no, at)
    }

    fun add(no: Int, at: LocalDateTime): List<Draw> {
        val list = (listOf(Draw(no, at.withNano(0))) + draws()).take(MAX)
        prefs.edit().putString("draws", list.joinToString(";") { "${it.no}@${it.at}" }).apply()
        return list
    }

    companion object {
        const val MAX = 10
    }
}

@Composable
fun FortuneScreen(s: AppState, vm: AppViewModel, back: () -> Unit) = FortuneContent(s, back)

/** [tab]、[clock]、[random] 讓截圖測試可以固定畫面 */
@Composable
internal fun FortuneContent(
    s: AppState,
    back: () -> Unit,
    tab: String? = null,
    clock: () -> LocalDateTime = LocalDateTime::now,
    random: Random = Random.Default,
) {
    val context = LocalContext.current
    val store = remember { FortuneStore(context) }
    var current by rememberSaveable { mutableStateOf(tab ?: store.tab) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("星座運勢與御神籤", onBack = back)
        Segmented(listOf(FortuneTab.HOROSCOPE to "🔮 星座運勢", FortuneTab.OMIKUJI to "⛩️ 御神籤"), current) {
            current = it
            store.tab = it
        }
        if (current == FortuneTab.OMIKUJI) OmikujiTab(store, clock, random) else HoroscopeTab(s, store, clock().toLocalDate())
    }
}

// ---------- 星座運勢 ----------

@Composable
private fun HoroscopeTab(s: AppState, store: FortuneStore, today: LocalDate) {
    val uri = LocalUriHandler.current
    val myKey = "me:${s.myId.orEmpty()}"
    val partnerKey = "partner:${s.partner?.id.orEmpty()}"
    var myPick by remember(myKey) { mutableStateOf(store.sign(myKey)) }
    var partnerPick by remember(partnerKey) { mutableStateOf(store.sign(partnerKey)) }
    val myBirth = s.me?.birthDate
    val partnerBirth = s.partner?.birthDate
    val mySign = myBirth?.let(Horoscope::signOf) ?: myPick
    val partnerSign = if (s.paired) partnerBirth?.let(Horoscope::signOf) ?: partnerPick else null

    var who by rememberSaveable { mutableStateOf("me") }
    val viewing = if (s.paired) who else "me"
    var picking by remember { mutableStateOf<String?>(null) }
    var day by rememberSaveable { mutableStateOf(0) }
    val sign = if (viewing == "me") mySign else partnerSign
    // 正在看的人還沒有星座：直接打開選擇
    val pickerFor = picking ?: viewing.takeIf { sign == null }

    CuteCard {
        Text("我們的星座", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignTile(
                name = s.me?.displayName ?: "我", color = Cute.colors.me, image = s.me?.avatarUrl, sign = mySign,
                manual = myBirth == null, selected = viewing == "me", modifier = Modifier.weight(1f),
                onChange = { who = "me"; picking = "me" },
            ) { who = "me"; picking = if (mySign == null) "me" else null }
            if (s.paired) SignTile(
                name = s.partnerName, color = Cute.colors.partner, image = s.partner?.avatarUrl, sign = partnerSign,
                manual = partnerBirth == null, selected = viewing == "partner", modifier = Modifier.weight(1f),
                onChange = { who = "partner"; picking = "partner" },
            ) { who = "partner"; picking = if (partnerSign == null) "partner" else null }
        }
        AnimatedVisibility(pickerFor != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val forMe = (pickerFor ?: "me") == "me"
            SignPicker(
                title = if (forMe) "你是什麼星座？" else "${s.partnerName} 是什麼星座？",
                selected = if (forMe) mySign else partnerSign,
                onClose = if ((if (forMe) mySign else partnerSign) != null) ({ picking = null }) else null,
            ) { picked ->
                if (forMe) { store.setSign(myKey, picked); myPick = picked } else { store.setSign(partnerKey, picked); partnerPick = picked }
                picking = null
            }
        }
        if (myBirth == null) Note("到「我的 › 我的資料」填上生日，就會自動算出你的星座", "🎂")
        else if (s.paired && partnerBirth == null) Note("${s.partnerName} 還沒填生日，可以先幫忙選一個星座看看", "🎂")
    }

    Segmented(
        listOf("0" to "今天 ${Fmt.md(today)}", "1" to "明天 ${Fmt.md(today.plusDays(1))}"),
        day.toString(),
    ) { day = it.toInt() }

    val date = today.plusDays(day.toLong())
    val dayWord = if (day == 0) "今天" else "明天"
    if (sign != null) {
        val daily = remember(sign, date) { Horoscope.daily(sign, date) }
        DailyCard(daily, dayWord) { runCatching { uri.openUri(Horoscope.moreUrl(sign)) } }
    }
    if (mySign != null && partnerSign != null) {
        val match = remember(mySign, partnerSign, date) { Horoscope.matchOfDay(mySign, partnerSign, date) }
        MatchCard(match, s.me?.displayName ?: "我", s.partnerName, dayWord)
    }
}

@Composable
private fun SignTile(
    name: String,
    color: Color,
    image: String?,
    sign: Horoscope.Sign?,
    manual: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onChange: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .bouncyClick(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(name, color, 22.dp, image = image)
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(sign?.emoji ?: "❔", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
        Text(sign?.name ?: "還不知道", style = MaterialTheme.typography.titleSmall)
        Text(
            sign?.let { "${it.range} · ${it.element.label}" } ?: "點我選星座",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
        if (manual && sign != null) Text(
            "手動選的 · 換一個",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onChange).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SignPicker(title: String, selected: Horoscope.Sign?, onClose: (() -> Unit)?, onPick: (Horoscope.Sign) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onClose != null) Text(
                "收起", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Horoscope.signs.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { sg ->
                    val on = sg == selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(18.dp))
                            .bouncyClick { onPick(sg) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(sg.emoji, style = MaterialTheme.typography.titleLarge)
                        Text(sg.name, style = MaterialTheme.typography.labelLarge)
                        Text(sg.range, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyCard(daily: Horoscope.Daily, dayWord: String, onMore: () -> Unit) {
    val sign = daily.sign
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmojiBubble(sign.emoji, Cute.colors.lavender.copy(alpha = 0.25f).compositeOver(Cute.colors.card), 52.dp)
            Column(Modifier.weight(1f)) {
                Text("${sign.name}的${dayWord}運勢", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Fmt.md(daily.date)}（${Fmt.weekday(daily.date).substring(1)}）· ${sign.element.emoji} ${sign.element.label} · ${sign.trait}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${daily.overall.emoji} 綜合運勢", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Stars(daily.overall.stars, 22.dp)
            }
            Text(daily.overall.text, style = MaterialTheme.typography.bodyMedium)
        }
        listOf(daily.love, daily.work, daily.money, daily.health).forEach { AspectRow(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("幸運色", daily.luckyColor.name, Modifier.weight(1f), icon = {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(Color(daily.luckyColor.argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            })
            StatTile("幸運數字", daily.luckyNumber.toString(), Modifier.weight(1f), icon = { Text("🍀", style = MaterialTheme.typography.titleSmall) })
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cute.colors.partnerSoft).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("💌", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${dayWord}的一句話", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(daily.advice, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("依星座與日期產生，僅供娛樂", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                "看更多 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AspectRow(a: Horoscope.Aspect) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EmojiBubble(a.emoji, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Stars(a.stars, 17.dp)
            }
            Text(a.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stars(n: Int, size: Dp) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$n 顆星" }) {
        repeat(5) { i ->
            Icon(Icons.Rounded.Star, null, tint = if (i < n) Cute.colors.butter else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(size))
        }
    }
}

@Composable
private fun MatchCard(match: Horoscope.Match, myName: String, partnerName: String, dayWord: String) {
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💞 ${dayWord}的速配指數", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(match.label, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MatchSide(myName, match.a, Modifier.weight(1f))
            ScoreRing(match.score)
            MatchSide(partnerName, match.b, Modifier.weight(1f))
        }
        Text(match.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MatchSide(name: String, sign: Horoscope.Sign, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(sign.emoji, style = MaterialTheme.typography.headlineMedium)
        Text(sign.name, style = MaterialTheme.typography.titleSmall)
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(score) { sweep.animateTo(score / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.primaryContainer
    val bar = MaterialTheme.colorScheme.primary
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 10.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(bar, -90f, 360f * sweep.value, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp, start = 1.dp))
        }
    }
}

// ---------- 御神籤 ----------

private val hm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun OmikujiTab(store: FortuneStore, clock: () -> LocalDateTime, random: Random) {
    val uri = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draws by remember { mutableStateOf(store.draws()) }
    var shown by rememberSaveable { mutableStateOf<Int?>(null) }
    var drawing by remember { mutableStateOf<Omikuji.Lot?>(null) }
    val shake = remember { Animatable(0f) }
    val stick = remember { Animatable(0f) }
    val appear = remember { Animatable(1f) }
    val bring = remember { BringIntoViewRequester() }
    val draw = { if (drawing == null) drawing = Omikuji.draw(random) }
    val hasSensor = shakeToDraw(enabled = drawing == null, onShake = draw)

    LaunchedEffect(drawing) {
        val lot = drawing ?: return@LaunchedEffect
        shown = null
        stick.snapTo(0f)
        repeat(6) { i -> shake.animateTo(if (i % 2 == 0) 14f else -14f, tween(if (i == 0) 70 else 120, easing = FastOutSlowInEasing)) }
        shake.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f))
        stick.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 280f))
        delay(200)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        draws = store.add(lot.no, clock())
        appear.snapTo(0f)
        shown = lot.no
        launch { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
        delay(60)
        runCatching { bring.bringIntoView() }
        drawing = null
    }

    val sway by rememberInfiniteTransition(label = "sway").animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 3200
            0f at 0; 3f at 800; 0f at 1600; -3f at 2400; 0f at 3200
        }),
        label = "sway",
    )

    CuteCard(padding = PaddingValues(20.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("東京淺草觀音寺一百籤", style = MaterialTheme.typography.titleLarge)
            Text("心裡默念想問的事，再搖一搖籤筒 🙏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        OmikujiBox(
            rotation = if (drawing != null) shake.value else sway,
            stickOut = stick.value,
            number = drawing?.no ?: shown,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        CuteButton(
            when {
                drawing != null -> "搖呀搖…"
                shown != null -> "再抽一支"
                else -> "搖一搖抽籤"
            },
            Modifier.fillMaxWidth(), enabled = drawing == null, onClick = draw,
        )
        if (hasSensor) Text(
            "也可以直接搖手機 📳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    shown?.let { no -> Omikuji.lots.firstOrNull { it.no == no } }?.let { lot ->
        OmikujiSlip(
            lot,
            Modifier.bringIntoViewRequester(bring).graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                val sc = 0.92f + 0.08f * appear.value
                scaleX = sc; scaleY = sc
                translationY = (1f - appear.value) * 40.dp.toPx()
            },
            onAgain = draw,
            again = drawing == null,
        ) { runCatching { uri.openUri(Omikuji.url(lot.no)) } }
    }

    val today = clock().toLocalDate()
    val todays = draws.filter { it.at.toLocalDate() == today }
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📜 今天抽到的籤", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (todays.isNotEmpty()) Text("${todays.size} 支", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (todays.isEmpty()) Text("今天還沒抽籤，先搖一支試試看吧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            todays.forEach { d ->
                val lot = d.lot ?: return@forEach
                HistoryRow(d, lot, selected = d.no == shown && drawing == null) {
                    if (drawing != null) return@HistoryRow
                    shown = lot.no
                    scope.launch {
                        appear.snapTo(1f)
                        delay(60)
                        runCatching { bring.bringIntoView() }
                    }
                }
            }
        }
    }

    Text(
        "籤詩：元三大師觀音百籤（淺草寺）；解籤：台灣好廟網",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HistoryRow(d: FortuneStore.Draw, lot: Omikuji.Lot, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .bouncyClick(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(hm.format(d.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("第 ${lot.no} 籤", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp), maxLines = 1)
        lot.luck?.let { LuckPill(it) }
        Text(
            lot.poem.first() + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LuckPill(luck: String) {
    val c = luckColor(luck)
    Pill(luck, c.copy(alpha = 0.16f).compositeOver(Cute.colors.card), c)
}

@Composable
private fun dark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 大吉最紅、凶用溫和的紫色（不要嚇人） */
@Composable
private fun luckColor(luck: String?): Color = when (luck) {
    "大吉" -> Cute.colors.thread
    "吉" -> MaterialTheme.colorScheme.primary
    "半吉", "小吉", "末小吉", "末吉" -> if (dark()) Cute.colors.peach else Color(0xFFD9772A)
    "凶" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun OmikujiSlip(lot: Omikuji.Lot, modifier: Modifier, onAgain: () -> Unit, again: Boolean, onMore: () -> Unit) {
    val ink = Cute.colors.thread
    val paper = Cute.colors.butter.copy(alpha = if (dark()) 0.07f else 0.14f).compositeOver(Cute.colors.card)
    CuteCard(modifier, color = paper, padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("觀音百籤 · 第${chineseNumber(lot.no)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("第 ${lot.no} 籤", style = MaterialTheme.typography.headlineMedium)
            }
            lot.luck?.let { LuckStamp(it) }
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally)
                .border(1.5.dp, ink.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) { contentDescription = lot.poem.joinToString("，") },
        ) {
            // 直書，由右往左讀
            lot.poem.reversed().forEachIndexed { i, line ->
                if (i > 0) Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(PoemCell * line.length).background(ink.copy(alpha = 0.22f)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    line.forEach { ch ->
                        Box(Modifier.size(30.dp, PoemCell), contentAlignment = Alignment.Center) {
                            Text(ch.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        if (lot.luck == "凶") Note("抽到凶別擔心～在淺草寺，大家會把籤綁在寺裡，把壞運留下再出發 🎀", "🍀", MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("再抽一支", Modifier.weight(1f), enabled = again, onClick = onAgain)
            CuteButton("看完整解籤 ›", Modifier.weight(1f), onClick = onMore)
        }
    }
}

private val PoemCell = 32.dp

@Composable
private fun LuckStamp(luck: String) {
    val c = luckColor(luck)
    Box(
        Modifier.rotate(-10f).size(64.dp).border(2.5.dp, c, CircleShape).padding(4.dp).border(1.dp, c.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            luck, color = c, fontWeight = FontWeight.Bold,
            style = if (luck.length >= 3) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
        )
    }
}

private const val DIGITS = "〇一二三四五六七八九"

/** 23 → 二十三、100 → 一百 */
internal fun chineseNumber(n: Int): String = when {
    n >= 100 -> "一百"
    n < 10 -> DIGITS[n].toString()
    else -> (if (n / 10 == 1) "" else DIGITS[n / 10].toString()) + "十" + (if (n % 10 == 0) "" else DIGITS[n % 10].toString())
}

/**
 * 淺草寺那種六角形籤筒：搖一搖，籤從上面的小洞跑出來。
 * 座標用 170×236 的設計單位（1 單位 = 1dp）
 */
@Composable
private fun OmikujiBox(rotation: Float, stickOut: Float, number: Int?, modifier: Modifier = Modifier) {
    val lacquer = Color(0xFFE25A47)
    val lacquerLight = Color(0xFFF07A66)
    val lacquerDark = Color(0xFFB83E2D)
    val gold = Color(0xFFF4C35A)
    val top = Color(0xFF9B4632)
    val hole = Color(0xFF4A1E16)
    val bamboo = Color(0xFFF2D7A0)
    val inkBrown = Color(0xFF6B3A1F)
    val cream = Color(0xFFFFF6DD)
    val shadow = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    val cheek = Color(0xFFFFA7B5)
    Box(
        modifier.size(170.dp, 236.dp).graphicsLayer {
            rotationZ = rotation
            transformOrigin = TransformOrigin(0.5f, 0.92f)
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 170f
            fun p(x: Float, y: Float) = Offset(x * u, y * u)
            fun poly(vararg xy: Float) = Path().apply {
                moveTo(xy[0] * u, xy[1] * u)
                for (i in 2 until xy.size step 2) lineTo(xy[i] * u, xy[i + 1] * u)
                close()
            }
            drawOval(shadow, p(32f, 220f), Size(106f * u, 14f * u))
            // 左、正、右三面
            drawPath(poly(30f, 92f, 52f, 104f, 52f, 224f, 30f, 212f), lacquerLight)
            drawPath(poly(52f, 104f, 118f, 104f, 118f, 224f, 52f, 224f), lacquer)
            drawPath(poly(118f, 104f, 140f, 92f, 140f, 212f, 118f, 224f), lacquerDark)
            // 上蓋和小洞
            drawPath(poly(30f, 92f, 52f, 80f, 118f, 80f, 140f, 92f, 118f, 104f, 52f, 104f), top)
            drawOval(hole, p(76f, 88f), Size(18f * u, 8f * u))
            // 金色的邊
            listOf(110f, 198f).forEach { y ->
                val band = Path().apply {
                    moveTo(30f * u, y * u); lineTo(52f * u, (y + 12f) * u); lineTo(118f * u, (y + 12f) * u); lineTo(140f * u, y * u)
                }
                drawPath(band, gold, style = Stroke(5f * u))
            }
            // 小臉
            drawCircle(inkBrown, 2.6f * u, p(74f, 190f))
            drawCircle(inkBrown, 2.6f * u, p(96f, 190f))
            drawOval(cheek.copy(alpha = 0.8f), p(62f, 193f), Size(8f * u, 4.5f * u))
            drawOval(cheek.copy(alpha = 0.8f), p(100f, 193f), Size(8f * u, 4.5f * u))
            drawArc(inkBrown, 20f, 140f, false, p(81f, 189f), Size(8f * u, 6f * u), style = Stroke(1.8f * u, cap = StrokeCap.Round))
        }
        // 正面的貼紙
        Column(
            Modifier.offset(70.dp, 128.dp).size(30.dp, 54.dp).clip(RoundedCornerShape(4.dp)).background(cream)
                .border(1.dp, lacquerDark.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            "觀音籤".forEach { Text(it.toString(), color = lacquerDark, fontSize = 12.sp, lineHeight = 15.sp) }
        }
        // 從小洞跑出來的籤
        Box(Modifier.offset(75.dp, 0.dp).size(20.dp, 92.dp).clipToBounds()) {
            Column(
                Modifier.offset(y = 92.dp - 66.dp * stickOut).size(20.dp, 100.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(bamboo),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(9.dp).background(lacquer))
                if (number != null) chineseNumber(number).forEach {
                    Text(it.toString(), color = inkBrown, fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
        }
        if (stickOut > 0.6f) {
            val a = ((stickOut - 0.6f) / 0.4f).coerceIn(0f, 1f)
            Text("✨", fontSize = 18.sp, modifier = Modifier.offset(40.dp, 30.dp).graphicsLayer { alpha = a })
            Text("✨", fontSize = 14.sp, modifier = Modifier.offset(108.dp, 46.dp).graphicsLayer { alpha = a })
        }
    }
}

/**
 * 只在這個畫面看得到、App 在前景時才聽加速度計；離開分頁或畫面就取消。
 * 回傳這支手機有沒有加速度計
 */
@Composable
private fun shakeToDraw(enabled: Boolean, onShake: () -> Unit): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sm = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val canShake by rememberUpdatedState(enabled)
    val shake by rememberUpdatedState(onShake)
    DisposableEffect(sm, sensor, lifecycle) {
        if (sm == null || sensor == null) return@DisposableEffect onDispose {}
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g > SHAKE_G && now - last > 1500 && canShake) {
                    last = now
                    shake()
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> sm.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sm.unregisterListener(listener)
        }
    }
    return sensor != null
}

/** 加速度超過重力的幾倍才算在搖 */
private const val SHAKE_G = 2.2f
