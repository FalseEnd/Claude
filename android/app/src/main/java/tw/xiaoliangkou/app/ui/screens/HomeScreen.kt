package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.BatteryPill
import tw.xiaoliangkou.app.ui.components.CountUpText
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.unscaled
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

@Composable
fun HomeScreen(s: AppState, vm: AppViewModel, open: (String) -> Unit, sheet: (SheetSpec) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TopBar(s, open) }
        item { Hero(s) }
        if (s.mySharing?.paused == true) item {
            Banner("🙈", "你暫停分享到 ${Fmt.time(s.mySharing.pausedUntilInstant!!)}，${s.partnerName} 看得到你暫停了。", Cute.colors.butter.copy(alpha = 0.35f)) {
                SoftButton("恢復", small = true) { vm.act("已恢復分享") { it.pauseSharing(0) } }
            }
        }
        item { PartnerPhone(s, vm) }
        item { MoodCard(s, open, sheet) }
        item { QuickActions(s, open, sheet) }
        careBanner(s)?.let { (emoji, text) -> item { Banner(emoji, text, Cute.colors.periodSoft) { SoftButton("看看", small = true) { open("cycle") } } } }
        item { Upcoming(s, open) }
        item { Recent(s, open) }
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge.unscaled())
        TidyText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        action()
    }
}

@Composable
private fun PartnerPhone(s: AppState, vm: AppViewModel) {
    val ps = s.partnerSharing
    val d = s.partnerStatus.device
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Avatar(s.partnerName, Cute.colors.partner, 34.dp, image = s.partner?.avatarUrl)
                if (s.partnerOnline) Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(12.dp).clip(CircleShape).background(Cute.colors.card).padding(2.dp).clip(CircleShape).background(Cute.colors.good))
            }
            // 手機型號放在標題下面：不跟標題搶寬度，名字長、字放大也不會把標題擠成好幾行
            Column(Modifier.weight(1f)) {
                TidyText("${s.partnerName} 的手機", style = MaterialTheme.typography.titleMedium)
                d?.deviceModel?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
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
            return@CuteCard
        }
        val level = d.batteryLevel ?: 0
        val charging = d.isCharging == true
        val lastActive = d.lastActiveAt?.toInstantOrNull()
        val screenOn = lastActive != null && java.time.Duration.between(lastActive, java.time.Instant.now()).seconds < 120
        // 圖示放在小標題旁，數值用整個寬度；同一排的卡片一樣高（字放大時數值會換成兩行）
        val tile = Modifier.fillMaxHeight()
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("電量", "$level%", tile.weight(1f), sub = if (charging) "充電中 ⚡" else if (level <= 20) "電量偏低 🔋" else "未充電", extra = { BatteryPill(level, charging) })
            StatTile(
                "網路", when (d.network) { "wifi" -> "Wi-Fi"; "cellular" -> "行動網路"; else -> "離線" }, tile.weight(1f),
                sub = d.networkLabel ?: if (d.network == "wifi") "已連線" else "—",
                labelIcon = { Icon(when (d.network) { "wifi" -> Icons.Rounded.Wifi; "cellular" -> Icons.Rounded.SignalCellularAlt; else -> Icons.Rounded.WifiOff }, null, tint = Cute.colors.me, modifier = Modifier.size(14.dp)) },
            )
            StatTile(
                "螢幕", if (screenOn) "使用中" else "休息中", tile.weight(1f),
                sub = if (d.notificationsSilenced == true) "勿擾中 🔕" else Fmt.rel(lastActive),
                labelIcon = { Icon(if (d.notificationsSilenced == true) Icons.Rounded.DoNotDisturbOn else if (screenOn) Icons.Rounded.PhoneAndroid else Icons.Rounded.Bedtime, null, tint = Cute.colors.lavender, modifier = Modifier.size(14.dp)) },
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val act = s.partnerStatus.location?.activity
            StatTile(
                "位置", if (ps.locationLive) Derived.partnerWhere(s) else "沒有分享位置", tile.weight(2f),
                sub = Derived.distanceToPartner(s)?.let { "距離你 ${Fmt.distance(it)}" } ?: "—",
                labelIcon = { Icon(when (act) { "walking", "running" -> Icons.AutoMirrored.Rounded.DirectionsWalk; "transit" -> Icons.Rounded.Train; else -> Icons.Rounded.LocationOn }, null, tint = Cute.colors.partner, modifier = Modifier.size(14.dp)) },
            )
            // 沒有步數（手機沒有計步器、沒給權限，或今天還沒回報）：說清楚，不要只寫「— 步」
            StatTile("今日步數", d.stepsToday?.let { Fmt.int(it) } ?: "—", tile.weight(1f), sub = if (d.stepsToday != null) "👣 步" else "手機沒回報")
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TidyText("更新於 ${Fmt.rel(d.updatedAt?.toInstantOrNull())}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ps.locationLive) SoftButton("現在在哪？", icon = Icons.Rounded.MyLocation, small = true) {
                    vm.act { r -> vm.toast(if (r.locatePartner()) "已請 ${s.partnerName} 的手機回報位置 📍" else "${s.partnerName} 的手機目前不在線上") }
                }
            }
            TidyText("只顯示對方選擇分享的項目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
            MoodHalf(s.partnerName, Cute.colors.partner, s.partner?.avatarUrl, pm?.mood, pm?.note, pm?.createdAt, Modifier.weight(1f), if (s.partnerSharing?.shareMood == false) "沒有分享心情" else "今天還沒更新", null)
            MoodHalf("我", Cute.colors.me, s.me?.avatarUrl, mm?.mood, mm?.note, mm?.createdAt, Modifier.weight(1f), "點我設定心情", onClick = { open("mood") })
        }
        val m = Catalog.mood(pm?.mood)
        if (m?.needsComfort == true) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cute.colors.partnerSoft).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${s.partnerName} 覺得${m.label}，要不要安撫一下？", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                CuteButton("抱抱", small = true, onClick = { sheet(SheetSpec.Comfort) })
            }
        }
    }
}

@Composable
private fun MoodHalf(name: String, color: Color, avatar: String?, mood: String?, note: String?, at: String?, modifier: Modifier, empty: String, onClick: (() -> Unit)?) {
    val m = Catalog.mood(mood)
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .then(if (onClick != null) Modifier.bouncyClick(onClick = onClick) else Modifier).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(name, color, 18.dp, image = avatar)
            NameAndTime(name, at?.toInstantOrNull()?.let { Fmt.time(it) })
        }
        if (m != null) {
            HeartBeat { Text(m.emoji, style = MaterialTheme.typography.headlineLarge) }
            Text(m.label, style = MaterialTheme.typography.titleMedium)
            Text(note ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
        p.lateDays == 0 && p.daysUntilNext in 0..5 -> "☕" to "${s.partnerName} 的經期預計${if (p.daysUntilNext == 0) "今天" else " ${p.daysUntilNext} 天後"}開始，情緒起伏是正常的，多一點耐心和抱抱。"
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
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                Text(Fmt.rel(a.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
