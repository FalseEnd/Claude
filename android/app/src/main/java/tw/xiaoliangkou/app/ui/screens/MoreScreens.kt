package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.service.ServiceNotice
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.StatusText
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.MenuRow
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.cuteSlider
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

@Composable
fun MoreScreen(s: AppState, vm: AppViewModel, sheet: (SheetSpec) -> Unit = {}, open: (String) -> Unit) {
    val c = Cute.colors
    var confirmUnpair by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("我的") }
        item {
            CuteCard(color = c.partnerSoft) {
                CoupleMascots(height = 90.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 點頭像換照片或表情
                    Box(Modifier.bouncyClick { sheet(SheetSpec.Avatar) }) {
                        Avatar(s.me?.displayName ?: "我", c.me, 52.dp, image = s.me?.avatarUrl)
                        Icon(
                            Icons.Rounded.PhotoCamera, "設定頭像", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(22.dp).clip(CircleShape).background(c.card).padding(4.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        // 「示範」跟在名字後面，下面那行可以用整個寬度
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.me?.displayName ?: "我", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (s.demo) Pill("示範", MaterialTheme.colorScheme.tertiaryContainer)
                        }
                        TidyText(
                            // 數字和「天」之間是不換行空格：不會只剩「天」在第二行
                            if (s.paired) "和 ${s.partnerName} 在一起 ${s.togetherSince?.let { Dates.daysTogether(it, LocalDate.now()) } ?: "—"}\u00A0天" else "還沒有配對",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            CuteCard {
                MenuRow(Icons.Rounded.Badge, c.me, c.meSoft, "我的資料", "暱稱、生日、身高、血型…${s.partnerName} 也看得到") { open("profile") }
                MenuRow(Icons.Rounded.FavoriteBorder, c.partner, c.partnerSoft, "${s.partnerName} 的資料與備註", "對方的資料，和只有你看得到的備註 🔒") { open("partner") }
                MenuRow(Icons.Rounded.StickyNote2, c.peach, c.peach.copy(alpha = 0.25f), "備忘錄", "文字、照片、語音，可以放在桌面") { open("memos") }
                MenuRow(Icons.Rounded.AutoAwesome, c.lavender, c.fertileSoft, "星座運勢與御神籤", "今天的運勢、兩人速配、抽一支籤") { open("fortune") }
            }
        }
        item {
            CuteCard {
                MenuRow(Icons.Rounded.EmojiEmotions, c.butter, c.butter.copy(alpha = 0.25f), "心情紀錄", "設定今天的心情，看看這週") { open("mood") }
                MenuRow(Icons.Rounded.Favorite, c.partner, c.partnerSoft, "紀念日", "生日、週年、里程碑提醒") { open("anniv") }
                MenuRow(Icons.Rounded.WaterDrop, c.period, c.periodSoft, "經期", if (s.cycleVisible) "預測、排卵日、易孕期" else "尚未開始") { open("cycle") }
                MenuRow(Icons.Rounded.History, c.lavender, c.fertileSoft, "歷史紀錄", "所有互動的時間軸") { open("history") }
            }
        }
        item {
            CuteCard {
                MenuRow(Icons.Rounded.Shield, c.mint, MaterialTheme.colorScheme.secondaryContainer, "分享與隱私", if (s.mySharing?.paused == true) "暫停分享中" else "決定 ${s.partnerName} 能看到什麼") { open("privacy") }
                MenuRow(Icons.Rounded.Notifications, c.peach, c.peach.copy(alpha = 0.25f), "通知設定", "通知列顯示 ${s.partnerName} 的狀態、想收到哪些通知") { open("notif") }
                MenuRow(Icons.Rounded.Widgets, c.butter, c.butter.copy(alpha = 0.25f), "桌面小工具", "在桌面看在一起的天數、${s.partnerName} 的狀態、備忘錄") { open("widgets") }
                if (!s.demo) {
                    MenuRow(Icons.Rounded.Tune, c.me, c.meSoft, "權限與背景執行", "位置、通知、省電、自動記帳") { open("permissions") }
                    MenuRow(Icons.Rounded.Cloud, c.sky, c.meSoft, "Supabase 與帳號", if (s.schemaOutdated) "資料庫需要更新，點這裡看怎麼做 🛠️" else "連線、邀請另一半、改密碼、登出") { open("account") }
                } else {
                    MenuRow(Icons.Rounded.Science, c.lavender, c.fertileSoft, "示範控制台", "扮演 ${s.partnerName}，看看會跳出什麼通知") { open("demo") }
                }
            }
        }
        item {
            if (s.demo) SoftButton("離開示範模式", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout) { scope.launch { vm.container.logout() } }
            else if (s.paired) SoftButton("解除配對", Modifier.fillMaxWidth(), icon = Icons.Rounded.LinkOff) { confirmUnpair = true }
        }
        item {
            Text(
                "小倆口 ${tw.xiaoliangkou.app.BuildConfig.VERSION_NAME}（${tw.xiaoliangkou.app.BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("解除配對？") },
        text = { Text("解除後，你和 ${s.partnerName} 之間的位置、手機狀態、開銷、心情與經期分享會立即停止。共同的標點、紀念日與約會保留 30 天。${s.partnerName} 會收到通知。") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; vm.act("已解除配對") { it.unpair() } }) { Text("解除配對", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val sh = s.mySharing
    fun set(key: String, v: Boolean) = vm.act("已${if (v) "開啟" else "關閉"}，${s.partnerName} 會收到通知") { it.updateSharing(buildJsonObject { put(key, v) }) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("分享與隱私", onBack = back) }
        item { SectionTitle("我分享給 ${s.partnerName}") }
        item {
            CuteCard {
                ToggleRow("位置", sh?.shareLocation == true, "即時位置、抵達與離開標點", "📍") { set("location", it) }
                ToggleRow("手機狀態", sh?.shareDevice == true, "電量、充電、網路、螢幕、步數", "🔋") { set("device", it) }
                ToggleRow("開銷", sh?.shareExpenses == true, "自動記帳（不給看、禮物模式除外）", "💰") { set("expenses", it) }
                ToggleRow("心情", sh?.shareMood == true, "你設定的心情與備註", "😊") { set("mood", it) }
                if (s.cycleOwnerIsMe) ToggleRow("經期", sh?.shareCycle == true, "日期與預測，不含症狀", "🌸") { set("cycle", it) }
            }
        }
        item {
            CuteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("暫停分享 🙈", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (sh?.paused == true) Pill("到 ${Fmt.time(sh.pausedUntilInstant!!)}", Cute.colors.butter.copy(alpha = 0.4f))
                }
                Text("暫停位置、手機狀態與開銷。${s.partnerName} 會看到「暫停中」與恢復時間，不會以為你失聯。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sh?.paused == true) CuteButton("立即恢復", Modifier.fillMaxWidth()) { vm.act("已恢復分享") { it.pauseSharing(0) } }
                // 放不下三顆並排（字放大的窄手機）時，放不下的換到下一排
                else ButtonRow {
                    SoftButton("1 小時") { vm.act("暫停 1 小時") { it.pauseSharing(60) } }
                    SoftButton("3 小時") { vm.act("暫停 3 小時") { it.pauseSharing(180) } }
                    SoftButton("到今晚") {
                        val mins = java.time.Duration.between(java.time.LocalDateTime.now(), LocalDate.now().atTime(23, 59)).toMinutes().toInt().coerceIn(1, 1440)
                        vm.act("暫停到今晚") { it.pauseSharing(mins) }
                    }
                }
            }
        }
        item {
            CuteCard {
                Text("開銷不給看的類別", style = MaterialTheme.typography.titleMedium)
                val hidden = sh?.hiddenCategories.orEmpty().toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Catalog.categories.forEach { cat ->
                        ChoiceChip(cat.label, cat.key in hidden, cat.emoji) {
                            val next = if (cat.key in hidden) hidden - cat.key else hidden + cat.key
                            vm.act { r -> r.updateSharing(buildJsonObject { putJsonArray("hiddenCategories") { next.forEach { add(JsonPrimitive(it)) } } }) }
                        }
                    }
                }
                Text("選取的類別不會出現在 ${s.partnerName} 的帳本裡。單筆也可以設成「不給看」或「禮物模式」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionTitle("${s.partnerName} 分享給我") }
        item {
            val ps = s.partnerSharing
            CuteCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("位置" to ps?.shareLocation, "手機狀態" to ps?.shareDevice, "開銷" to ps?.shareExpenses, "心情" to ps?.shareMood, "經期" to ps?.shareCycle).forEach { (l, on) ->
                        Pill((if (on == true) "✅ " else "⛔ ") + l, if (on == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                if (ps?.paused == true) Text("${s.partnerName} 暫停分享到 ${Fmt.time(ps.pausedUntilInstant!!)}", style = MaterialTheme.typography.bodyMedium)
                TidyText("只能由 ${s.partnerName} 在自己的手機上更改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Note("每次更改分享設定，對方都會收到通知。對方新增會偵測你的標點時，你也一定會收到通知。解除配對後所有分享立即停止。", "🛡️") }
        item { Note("分享應該是兩個人都願意的事。如果你覺得被要求或被迫分享，可以先暫停或解除配對，也可以撥打 113 保護專線（24 小時）。", "🤍", Cute.colors.partnerSoft) }
    }
}

@Composable
fun NotificationScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val kinds = listOf(
        Triple("comfort", "抱抱與悄悄話", "🤗"), Triple("mood", "心情更新", "😊"), Triple("place", "抵達與離開標點", "📍"),
        Triple("battery_low", "對方電量低", "🔋"), Triple("guess", "猜花費", "💰"), Triple("event", "約會邀請與提醒", "📅"),
        Triple("anniversary", "紀念日與里程碑", "🎂"), Triple("cycle_care", "經期關懷提醒", "🌸"),
        Triple("diary", "對方記錄那天的生活", "📔"), Triple("memo", "新的共同備忘錄", "📝"),
    )
    val prefs by vm.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("通知設定", onBack = back) }
        item { StatusNoticeCard(s, vm, prefs?.statusNotification != false, prefs?.statusItems ?: StatusText.DEFAULTS) { block -> scope.launch { block() } } }
        item {
            CuteCard {
                Text("要收到哪些通知", style = MaterialTheme.typography.titleMedium)
                kinds.forEach { (k, l, e) ->
                    // 舊的資料庫不認得這兩種通知：先提醒；按了的話 setNotifPref 會說要更新資料庫（更新好就能直接設定，不用等重新檢查）
                    val hint = if (s.schemaOutdated && k in V2_NOTIF_KINDS) "資料庫更新後才能設定" else null
                    ToggleRow(l, s.notifPrefs[k] != false, hint, e) { v -> vm.act { it.setNotifPref(k, v) } }
                }
            }
        }
        item { Note("「對方新增會偵測你的標點」與「分享設定變更」的通知不能關閉，讓兩個人都知道自己分享了什麼。也可以在系統設定裡分別調整每種通知的聲音。", "🔔") }
    }
}

/** upgrade-v2.sql 才有的通知種類 */
private val V2_NOTIF_KINDS = setOf("diary", "memo")

/** 常駐通知顯示對方的狀態：開關、要顯示哪些項目、預覽 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusNoticeCard(s: AppState, vm: AppViewModel, on: Boolean, items: Set<String>, launch: (suspend () -> Unit) -> Unit) {
    CuteCard {
        ToggleRow("通知列顯示 ${s.partnerName} 的狀態", on, "常駐通知會顯示對方的電量、位置、心情", "📌") { v -> launch { vm.container.setStatusNotification(v) } }
        if (!on) return@CuteCard
        Text("要顯示哪些", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText.options.forEach { (k, label) ->
                ChoiceChip(label, k in items) { launch { vm.container.setStatusItems(if (k in items) items - k else items + k) } }
            }
        }
        if (!s.paired) {
            Note("配對之後，這裡會顯示 ${s.partnerName} 的狀態", "💞")
            return@CuteCard
        }
        Text("預覽", style = MaterialTheme.typography.labelLarge)
        NoticePreview(ServiceNotice.of(s, true, items))
        Text("只會出現 ${s.partnerName} 有分享的項目。通知上的按鈕可以直接問對方在哪、送一個抱抱。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val context = LocalContext.current
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            s.demo -> Note("示範模式只有預覽，登入 Supabase 後才會出現在通知列", "🧪")
            !allowed -> Note("通知權限還沒打開，到「我的 › 權限與背景執行」打開後才看得到", "🔕")
        }
    }
}

/** 長得像系統通知的預覽 */
@Composable
private fun NoticePreview(n: ServiceNotice) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(
                listOfNotNull("小倆口", n.sub, n.whenMs?.let { Fmt.time(java.time.Instant.ofEpochMilli(it)) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall)
                (n.big ?: n.text).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (n.status) Avatar(n.avatarName, Cute.colors.partner, 40.dp, image = n.avatar)
        }
        if (n.locate || n.hug) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (n.locate) Text("📍 現在在哪？", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (n.hug) Text("🤗 抱抱", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun HistoryScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val filters = listOf("all" to "全部", "place" to "位置", "mood" to "心情", "comfort" to "安撫", "money" to "開銷", "date" to "約會", "device" to "手機", "pair" to "分享")
    val list = s.history.filter { filter == "all" || Fmt.historyGroup(it.kind) == filter }
    val groups = list.groupBy { Fmt.localDate(it.time) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("歷史紀錄", onBack = back) }
        item {
            // 往左右多伸 16dp 到螢幕邊：捲動時籤片從螢幕邊緣消失，不會在內容邊界被切一半
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.layout { m, c ->
                    val extra = 32.dp.roundToPx()
                    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
                },
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters) { (k, l) -> ChoiceChip(l, filter == k) { filter = k } }
            }
        }
        if (list.isEmpty()) item { CuteCard { tw.xiaoliangkou.app.ui.components.EmptyState("這個分類還沒有紀錄", "🍃") } }
        groups.forEach { (day, items) ->
            item(key = "d$day") { Text(Fmt.dayLabel(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 6.dp)) }
            items(items, key = { it.id }) { a ->
                val (emoji, text) = Fmt.history(a, s.myId, s.partnerName)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EmojiBubble(emoji, if (a.actorId == s.myId) Cute.colors.meSoft else Cute.colors.partnerSoft, 38.dp)
                    TidyText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Fmt.time(a.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!s.historyEnd && list.isNotEmpty()) item { SoftButton("載入更多", Modifier.fillMaxWidth()) { vm.act { it.loadMoreHistory() } } }
        item { Note("位置只記錄抵達與離開標點的事件，不保存移動軌跡。", "🛡️") }
    }
}

@Composable
fun AccountScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    val context = LocalContext.current
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(s.me?.displayName ?: "") }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Supabase 與帳號", onBack = back) }
        if (s.schemaOutdated) item { SchemaUpgradeCard(prefs?.supabaseUrl, vm) }
        item {
            CuteCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (s.connected) "🟢" else "🟠", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(if (s.connected) "已連上你們的 Supabase" else "連線中…", style = MaterialTheme.typography.titleMedium)
                        Text(prefs?.supabaseUrl?.substringAfter("://").orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Email：${s.me?.email ?: prefs?.session?.email ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("資料存在你們自己的 Supabase 專案，在哪裡都能連。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val p = prefs?.takeIf { it.configured }
        if (p != null) item {
            CuteCard(color = Cute.colors.meSoft) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiBubble("💌", Cute.colors.card, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("邀請另一半", style = MaterialTheme.typography.titleSmall)
                        Text("傳一個連結過去，對方點一下就會自動填好 Supabase 網址和金鑰", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ButtonRow(weights = listOf(1f, 1.4f)) {
                    SoftButton("複製", icon = Icons.Rounded.ContentCopy) {
                        copyText(context, "小倆口連線邀請", InviteLink.shareText(p.supabaseUrl, p.supabaseKey))
                        vm.toast("已複製連線邀請")
                    }
                    CuteButton("分享給另一半", icon = Icons.Rounded.Share) {
                        shareText(context, InviteLink.shareText(p.supabaseUrl, p.supabaseKey), "傳送連線邀請")
                    }
                }
                Text("金鑰是公開用的 anon / publishable key，資料由資料庫的權限規則保護，只有你們兩個看得到 🔒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CuteCard {
                Text("暱稱", style = MaterialTheme.typography.titleMedium)
                CuteField(name, { name = it.take(30) }, "暱稱")
                SoftButton("儲存暱稱", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { vm.act("已更新暱稱") { it.updateProfile(name.trim()) } }
            }
        }
        if (s.paired) item {
            CuteCard {
                Text("在一起的日子", style = MaterialTheme.typography.titleMedium)
                DateField("從哪天開始", s.couple?.togetherSince ?: LocalDate.now().toString()) { d -> vm.act("已更新 💞") { it.setTogetherSince(d) } }
            }
        }
        item {
            CuteCard {
                Text("改密碼 🔑", style = MaterialTheme.typography.titleMedium)
                CuteField(old, { old = it }, "目前的密碼", password = true)
                CuteField(new, { new = it }, "新密碼（至少 6 個字）", password = true)
                SoftButton("更新密碼", Modifier.fillMaxWidth(), icon = Icons.Rounded.Key, enabled = old.isNotEmpty() && new.length >= 6) {
                    vm.account(onDone = { old = ""; new = ""; vm.toast("密碼已更新，其他裝置需要重新登入") }) { vm.container.changePassword(old, new) }
                }
            }
        }
        item {
            CuteCard {
                ToggleRow("自動記帳", prefs?.autoBookkeeping != false, "收到付款通知時自動記一筆", "🤖") { v -> scope.launch { vm.container.setAutoBookkeeping(v) } }
            }
        }
        item { SoftButton("登出", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout) { scope.launch { vm.container.logout() } } }
    }
}

/** 資料庫還是 0.1 版：一步一步教怎麼執行 upgrade-v2.sql，更新好再檢查一次（其他畫面的「資料庫需要更新」都指到這裡） */
@Composable
private fun SchemaUpgradeCard(projectUrl: String?, vm: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    fun open(url: String) { runCatching { uriHandler.openUri(url) }.onFailure { vm.toast("打不開瀏覽器，請到說明文件找「更新資料庫」那一節") } }
    val editor = Supabase.sqlEditorUrl(projectUrl)
    val steps = listOf(
        "按下面的「upgrade-v2.sql」打開更新檔，按「Raw」，從純文字頁面全選、複製",
        if (editor != null) "按「SQL Editor」打開你們專案的 SQL Editor，貼上後按「Run」"
        else "到 Supabase 後台的 SQL Editor，按左上角的「＋」›「Create a new snippet」，貼上後按「Run」",
        "跳出「Potential issue detected」的話，按「Run query」繼續",
        "下方出現「更新完成 🎉」就好了，回來按「重新檢查」",
    )
    CuteCard(color = Cute.colors.butter.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmojiBubble("🛠️", Cute.colors.card, 44.dp)
            Column(Modifier.weight(1f)) {
                Text("資料庫需要更新", style = MaterialTheme.typography.titleMedium)
                Text("個人資料、備註、日曆的生活紀錄和備忘錄，要先更新資料庫才能用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        steps.forEachIndexed { i, t ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        // 上面的步驟要按這兩顆：字放大時一顆一排，按鈕上的字一定看得完整
        ButtonRow {
            SoftButton("upgrade-v2.sql", icon = Icons.Rounded.Description, small = true) { open(Supabase.UPGRADE_SQL_URL) }
            if (editor != null) SoftButton("SQL Editor", icon = Icons.Rounded.Terminal, small = true) { open(editor) }
            else SoftButton("完整說明", icon = Icons.AutoMirrored.Rounded.MenuBook, small = true) { open(Supabase.GUIDE_URL) }
        }
        if (editor != null) SoftButton("看完整說明", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.MenuBook, small = true) { open(Supabase.GUIDE_URL) }
        CuteButton("我更新好了，重新檢查", Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, enabled = !vm.busy) {
            vm.act { r ->
                r.refreshAll()
                vm.toast(
                    if (r.state.value.schemaOutdated) "還是舊的資料庫：確認 SQL Editor 下方出現「更新完成 🎉」，再檢查一次"
                    else "資料庫更新好了，新功能都可以用了 🎉",
                )
            }
        }
        Text("只要執行一次，兩個人其中一個做就好。已經存的資料不會受影響，不小心多跑一次也沒關係。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DemoConsole(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val demo = vm.demo
    var speed by remember { mutableFloatStateOf((demo?.speed ?: 1).toFloat()) }
    var running by remember { mutableStateOf(demo?.running ?: true) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("示範控制台 🧪", onBack = back) }
        item { Note("這裡扮演「${s.partnerName}」那一端的手機。按下按鈕後回到首頁，看看會跳出哪些通知與變化。", "🎭") }
        item {
            CuteCard {
                Text("${s.partnerName} 的行程", style = MaterialTheme.typography.titleMedium)
                Text("${s.partnerName} 會在台北市區走一整天的行程：家 → 便利商店 → 公司 → 咖啡店 → 健身房 → 家。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow("模擬移動", running, emoji = "🚶") { running = it; demo?.running = it }
                Text("速度 ${speed.toInt()}×", style = MaterialTheme.typography.labelLarge)
                Slider(speed, { speed = it; demo?.speed = it.toInt().coerceAtLeast(1) }, valueRange = 1f..8f, steps = 6, colors = cuteSlider())
                Text("讓 ${s.partnerName} 前往", style = MaterialTheme.typography.labelLarge)
                s.places.filter { it.watchUser != s.myId }.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p -> SoftButton("${placeEmoji(p.icon)} ${p.name}", Modifier.weight(1f), small = true) { demo?.demoAction("go", p.id) } }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            CuteCard {
                Text("讓 ${s.partnerName}…", style = MaterialTheme.typography.titleMedium)
                // 兩顆一排；字太長放不下兩顆時自己一排
                ButtonRow(maxPerRow = 2) {
                    listOf(
                        "sad" to "😢 心情不好", "happy" to "🥰 心情很好", "hug" to "🤗 給你抱抱", "battery" to "🔋 電量剩 19%",
                        "spend" to "💳 刷卡消費", "invite" to "💌 約你出去", "pause" to "🙈 暫停／恢復分享", "guess" to "💰 猜你的花費",
                        "diary" to "📔 記錄今天的生活", "memo" to "📝 新增共同備忘錄",
                    ).forEach { (k, l) -> SoftButton(l, small = true) { demo?.demoAction(k); vm.toast("${s.partnerName}：$l") } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceSheet(s: AppState, vm: AppViewModel, id: String?, lat: Double, lng: Double, presetName: String = "", close: () -> Unit) {
    val p = id?.let { x -> s.places.firstOrNull { it.id == x } }
    var name by remember { mutableStateOf(p?.name ?: presetName.take(20)) }
    var icon by remember { mutableStateOf(p?.icon ?: "heart") }
    var radius by remember { mutableFloatStateOf((p?.radiusM ?: 150).toFloat()) }
    var watch by remember { mutableStateOf(when (p?.watchUser) { null -> if (p == null) "partner" else "both"; s.myId -> "me"; else -> "partner" }) }
    var arrive by remember { mutableStateOf(p?.notifyArrive ?: true) }
    var leave by remember { mutableStateOf(p?.notifyLeave ?: true) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (p == null) "新增標點 📍" else "編輯標點", style = MaterialTheme.typography.titleLarge)
        CuteField(name, { name = it.take(20) }, "名稱", placeholder = "例如：公司、學校、我家")
        // 圖示一排放不下時換行：預設的 💗、📍 不會被擠出畫面
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Catalog.placeIcons.forEach { k ->
                Text(
                    placeEmoji(k), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (icon == k) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .bouncyClick { icon = k }
                        .padding(6.dp),
                )
            }
        }
        Text("範圍半徑 ${radius.toInt()} 公尺", style = MaterialTheme.typography.labelLarge)
        Slider(radius, { radius = (it / 10).toInt() * 10f }, valueRange = 50f..500f, colors = cuteSlider())
        Text("偵測誰", style = MaterialTheme.typography.labelLarge)
        Segmented(listOf("partner" to s.partnerName, "me" to "我", "both" to "兩個人"), watch) { watch = it }
        CuteCard(color = MaterialTheme.colorScheme.surfaceVariant, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            ToggleRow("抵達時通知", arrive, emoji = "🏁") { arrive = it }
            ToggleRow("離開時通知", leave, emoji = "👋") { leave = it }
        }
        Text(
            if (watch == "me") "你進出這裡時 ${s.partnerName} 會收到通知。" else "${s.partnerName} 會收到「你新增了這個標點」的通知，看得到範圍與通知設定。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CuteButton("儲存", Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !vm.busy) {
            val body = buildJsonObject {
                put("name", name.trim()); put("icon", icon); put("radiusM", radius.toInt()); put("watch", watch)
                put("notifyArrive", arrive); put("notifyLeave", leave)
                if (p == null) { put("lat", lat); put("lng", lng) }
            }
            vm.act(if (p == null && watch != "me") "已新增，並通知 ${s.partnerName} 📍" else "已儲存", onDone = close) { it.savePlace(id, body) }
        }
        if (p != null) SoftButton("刪除標點", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete) { vm.act("已刪除", onDone = close) { it.deletePlace(p.id) } }
    }
}

