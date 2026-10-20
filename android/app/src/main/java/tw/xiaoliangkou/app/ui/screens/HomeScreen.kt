package tw.xiaoliangkou.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Comfort
import tw.xiaoliangkou.app.data.DeviceStatus
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.Mood
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.service.DeviceProbe
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.BatteryPill
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CountUpText
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.StickerImage
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.unscaled
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

@Composable
fun HomeScreen(s: AppState, vm: AppViewModel, open: (String) -> Unit, sheet: (SheetSpec) -> Unit) {
    // 收到、還沒看過的安撫（最新的一則）：放在手機狀態上面，一打開就看得到
    val received = s.comforts.firstOrNull { it.receiverId == s.myId && it.readAt == null }
    // 每一項都有 key：安撫的卡片出現、消失時，畫面不會跳
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "top") { TopBar(s, open) }
        item(key = "hero") { Hero(s) }
        if (s.mySharing?.paused == true) item(key = "paused") {
            Banner("🙈", "你暫停分享到 ${Fmt.time(s.mySharing.pausedUntilInstant!!)}，${s.partnerName} 看得到你暫停了。", Cute.colors.butter.copy(alpha = 0.35f)) {
                SoftButton("恢復", small = true) { vm.act("已恢復分享") { it.pauseSharing(0) } }
            }
        }
        if (received != null) item(key = "received") { ReceivedComfort(s, vm, received) }
        item(key = "phone") { PhoneCard(s, vm, open) }
        item(key = "mood") { MoodCard(s, open, sheet) }
        item(key = "quick") { QuickActions(s, open, sheet) }
        careBanner(s)?.let { (emoji, text) -> item(key = "care") { Banner(emoji, text, Cute.colors.periodSoft) { SoftButton("看看", small = true) { open("cycle") } } } }
        item(key = "upcoming") { Upcoming(s, open) }
        item(key = "recent") { Recent(s, open) }
    }
}

/** 對方傳來的安撫：誰、說了什麼、附的貼圖和悄悄話；回抱一下或「收到了」就收起來 */
@Composable
private fun ReceivedComfort(s: AppState, vm: AppViewModel, c: Comfort) {
    val kind = Catalog.comfort(c.kind)
    val sticker = c.sticker?.takeIf(Giphy::isValidId)
    val message = c.message?.trim()?.takeIf { it.isNotEmpty() }
    CuteCard(color = Cute.colors.partnerSoft) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(s.partnerName, Cute.colors.partner, 40.dp, image = s.partner?.avatarUrl)
            Column(Modifier.weight(1f)) {
                TidyText("${s.partnerName} ${kind?.push ?: "安撫了你"} ${kind?.emoji ?: "🤗"}", style = MaterialTheme.typography.titleSmall)
                Text(Fmt.rel(c.createdAt.toInstantOrNull()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (sticker != null) StickerImage(sticker, Modifier.size(120.dp))
        if (message != null) TidyText(
            message, Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cute.colors.card.copy(alpha = 0.8f)).padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        ButtonRow {
            CuteButton("回抱 🤗", small = true, enabled = !vm.busy) {
                vm.act("已經抱抱 ${s.partnerName} 🤗", hearts = true) { it.sendComfort("hug", null); it.readComfort(c.id) }
            }
            SoftButton("收到了", small = true, enabled = !vm.busy) { vm.act { it.readComfort(c.id) } }
        }
    }
}

@Composable
private fun TopBar(s: AppState, open: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeartBeat { Text("💞", style = MaterialTheme.typography.headlineSmall) }
        Text("小倆口", style = MaterialTheme.typography.headlineSmall)
        if (s.demo) Pill("示範模式", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else if (!s.connected) Pill("連線中…", Cute.colors.butter.copy(alpha = 0.4f))
        Box(Modifier.weight(1f))
        val unread = s.comforts.count { it.receiverId == s.myId && it.readAt == null }.coerceAtMost(9)
        IconButton(onClick = { open("history") }) {
            BadgedBox(badge = { if (unread > 0 && !s.demo) Badge { Text("$unread") } }) {
                Icon(Icons.Rounded.Notifications, "歷史紀錄", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Hero(s: AppState) {
    val since = s.togetherSince
    val days = since?.let { Dates.daysTogether(it, LocalDate.now()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(Cute.colors.partnerSoft, MaterialTheme.colorScheme.background)))
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoupleMascots(partnerSad = Derived.partnerNeedsComfort(s), height = 120.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Text(s.me?.displayName ?: "我", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(s.partnerName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (days != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("在一起", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                CountUpText(days, MaterialTheme.typography.displayLarge, prefix = "第 ", suffix = " 天", color = MaterialTheme.colorScheme.primary)
            }
        }
        val loc = if (s.partnerSharing?.locationLive == true) {
            listOfNotNull("${s.partnerName} ${Derived.partnerWhere(s)}", Derived.distanceToPartner(s)?.let { "相距 ${Fmt.distance(it)}" }).joinToString(" · ")
        } else if (s.partnerSharing?.paused == true) "${s.partnerName} 暫停分享到 ${Fmt.time(s.partnerSharing.pausedUntilInstant!!)}"
        else "${s.partnerName} 沒有分享位置"
        Text(loc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Banner(emoji: String, text: String, color: Color, action: @Composable () -> Unit) {
    // 表情、一段話、右邊的按鈕排一排；字放大時話被按鈕擠到四行以上，就把按鈕移到話的下面（靠右），話用整個寬度
    Layout(
        content = {
            Text(emoji, style = MaterialTheme.typography.titleLarge.unscaled())
            TidyText(text, style = MaterialTheme.typography.bodyMedium)
            Box { action() }
        },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color).padding(14.dp),
    ) { (emojiM, textM, actionM), c ->
        val gap = 10.dp.roundToPx()
        val w = c.maxWidth
        val e = emojiM.measure(Constraints())
        val a = actionM.measure(Constraints(maxWidth = w))
        val beside = (w - e.width - a.width - gap * 2).coerceAtLeast(0)
        val oneLine = textM.minIntrinsicHeight(Constraints.Infinity).coerceAtLeast(1)
        val stack = textM.minIntrinsicHeight(beside) > oneLine * 3
        val t = textM.measure(Constraints(maxWidth = if (stack) (w - e.width - gap).coerceAtLeast(0) else beside))
        val top = maxOf(e.height, t.height, if (stack) 0 else a.height)
        val h = if (stack) top + 8.dp.roundToPx() + a.height else top
        layout(w, h) {
            e.placeRelative(0, (top - e.height) / 2)
            t.placeRelative(e.width + gap, (top - t.height) / 2)
            if (stack) a.placeRelative(w - a.width, h - a.height) else a.placeRelative(w - a.width, (top - a.height) / 2)
        }
    }
}

/** 手機狀態：對方分享的，和我自己的（直接從這支手機讀，不用分享），上面切換 */
@Composable
private fun PhoneCard(s: AppState, vm: AppViewModel, open: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("partner") }
    CuteCard {
        Segmented(listOf("partner" to s.partnerName, "me" to "我"), tab) { tab = it }
        if (tab == "me") MyPhone(s, vm, open) else PartnerPhone(s, vm)
    }
}

@Composable
private fun PartnerPhone(s: AppState, vm: AppViewModel) {
    val ps = s.partnerSharing
    val d = s.partnerStatus.device
    PhoneHeader("${s.partnerName} 的手機", d) {
        Box {
            Avatar(s.partnerName, Cute.colors.partner, 34.dp, image = s.partner?.avatarUrl)
            if (s.partnerOnline) Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(12.dp).clip(CircleShape).background(Cute.colors.card).padding(2.dp).clip(CircleShape).background(Cute.colors.good))
        }
    }
    if (ps?.deviceLive != true || d == null) {
        Text(
            when {
                ps?.paused == true -> "${s.partnerName} 暫停分享到 ${Fmt.time(ps.pausedUntilInstant!!)}，時間到會自動恢復 🙈"
                ps?.shareDevice != true -> "${s.partnerName} 目前沒有分享手機狀態"
                else -> "還沒收到手機狀態，等對方打開小倆口 📡"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val lastActive = d.lastActiveAt?.toInstantOrNull()
    val screenOn = lastActive != null && java.time.Duration.between(lastActive, java.time.Instant.now()).seconds < 120
    // 兩欄：字放大時數值、說明也放得下（三欄時「行動網路」會被擠成「行…」）
    TileRow { m ->
        BatteryTile(d, m)
        NetworkTile(d, m)
    }
    TileRow { m ->
        StatTile(
            "螢幕", if (screenOn) "使用中" else "休息中", m,
            sub = if (d.notificationsSilenced == true) "勿擾中 🔕" else Fmt.rel(lastActive),
            labelIcon = { Icon(if (d.notificationsSilenced == true) Icons.Rounded.DoNotDisturbOn else if (screenOn) Icons.Rounded.PhoneAndroid else Icons.Rounded.Bedtime, null, tint = Cute.colors.lavender, modifier = Modifier.size(14.dp)) },
        )
        // 沒有步數（手機沒有計步器、沒給權限，或今天還沒回報）：說清楚，不要只寫「— 步」
        StatTile("今日步數", d.stepsToday?.let { Fmt.int(it) } ?: "—", m, sub = if (d.stepsToday != null) "👣 步" else "手機沒回報")
    }
    val act = s.partnerStatus.location?.activity
    StatTile(
        "位置", if (ps.locationLive) Derived.partnerWhere(s) else "沒有分享位置", Modifier.fillMaxWidth(),
        sub = Derived.distanceToPartner(s)?.let { "距離你 ${Fmt.distance(it)}" } ?: "—",
        labelIcon = { Icon(when (act) { "walking", "running" -> Icons.AutoMirrored.Rounded.DirectionsWalk; "transit" -> Icons.Rounded.Train; else -> Icons.Rounded.LocationOn }, null, tint = Cute.colors.partner, modifier = Modifier.size(14.dp)) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TidyText("更新於 ${Fmt.rel(d.updatedAt?.toInstantOrNull())}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ps.locationLive) SoftButton("現在在哪？", icon = Icons.Rounded.MyLocation, small = true) {
                vm.act { r -> vm.toast(if (r.locatePartner()) "已請 ${s.partnerName} 的手機回報位置 📍" else "${s.partnerName} 的手機目前不在線上") }
            }
        }
        TidyText(
            if (s.deviceInfoOutdated) "只顯示對方選擇分享的項目 · 資料庫再更新一次就能看到手機名稱和電信" else "只顯示對方選擇分享的項目",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 我的手機：直接從這支手機讀（每 30 秒更新），沒分享、沒網路也看得到 */
@Composable
private fun MyPhone(s: AppState, vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val myId = s.myId.orEmpty()
    LaunchedEffect(myId, lifecycle) {
        // 示範模式（和還沒登入）用示範的手機，不用一直讀
        if (vm.repo.value !is RemoteRepository) return@LaunchedEffect
        // 只在看得到畫面時讀：切到背景就停，回到 App 馬上讀一次
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val r = withContext(Dispatchers.IO) { DeviceProbe.read(context) }
                val steps = vm.container.settings.stepsSoFar(LocalDate.now().toEpochDay())
                vm.repo.value?.setMyDevice(DeviceProbe.status(myId, r, steps))
                delay(30_000)
            }
        }
    }
    val d = s.myDevice
    PhoneHeader("我的手機", d) { Avatar(s.me?.displayName ?: "我", Cute.colors.me, 34.dp, image = s.me?.avatarUrl) }
    if (d == null) {
        Text("讀取中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    TileRow { m ->
        BatteryTile(d, m)
        NetworkTile(d, m)
    }
    TileRow { m ->
        val silenced = d.notificationsSilenced == true
        StatTile(
            "勿擾", if (silenced) "開著" else "關著", m, sub = if (silenced) "通知不會響 🔕" else "通知會響",
            labelIcon = { Icon(if (silenced) Icons.Rounded.DoNotDisturbOn else Icons.Rounded.Notifications, null, tint = Cute.colors.lavender, modifier = Modifier.size(14.dp)) },
        )
        val noPermission = Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        StatTile(
            "今日步數", d.stepsToday?.let { Fmt.int(it) } ?: "—", m,
            sub = when {
                d.stepsToday != null -> "👣 步"
                noPermission -> "要打開「體能活動」權限"
                else -> "走幾步就會出現"
            },
        )
    }
    val sh = s.mySharing
    val shared = listOfNotNull("手機狀態".takeIf { sh?.deviceLive == true }, "位置".takeIf { sh?.locationLive == true })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TidyText(
            when {
                sh?.paused == true -> "你暫停分享中，${s.partnerName} 現在看不到這些"
                shared.isEmpty() -> "你沒有分享手機狀態和位置，${s.partnerName} 看不到這些"
                else -> "${s.partnerName} 看得到你的${shared.joinToString("和")}"
            },
            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SoftButton("分享設定", small = true) { open("privacy") }
    }
}

/** 誰的手機，下面一行手機名稱 · 型號，一行電信 */
@Composable
private fun PhoneHeader(title: String, d: DeviceStatus?, avatar: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        avatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            TidyText(title, style = MaterialTheme.typography.titleMedium)
            val phone = listOfNotNull(d?.deviceName, d?.deviceModel).distinct().joinToString(" · ")
            if (phone.isNotEmpty()) InfoLine(Icons.Rounded.PhoneAndroid, phone)
            d?.carrier?.let { InfoLine(Icons.Rounded.SimCard, it) }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        TidyText(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 同一排的小卡一樣高 */
@Composable
private fun TileRow(content: @Composable RowScope.(Modifier) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content(Modifier.fillMaxHeight().weight(1f))
    }
}

@Composable
private fun BatteryTile(d: DeviceStatus, modifier: Modifier) {
    val level = d.batteryLevel ?: 0
    val charging = d.isCharging == true
    StatTile("電量", d.batteryLevel?.let { "$it%" } ?: "—", modifier, sub = if (charging) "充電中 ⚡" else if (level <= 20) "電量偏低 🔋" else "未充電", extra = { BatteryPill(level, charging) })
}

/** 網路：Wi-Fi 或行動網路，下面是所在的標點或網速（電信寫在上面的手機資訊裡，連 Wi-Fi 時也看得到） */
@Composable
private fun NetworkTile(d: DeviceStatus, modifier: Modifier) {
    StatTile(
        "網路", when (d.network) { "wifi" -> "Wi-Fi"; "cellular" -> "行動網路"; else -> "離線" }, modifier,
        sub = d.networkLabel ?: when (d.network) { "wifi", "cellular" -> "已連線"; else -> "沒有網路" },
        labelIcon = { Icon(when (d.network) { "wifi" -> Icons.Rounded.Wifi; "cellular" -> Icons.Rounded.SignalCellularAlt; else -> Icons.Rounded.WifiOff }, null, tint = Cute.colors.me, modifier = Modifier.size(14.dp)) },
    )
}

@Composable
private fun MoodCard(s: AppState, open: (String) -> Unit, sheet: (SheetSpec) -> Unit) {
    val pm = if (s.partnerSharing?.shareMood == true) Derived.moodToday(s, s.partner?.id) else null
    val mm = Derived.moodToday(s, s.myId)
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今天的心情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("心情紀錄 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.bouncyClick { open("mood") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoodHalf(s.partnerName, Cute.colors.partner, s.partner?.avatarUrl, pm, Modifier.weight(1f), if (s.partnerSharing?.shareMood == false) "沒有分享心情" else "今天還沒更新", null)
            MoodHalf("我", Cute.colors.me, s.me?.avatarUrl, mm, Modifier.weight(1f), "點我設定心情", onClick = { open("mood") })
        }
        val m = Catalog.mood(pm?.mood)
        if (m?.needsComfort == true) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cute.colors.partnerSoft).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TidyText("${s.partnerName} 覺得${m.label}，要不要安撫一下？", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                CuteButton("抱抱", small = true, onClick = { sheet(SheetSpec.Comfort) })
            }
        }
    }
}

@Composable
private fun MoodHalf(name: String, color: Color, avatar: String?, mood: Mood?, modifier: Modifier, empty: String, onClick: (() -> Unit)?) {
    val m = Catalog.mood(mood?.mood)
    val sticker = mood?.sticker?.takeIf(Giphy::isValidId)
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .then(if (onClick != null) Modifier.bouncyClick(onClick = onClick) else Modifier).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(name, color, 18.dp, image = avatar)
            NameAndTime(name, mood?.createdAt?.toInstantOrNull()?.let { Fmt.time(it) })
        }
        if (m != null) {
            // 附了貼圖就放貼圖，表情移到心情前面，一樣看得出是什麼心情
            if (sticker != null) {
                StickerImage(sticker, Modifier.padding(vertical = 2.dp).size(72.dp))
                TidyText("${m.emoji} ${m.label}", style = MaterialTheme.typography.titleMedium)
            } else {
                HeartBeat { Text(m.emoji, style = MaterialTheme.typography.headlineLarge) }
                Text(m.label, style = MaterialTheme.typography.titleMedium)
            }
            // 太長時用「…」結尾（點進心情紀錄看得到），不會無聲無息被切掉
            TidyText(mood?.note ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(if (onClick != null) "＋" else "·", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(empty, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** 「小雨 · 09:10」；一行放不下時名字和時間各一行，不會留一個「·」掛在行尾 */
@Composable
private fun NameAndTime(name: String, time: String?) {
    var stacked by remember(name, time) { mutableStateOf(false) }
    Text(
        when { time == null -> name; stacked -> "$name\n$time"; else -> "$name · $time" },
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        onTextLayout = { if (!stacked && time != null && it.lineCount > 1) stacked = true },
    )
}

@Composable
private fun QuickActions(s: AppState, open: (String) -> Unit, sheet: (SheetSpec) -> Unit) {
    val pending = s.partnerExpenses.count { it.waitingForGuess }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Quick("🤗", "安撫", Cute.colors.partnerSoft, Modifier.weight(1f)) { sheet(SheetSpec.Comfort) }
        Quick("💰", "猜花費", Cute.colors.butter.copy(alpha = 0.35f), Modifier.weight(1f), badge = pending) { open("money") }
        Quick("🌸", "經期", Cute.colors.periodSoft, Modifier.weight(1f)) { open("cycle") }
        Quick("🎂", "紀念日", Cute.colors.lavender.copy(alpha = 0.3f), Modifier.weight(1f)) { open("anniv") }
    }
}

@Composable
private fun Quick(emoji: String, label: String, bg: Color, modifier: Modifier, badge: Int = 0, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(Cute.colors.card).bouncyClick(haptic = true, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { EmojiBubble(emoji, bg, 46.dp) }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun careBanner(s: AppState): Pair<String, String>? {
    if (s.cycleOwnerIsMe) return null
    if (s.cycle.partner.settings?.careReminders == false) return null
    val p = Derived.prediction(s) ?: return null
    return when {
        p.today.phase == Cycle.Phase.PERIOD && !p.today.predicted -> "🌸" to "${s.partnerName} 經期第 ${p.cycleDay} 天，準備熱飲和暖暖包，少讓對方碰冰的吧。"
        // 數字和「天後」之間是不換行空格：不會變成「…預計 5 / 天後開始」
        p.lateDays == 0 && p.daysUntilNext in 0..5 -> "☕" to "${s.partnerName} 的經期預計${if (p.daysUntilNext == 0) "今天" else " ${p.daysUntilNext}\u00A0天後"}開始，情緒起伏是正常的，多一點耐心和抱抱。"
        else -> null
    }
}

@Composable
private fun Upcoming(s: AppState, open: (String) -> Unit) {
    val items = Derived.upcoming(s).take(4)
    CuteCard {
        SectionTitle("接下來", trailing = "日曆", onTrailing = { open("calendar") })
        items.forEach { u ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).bouncyClick { open(u.route) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmojiBubble(u.emoji, MaterialTheme.colorScheme.primaryContainer)
                Column(Modifier.weight(1f)) {
                    // 字放大時換成兩行，「（等你回覆）」和地點不會不見；再長才用「…」，點進日曆看得到全部
                    TidyText(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TidyText(u.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Pill(Fmt.daysLeft(u.daysLeft), if (u.daysLeft <= 3) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun Recent(s: AppState, open: (String) -> Unit) {
    CuteCard {
        SectionTitle("最近動態", trailing = "全部紀錄", onTrailing = { open("history") })
        val items = s.history.take(3)
        if (items.isEmpty()) Text("還沒有紀錄", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { a ->
            val (emoji, text) = Fmt.history(a, s.myId, s.partnerName)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EmojiBubble(emoji, if (a.actorId == s.myId) Cute.colors.meSoft else Cute.colors.partnerSoft, 34.dp)
                // 太長時用「…」結尾（全部紀錄裡看得到完整的），不會無聲無息被切掉
                TidyText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Fmt.rel(a.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
