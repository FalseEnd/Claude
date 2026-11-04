package tw.xiaoliangkou.app.ui.screens

import tw.xiaoliangkou.app.ui.components.IllustratedIcon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tw.xiaoliangkou.app.core.Pet
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.PetDto
import tw.xiaoliangkou.app.data.PetEvent
import tw.xiaoliangkou.app.data.PetResponse
import tw.xiaoliangkou.app.data.PetResult
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CatFace
import tw.xiaoliangkou.app.ui.components.Confetti
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.HeartBurstOverlay
import tw.xiaoliangkou.app.ui.components.LoadingDots
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.PetCat
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.catFace
import tw.xiaoliangkou.app.ui.components.catFaceFor
import tw.xiaoliangkou.app.ui.components.rememberHeartBurst
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 現在幾點（測試換成固定的時間，才拍得到白天和晚上的房間） */
val LocalPetClock = staticCompositionLocalOf<() -> Instant> { { Instant.now() } }

/** 現在的時間，每分鐘更新：小貓的狀態會一直往下掉，畫面跟著算 */
@Composable
private fun rememberNow(): Instant {
    val clock = LocalPetClock.current
    val now by produceState(clock(), clock) {
        while (true) {
            delay(60_000)
            value = clock()
        }
    }
    return now
}

/** 資料庫的值往下算到 [at] */
internal fun petState(p: PetDto, at: Instant): Pet.State = Pet.decay(p.state(), at)

internal fun stageIndex(xp: Int) = Pet.stages.indexOf(Pet.stageOf(xp))

/** 照顧之後牠說的話 */
internal fun petReaction(action: String, item: String?, r: PetResult): String = when (action) {
    "feed" -> when {
        r.xp == 0 && !r.bonus -> "肚子飽飽，吃一點點就好～"
        item == "can" -> "罐罐！！最喜歡了"
        item == "fish" -> "小魚乾～再一條嘛"
        else -> "喀滋喀滋～好好吃！"
    }
    "play" -> when {
        r.xp == 0 && !r.bonus -> "今天已經玩得好開心了～"
        item == "laser" -> "紅點點在哪裡？！"
        item == "yarn" -> "毛線球滾來滾去～"
        else -> "抓到了！再來再來！"
    }
    "bath" -> "嗚…濕答答的，可是香香的"
    "brush" -> "呼嚕呼嚕～好舒服"
    else -> Pet.purrs.random().removeSuffix(" 💕")
}

/** 這次照顧得到的成長值（沒有就是 null，不用跳提示） */
internal fun petGain(r: PetResult): String? = listOfNotNull(
    "+${r.xp} 成長值".takeIf { r.xp > 0 },
    "今天的任務都完成了".takeIf { r.bonus },
    "連續照顧滿 ${Pet.STREAK_EVERY} 天的倍數".takeIf { r.streakBonus },
).joinToString("，").ifEmpty { null }

/** 照顧紀錄的一行字 */
internal fun petEventText(e: PetEvent, who: String, petName: String): String = when (e.action) {
    "adopt" -> "$who 領養了$petName（${Pet.coat(e.item).label}）"
    "feed" -> Pet.food(e.item)?.let { "$who 餵了${it.label}" } ?: "$who 餵牠吃飯"
    "play" -> Pet.toy(e.item)?.let { "$who 用${it.label}陪牠玩" } ?: "$who 陪牠玩"
    "pet" -> "$who 摸摸牠"
    "bath" -> "$who 幫牠洗澡"
    "brush" -> "$who 幫牠梳毛"
    "bonus" -> "完成今天的任務"
    "streak" -> "連續照顧 ${e.item} 天"
    "grow" -> "長大成${e.item}了！"
    "rename" -> "$who 幫牠改名叫「${e.item}」"
    "coat" -> "$who 把牠換成${Pet.coat(e.item).label}"
    else -> "$who 照顧了牠"
}

/** 連續的摸摸合成一行（摸了 8 次不要佔 8 行）：(紀錄, 次數) */
internal fun groupPetEvents(events: List<PetEvent>): List<Pair<PetEvent, Int>> {
    val out = mutableListOf<Pair<PetEvent, Int>>()
    events.forEach { e ->
        val last = out.lastOrNull()
        if (last != null && e.action == "pet" && last.first.action == "pet" && last.first.userId == e.userId) {
            out[out.lastIndex] = last.first to last.second + 1
        } else {
            out += e to 1
        }
    }
    return out
}

private fun who(s: AppState, userId: String?) = when (userId) {
    null -> "有人"
    s.myId -> s.me?.displayName ?: "我"
    else -> s.partnerName
}

// ================================================================ 首頁的小貓

/** 首頁：還沒領養是邀請卡；有了就看牠的心情、三個狀態和今天有沒有人餵，點牠一下摸摸 */
@Composable
fun HomePetCard(s: AppState, vm: AppViewModel, open: (String) -> Unit) {
    if (!s.paired) return
    if (!s.petReady) {
        if (s.petOutdated) CuteCard(onClick = { open("account") }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Cute.colors.butter.copy(alpha = 0.25f))) {
                    PetCat("orange", 0, CatFace.HAPPY, Modifier.fillMaxSize().padding(4.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("一起養一隻小貓", style = MaterialTheme.typography.titleMedium)
                    TidyText("再更新一次資料庫就能養了（我的 › Supabase 與帳號）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val bundle = s.pet ?: return
    val pet = bundle.pet
    if (pet == null) {
        CuteCard(onClick = { open("pet") }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(92.dp).clip(RoundedCornerShape(24.dp)).background(Cute.colors.butter.copy(alpha = 0.25f))) {
                    PetCat("orange", 0, CatFace.HAPPY, Modifier.fillMaxSize().padding(6.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("領養一隻小貓吧", style = MaterialTheme.typography.titleMedium)
                    TidyText("兩個人每天一起餵牠、陪牠玩，看牠從小奶貓長成大胖貓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SoftButton("去領養", small = true) { open("pet") }
                }
            }
        }
        return
    }
    val now = rememberNow()
    val st = petState(pet, now)
    val mood = Pet.mood(st, now)
    val stage = st.stage
    var bounce by remember { mutableIntStateOf(0) }
    val hearts = rememberHeartBurst()
    CuteCard(onClick = { open("pet") }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)).background(Cute.colors.butter.copy(alpha = 0.25f))
                    .clickable(remember { MutableInteractionSource() }, null) {
                        bounce++
                        hearts.burst(0.5f, 0.5f, 8)
                        vm.act { it.petAction("pet", null) }
                    }
                    .semantics { contentDescription = "摸摸${pet.name}" },
            ) {
                PetCat(pet.coat, stageIndex(st.xp), catFace(mood), Modifier.fillMaxSize().padding(6.dp), bounce = bounce)
                HeartBurstOverlay(hearts)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TidyText(pet.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Pill(stage.label, Cute.colors.butter.copy(alpha = 0.35f))
                }
                TidyText(mood.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                PetBar("🍗", st.fullness, FULL, compact = true)
                PetBar("💖", st.happiness, HAPPY, compact = true)
                PetBar("✨", st.cleanliness, CLEAN, compact = true)
            }
        }
        val today = bundle.today
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TidyText(
                if (today.feeds == 0) "今天還沒有人餵${pet.name}" else "今天 ${tick(today.feeds > 0)} 餵飯 ${tick(today.plays > 0)} 陪玩 ${tick(today.pets >= Pet.TASK_PETS)} 摸摸 ${today.pets.coerceAtMost(Pet.TASK_PETS)}/${Pet.TASK_PETS}",
                style = MaterialTheme.typography.labelLarge,
                color = if (today.feeds == 0) Cute.colors.bad else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SoftButton(if (today.feeds == 0 || st.fullness < 40) "去餵牠" else "照顧牠", small = true) { open("pet") }
        }
    }
}

private fun tick(done: Boolean) = if (done) "✓" else "·"

private val FULL = Color(0xFFFFA26B)
private val HAPPY = Color(0xFFFF7FA8)
private val CLEAN = Color(0xFF6FB8F2)

/** 一條狀態：[emoji] 標示、[value] 0～100；太低變紅色 */
@Composable
private fun PetBar(emoji: String, value: Double, color: Color, label: String? = null, compact: Boolean = false) {
    val v by animateFloatAsState((value / 100.0).toFloat().coerceIn(0f, 1f), tween(600), label = "bar")
    val low = value < 25
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IllustratedIcon(emoji, Modifier.size(if (compact) 18.dp else 24.dp), label)
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        Box(Modifier.weight(1f).height(if (compact) 8.dp else 12.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(v).height(if (compact) 8.dp else 12.dp).clip(RoundedCornerShape(50)).background(if (low) Cute.colors.bad else color))
        }
        if (!compact) Text("${value.toInt()}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
    }
}

// ================================================================ 小貓的頁面

@Composable
fun PetScreen(s: AppState, vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val bundle = s.pet
    val pet = bundle?.pet
    var prop by remember { mutableStateOf<String?>(null) }
    var say by remember { mutableStateOf<String?>(null) }
    var bounce by remember { mutableIntStateOf(0) }
    var confetti by remember { mutableIntStateOf(0) }
    var grown by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val hearts = rememberHeartBurst()
    // 拿出來的碗、玩具過一下收起來；說的話也是
    LaunchedEffect(prop, bounce) { if (prop != null) { delay(3200); prop = null } }
    LaunchedEffect(say, bounce) { if (say != null) { delay(3600); say = null } }

    fun care(action: String, item: String?) {
        picker = null
        // 摸摸馬上有反應（跳一下、冒愛心），其他的等資料庫答應了再動
        if (action == "pet") { hearts.burst(0.5f, 0.55f, 12); bounce++ }
        vm.act { r ->
            val res = r.petAction(action, item)
            prop = item ?: action
            if (action != "pet") bounce++
            say = petReaction(action, item, res)
            petGain(res)?.let { vm.toast(it) }
            if (res.grewTo != null) { confetti++; grown = res.grewTo }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { ScreenHeader("我們的貓咪", onBack = back) }
            when {
                !s.paired -> item { EmptyState("配對之後就能一起養小貓", "🐾") }
                !s.petReady -> item {
                    CuteCard {
                        Text("一起養一隻小貓", style = MaterialTheme.typography.titleMedium)
                        TidyText("資料庫還沒有小貓：到「我的 › Supabase 與帳號」再執行一次 upgrade-v2.sql 就能養了。兩個人其中一個做就好。")
                        SoftButton("怎麼更新", small = true) { open("account") }
                    }
                }
                bundle == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingDots() } }
                pet == null -> item { AdoptCard(s, vm) }
                else -> {
                    item { PetRoom(pet, s, prop, say, bounce, hearts) { care("pet", null) } }
                    // 按筆：名字和品種的卡片直接換成可以改的（不用對話框，15 種貓排得開）
                    item { if (renaming) EditPetCard(s, pet, vm) { renaming = false } else PetStatusCard(pet, s) { renaming = true } }
                    item { CareCard(pet, bundle, picker, vm.busy, { picker = if (picker == it) null else it }, ::care) }
                    item { TasksCard(pet, bundle) }
                    item { PetHistoryCard(s, pet, bundle) }
                    item { Note("每天都要有人餵牠，餓太久會不開心。晚上 8 點還沒有人餵、牠又餓了，會提醒你們兩個。連續每天都有人餵，每滿 ${Pet.STREAK_EVERY} 天多 ${Pet.STREAK_BONUS} 成長值。", "🐾") }
                }
            }
        }
        Confetti(confetti)
    }
    grown?.let { stage ->
        val st = Pet.stages.firstOrNull { it.label == stage }
        AlertDialog(
            onDismissRequest = { grown = null },
            confirmButton = { TextButton({ grown = null }) { Text("好棒！") } },
            title = { Text("${pet?.name ?: "牠"} 長大了！") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PetCat(pet?.coat ?: "orange", st?.let { Pet.stages.indexOf(it) } ?: 1, CatFace.HAPPY, Modifier.size(140.dp))
                    TidyText("現在是${stage}，謝謝你們的照顧", textAlign = TextAlign.Center)
                }
            },
        )
    }
}

/** 牠的房間：白天有太陽、晚上有月亮和星星；點牠摸摸 */
@Composable
private fun PetRoom(pet: PetDto, s: AppState, prop: String?, say: String?, bounce: Int, hearts: tw.xiaoliangkou.app.ui.components.HeartBurstState, onPet: () -> Unit) {
    val now = rememberNow()
    val st = petState(pet, now)
    val mood = Pet.mood(st, now)
    val hour = now.atZone(Pet.TAIPEI).hour
    val night = hour >= 19 || hour < 6
    val face = catFaceFor(prop) ?: catFace(mood)
    Box(
        Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onPet)
            .semantics { contentDescription = "摸摸${pet.name}" },
    ) {
        RoomBackground(night)
        PetCat(pet.coat, stageIndex(st.xp), face, Modifier.fillMaxSize().padding(top = 70.dp, bottom = 14.dp), prop = prop, bounce = bounce)
        HeartBurstOverlay(hearts)
        // 牠說的話（沒有的話是現在的心情）
        val text = say ?: mood.text
        TidyText(
            text,
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall, color = Color(0xFF4A2B39), textAlign = TextAlign.Center, maxLines = 2,
        )
        Pill("點牠摸摸", Color.White.copy(alpha = 0.75f), Color(0xFF4A2B39), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

@Composable
private fun RoomBackground(night: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val wall = if (night) listOf(Color(0xFF3E3A6E), Color(0xFF6A5A96)) else listOf(Color(0xFFFFF4E4), Color(0xFFFFE2EA))
        drawRect(Brush.verticalGradient(wall))
        // 窗戶
        val win = Offset(size.width * 0.08f, size.height * 0.1f)
        val ws = Size(size.width * 0.26f, size.height * 0.36f)
        drawRoundRect(Color(0xFFFFFFFF).copy(alpha = 0.9f), Offset(win.x - 6f, win.y - 6f), Size(ws.width + 12f, ws.height + 12f), androidx.compose.ui.geometry.CornerRadius(18f))
        drawRoundRect(if (night) Color(0xFF26245A) else Color(0xFFBFE6FF), win, ws, androidx.compose.ui.geometry.CornerRadius(14f))
        if (night) {
            drawCircle(Color(0xFFFFF2B3), ws.width * 0.16f, Offset(win.x + ws.width * 0.66f, win.y + ws.height * 0.3f))
            drawCircle(Color(0xFF26245A), ws.width * 0.14f, Offset(win.x + ws.width * 0.6f, win.y + ws.height * 0.26f))
            listOf(0.2f to 0.2f, 0.35f to 0.6f, 0.8f to 0.7f, 0.15f to 0.8f).forEach { (x, y) ->
                drawCircle(Color.White, 3.5f, Offset(win.x + ws.width * x, win.y + ws.height * y))
            }
        } else {
            drawCircle(Color(0xFFFFD45E), ws.width * 0.15f, Offset(win.x + ws.width * 0.7f, win.y + ws.height * 0.28f))
            drawOval(Color.White, Offset(win.x + ws.width * 0.1f, win.y + ws.height * 0.55f), Size(ws.width * 0.5f, ws.height * 0.16f))
            drawOval(Color.White, Offset(win.x + ws.width * 0.22f, win.y + ws.height * 0.47f), Size(ws.width * 0.3f, ws.height * 0.16f))
        }
        drawLine(Color.White, Offset(win.x + ws.width / 2, win.y), Offset(win.x + ws.width / 2, win.y + ws.height), 6f)
        drawLine(Color.White, Offset(win.x, win.y + ws.height / 2), Offset(win.x + ws.width, win.y + ws.height / 2), 6f)
        // 牆上的小相框（兩顆愛心）
        val fx = size.width * 0.78f
        val fy = size.height * 0.14f
        drawRoundRect(Color(0xFFFFFFFF).copy(alpha = 0.85f), Offset(fx, fy), Size(size.width * 0.14f, size.height * 0.16f), androidx.compose.ui.geometry.CornerRadius(10f))
        drawPath(tw.xiaoliangkou.app.ui.components.heartPath(fx + size.width * 0.07f, fy + size.height * 0.08f, size.height * 0.05f), Color(0xFFFF7FA8))
        // 地板和地毯
        val floorY = size.height * 0.74f
        drawRect(if (night) Color(0xFF8E7091) else Color(0xFFF2D1B0), Offset(0f, floorY), Size(size.width, size.height - floorY))
        drawLine(if (night) Color(0xFF7A5E7E) else Color(0xFFE6BD97), Offset(0f, floorY), Offset(size.width, floorY), 4f)
        drawOval(if (night) Color(0xFFB98FC9) else Color(0xFFFFC2D4), Offset(size.width * 0.2f, size.height * 0.84f), Size(size.width * 0.6f, size.height * 0.14f))
    }
}

/** 名字、長大到哪、三個狀態、成長值 */
@Composable
private fun PetStatusCard(pet: PetDto, s: AppState, rename: () -> Unit) {
    val now = rememberNow()
    val st = petState(pet, now)
    val stage = st.stage
    val next = Pet.nextStage(st.xp)
    val days = pet.adopted?.let { ChronoUnit.DAYS.between(Pet.localDay(it), Pet.localDay(now)) + 1 } ?: 1
    val streak = Pet.streakNow(st, Pet.localDay(now))
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TidyText(pet.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TidyText(
                    "${Pet.coat(pet.coat).label} · ${who(s, pet.adoptedBy)} 領養的 · 第 $days 天",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(rename) { Icon(Icons.Rounded.Edit, "改名字、換品種") }
        }
        ButtonRow(spacing = 6.dp) {
            Pill(stage.label, Cute.colors.butter.copy(alpha = 0.35f))
            Pill(if (streak > 0) "連續照顧 $streak 天" else "今天開始連續照顧", Cute.colors.peach.copy(alpha = 0.3f))
        }
        PetBar("🍗", st.fullness, FULL, "飽足")
        PetBar("💖", st.happiness, HAPPY, "開心")
        PetBar("✨", st.cleanliness, CLEAN, "乾淨")
        // 成長值：從這個階段到下一個階段
        val from = stage.xp
        val progress = if (next == null) 1f else ((st.xp - from).toFloat() / (next.xp - from)).coerceIn(0f, 1f)
        val p by animateFloatAsState(progress, tween(800), label = "grow")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("成長值 ${st.xp}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TidyText(
                    if (next == null) "已經是最大隻的大胖貓了" else "再 ${next.xp - st.xp} 長成${next.label}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(p).height(14.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Cute.colors.butter, Cute.colors.partner))))
            }
            // 長大的路線：小奶貓 → … → 大胖貓
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Pet.stages.forEach { x ->
                    IllustratedIcon(x.emoji, Modifier.size(26.dp).alpha(if (st.xp >= x.xp) 1f else 0.3f), x.label)
                }
            }
        }
    }
}

/** 照顧牠：餵食、陪玩、摸摸、洗澡、梳毛；餵食和陪玩再選一種 */
@Composable
private fun CareCard(pet: PetDto, bundle: PetResponse, picker: String?, busy: Boolean, toggle: (String) -> Unit, care: (String, String?) -> Unit) {
    CuteCard {
        SectionTitle("照顧${pet.name}")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CareTile("🍚", "餵食", picker == "feed", Modifier.weight(1f)) { toggle("feed") }
            CareTile("🧶", "陪玩", picker == "play", Modifier.weight(1f)) { toggle("play") }
            CareTile("🤚", "摸摸", false, Modifier.weight(1f)) { care("pet", null) }
            CareTile("🛁", "洗澡", false, Modifier.weight(1f)) { care("bath", null) }
            CareTile("💆", "梳毛", false, Modifier.weight(1f)) { care("brush", null) }
        }
        val items = when (picker) { "feed" -> Pet.foods; "play" -> Pet.toys; else -> emptyList() }
        items.forEach { item ->
            val left = Pet.TREATS_PER_DAY - bundle.today.treats
            val effect = listOfNotNull(
                item.fullness.takeIf { it != 0 }?.let { "飽足 ${signed(it)}" },
                item.happiness.takeIf { it != 0 }?.let { "開心 ${signed(it)}" },
                item.cleanliness.takeIf { it != 0 }?.let { "乾淨 ${signed(it)}" },
            ).joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .bouncyClick(enabled = !busy) { care(if (picker == "feed") "feed" else "play", item.key) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EmojiBubble(item.emoji, Cute.colors.card, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                    TidyText(effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.key == "fish") Pill("零食，今天還能給 ${left.coerceAtLeast(0)} 次", if (left > 0) Cute.colors.mint.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        if (picker == null) TidyText(
            "餓的時候餵、無聊的時候陪玩才會長大；吃太飽、太乾淨的時候牠會拒絕喔",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

@Composable
private fun CareTile(emoji: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
            .bouncyClick(haptic = true, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EmojiBubble(emoji, Cute.colors.butter.copy(alpha = 0.3f), 44.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** 今天的任務：餵飯、陪玩、摸摸 3 次 */
@Composable
private fun TasksCard(pet: PetDto, bundle: PetResponse) {
    val t = bundle.today
    val done = t.core().tasksDone
    CuteCard {
        SectionTitle("今天的任務")
        TaskRow("🍚", "有人餵${pet.name}吃飯", t.feeds > 0, if (t.feeds > 0) "${t.feeds} 次" else null)
        TaskRow("🧶", "陪牠玩", t.plays > 0, if (t.plays > 0) "${t.plays} 次" else null)
        TaskRow("🤚", "摸摸牠 ${Pet.TASK_PETS} 次", t.pets >= Pet.TASK_PETS, "${t.pets.coerceAtMost(Pet.TASK_PETS)}/${Pet.TASK_PETS}")
        Note(
            if (done) "今天的任務完成了！多了 ${Pet.TASK_BONUS} 成長值" else "三個都做完多 ${Pet.TASK_BONUS} 成長值，兩個人一起做也算",
            if (done) "🏆" else "✨",
            if (done) Cute.colors.mint.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(emoji: String, text: String, done: Boolean, count: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IllustratedIcon(if (done) "✅" else "⬜", Modifier.size(24.dp), if (done) "已完成" else "待完成")
        IllustratedIcon(emoji, Modifier.size(26.dp))
        TidyText(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 最近誰做了什麼 */
@Composable
private fun PetHistoryCard(s: AppState, pet: PetDto, bundle: PetResponse) {
    val rows = groupPetEvents(bundle.events).take(12)
    val now = rememberNow()
    CuteCard {
        SectionTitle("最近的照顧")
        rows.forEach { (e, n) ->
            val mine = e.userId == s.myId
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (e.userId == null) Cute.colors.butter else if (mine) Cute.colors.me else Cute.colors.partner))
                Column(Modifier.weight(1f)) {
                    TidyText(petEventText(e, who(s, e.userId), pet.name) + if (n > 1) " ×$n" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(Fmt.rel(e.time, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val xp = if (n > 1) null else e.xp.takeIf { it > 0 }
                if (xp != null) Pill("+$xp", Cute.colors.butter.copy(alpha = 0.35f))
            }
        }
    }
}

/** 領養：選毛色、取名字 */
@Composable
private fun AdoptCard(s: AppState, vm: AppViewModel) {
    var coat by remember { mutableStateOf(Pet.coats.first().key) }
    var name by remember { mutableStateOf("") }
    val valid = Pet.cleanName(name.ifBlank { "咪咪" }) != null
    CuteCard {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(22.dp)).background(Cute.colors.butter.copy(alpha = 0.25f))) {
            PetCat(coat, 0, CatFace.HAPPY, Modifier.fillMaxSize().padding(top = 10.dp, bottom = 6.dp))
        }
        Text("領養一隻小貓", style = MaterialTheme.typography.titleLarge)
        TidyText("你和 ${s.partnerName} 一起養，兩個人都能餵牠、陪牠玩。每天都要有人來看牠，牠會從小奶貓慢慢長成大胖貓。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("選一隻", style = MaterialTheme.typography.titleSmall)
        CoatGrid(s, vm, coat) { coat = it }
        CuteField(name, { name = it }, "幫牠取名字", placeholder = "例如：麻糬、湯圓（沒填就叫咪咪）", supporting = "最多 ${Pet.NAME_MAX} 個字，之後也可以改", isError = !valid)
        CuteButton("領養${Pet.coat(coat).label}", Modifier.fillMaxWidth(), enabled = valid && !vm.busy) {
            vm.act("歡迎回家！", hearts = true) { it.adoptPet(name.ifBlank { "咪咪" }, coat) }
        }
    }
}

/** 選哪一種貓：一排三隻；資料庫還存不了的品種（schema 9 以前）鎖起來，點了說要更新 */
@Composable
private fun CoatGrid(s: AppState, vm: AppViewModel, selected: String, stage: Int = 0, onSelect: (String) -> Unit) {
    ButtonRow(maxPerRow = 3, spacing = 8.dp) {
        Pet.coats.forEach { c ->
            val ready = s.canPickCoat(c)
            CoatChoice(c, selected == c.key, stage, locked = !ready) {
                if (ready) onSelect(c.key) else vm.toast("再更新一次資料庫就能選${c.label}了（我的 › Supabase 與帳號）")
            }
        }
    }
    if (Pet.coats.any { !s.canPickCoat(it) }) {
        TidyText("尚未開放的品種要再執行一次 upgrade-v2.sql 才能選", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoatChoice(c: Pet.Coat, selected: Boolean, stage: Int, locked: Boolean, size: Dp = 84.dp, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
            .bouncyClick(onClick = onClick).padding(6.dp)
            .alpha(if (locked) 0.45f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PetCat(c.key, stage, if (selected) CatFace.HAPPY else CatFace.OK, Modifier.fillMaxWidth().height(size * 0.8f), animated = false)
        Text(if (locked) "${c.label}（未開放）" else c.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 改名字、換品種：上面預覽改好的樣子（牠現在的大小） */
@Composable
private fun EditPetCard(s: AppState, pet: PetDto, vm: AppViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf(pet.name) }
    var coat by remember { mutableStateOf(pet.coat) }
    val valid = Pet.cleanName(name) != null
    val stage = stageIndex(pet.xp)
    CuteCard {
        Text("幫${pet.name}改名字、換品種", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(22.dp)).background(Cute.colors.butter.copy(alpha = 0.25f))) {
            PetCat(coat, stage, CatFace.HAPPY, Modifier.fillMaxSize().padding(top = 8.dp, bottom = 6.dp))
        }
        CuteField(name, { name = it }, "名字", supporting = "最多 ${Pet.NAME_MAX} 個字", isError = !valid)
        Text("牠是哪一種貓", style = MaterialTheme.typography.titleSmall)
        CoatGrid(s, vm, coat, stage) { coat = it }
        ButtonRow {
            SoftButton("取消", small = true) { close() }
            CuteButton("好了", small = true, enabled = valid && !vm.busy) { vm.act("改好了", onDone = close) { it.editPet(name, coat) } }
        }
    }
}
