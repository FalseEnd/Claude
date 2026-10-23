package tw.xiaoliangkou.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Guess
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Expense
import tw.xiaoliangkou.app.data.GuessResult
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CategoryBars
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.Confetti
import tw.xiaoliangkou.app.ui.components.CountUpText
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.Wiggle
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

@Composable
fun MoneyScreen(s: AppState, vm: AppViewModel, sheet: (SheetSpec) -> Unit) {
    var who by remember { mutableStateOf("partner") }
    val mine = who == "me"
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("帳本") }
        item { Segmented(listOf("partner" to "${s.partnerName} 的帳本", "me" to "我的帳本"), who) { who = it } }
        if (!mine && s.partnerSharing?.shareExpenses != true) {
            item { CuteCard { EmptyState("${s.partnerName} 目前沒有分享開銷", "🙈") } }
            return@LazyColumn
        }
        val all = Derived.recent(if (mine) s.myExpenses else s.partnerExpenses)
        val visible = if (mine) all else all.filterNot { Derived.surpriseHidden(it) }
        val counted = visible.filter { it.amount != null }
        val pending = visible.filter { it.waitingForGuess }
        item { Summary(counted, pending.size, mine, s.partnerName) }
        if (!mine) item { GuessCard(s, pending, sheet) }
        else item {
            ButtonRow(spacing = 10.dp) {
                CuteButton("記一筆", icon = Icons.Rounded.Add) { sheet(SheetSpec.ExpenseEdit(null)) }
                SoftButton("測試自動記帳", icon = Icons.Rounded.NotificationsActive) { sheet(SheetSpec.ParseTest) }
            }
        }
        val groups = visible.filter { mine || !it.waitingForGuess }.sortedByDescending { it.time }.groupBy { Fmt.localDate(it.time) }
        if (groups.isEmpty()) item { CuteCard { EmptyState("近 30 天還沒有開銷", "🧾") } }
        groups.forEach { (day, list) ->
            item(key = "h$day$who") {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(Fmt.dayLabel(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(Fmt.money(list.sumOf { it.amount ?: 0.0 }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.id }) { e -> ExpenseRow(e, mine) { sheet(if (mine) SheetSpec.ExpenseEdit(e.id) else SheetSpec.ExpenseView(e.id)) } }
        }
        if (!mine) {
            val hidden = all.count { Derived.surpriseHidden(it) }
            if (hidden > 0) item { Note("${s.partnerName} 有 $hidden 筆開銷設成禮物模式，到了日子才會出現。是給你的驚喜嗎？", "🎁") }
        } else item {
            Note("自動記帳：在「我的 › 權限與背景執行」開啟通知存取權後，LINE Pay、街口、銀行的付款通知會自動記下來。同一筆交易重複通知會自動合併。", "🤖")
        }
    }
}

@Composable
private fun Summary(list: List<Expense>, pending: Int, mine: Boolean, partner: String) {
    val total = list.sumOf { it.amount ?: 0.0 }
    val byCat = list.groupBy { it.category }.map { (k, v) -> val c = Catalog.category(k); Triple(c.emoji, c.label, v.sumOf { it.amount ?: 0.0 }) }.sortedByDescending { it.third }
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近 30 天", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${list.size} 筆" + if (pending > 0) " · 另有 $pending 筆待猜" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountUpText(total.toInt(), MaterialTheme.typography.headlineLarge, prefix = "NT$")
            Text(if (mine) "我的支出" else "$partner 的支出", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (byCat.isNotEmpty()) CategoryBars(byCat, if (mine) Cute.colors.me else Cute.colors.partner, Fmt::money)
    }
}

@Composable
private fun GuessCard(s: AppState, pending: List<Expense>, sheet: (SheetSpec) -> Unit) {
    val done = s.partnerExpenses.filter { it.myGuess != null && it.amount != null }
    val points = done.sumOf { it.myPoints ?: 0 }
    val err = if (done.isEmpty()) 0.0 else done.sumOf { kotlin.math.abs(it.myGuess!! - it.amount!!) / it.amount } / done.size
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("猜猜看 💰", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("猜得越準分數越高", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pending.isEmpty()) Text("目前沒有等你猜的開銷。${s.partnerName} 開放猜金額的消費會出現在這裡。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pending.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cute.colors.butter.copy(alpha = 0.25f)).bouncyClick(haptic = true) { sheet(SheetSpec.GuessIt(e.id)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmojiBubble(Catalog.category(e.category).emoji, Cute.colors.card)
                Column(Modifier.weight(1f)) {
                    // 店名最多兩行，再長才「…」（點進去看得到全名）
                    TidyText(e.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${Catalog.category(e.category).label} · ${Fmt.rel(e.time)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Wiggle(8f, 1200) { Pill("猜！", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("總分", Fmt.int(points), Modifier.weight(1f))
            StatTile("猜過", "${done.size} 次", Modifier.weight(1f))
            StatTile("平均誤差", "${Math.round(err * 100)}%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExpenseRow(e: Expense, mine: Boolean, onClick: () -> Unit) {
    val c = Catalog.category(e.category)
    // 時間放前面，換行時最常看的時間一定在第一行；「LINE Pay」用不換行空格連起來，不會拆成兩行
    val tags = buildList {
        add(Fmt.time(e.time))
        add((Catalog.sources[e.source] ?: "付款通知").replace(' ', '\u00A0'))
        if (mine && e.visibility == "private") add("不給看")
        if (mine && e.visibility == "surprise") add("禮物 · ${e.revealOn?.let { Fmt.parseDate(it)?.let(Fmt::md) } ?: ""} 公開")
        if (mine && e.guessable) add(e.theirGuess?.let { "對方猜 ${Fmt.money(it)}" } ?: "開放猜")
        if (!mine && e.myGuess != null) add("你猜 ${Fmt.money(e.myGuess)} · ${e.myPoints} 分")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).bouncyClick(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBubble(c.emoji, if (mine) Cute.colors.meSoft else Cute.colors.partnerSoft)
        Column(Modifier.weight(1f)) {
            // 店名最多兩行，再長才「…」（點進去看得到全名）
            TidyText(e.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // 類別、時間、來源和「不給看」「開放猜」「你猜…分」這些標籤：放不下就換行，不會無聲無息被切掉
            TidyText(
                "${c.label} · " + tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Fmt.money(e.amount), style = MaterialTheme.typography.titleSmall)
    }
}

// ---------------------------------------------------------------- 底部面板

@Composable
fun GuessSheet(s: AppState, vm: AppViewModel, id: String, close: () -> Unit) {
    val e = s.partnerExpenses.firstOrNull { it.id == id } ?: return
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GuessResult?>(null) }
    var confetti by remember { mutableIntStateOf(0) }
    val same = s.partnerExpenses.filter { it.category == e.category && it.id != id && it.amount != null }
    val avg = if (same.isEmpty()) null else same.sumOf { it.amount!! } / same.size
    Box {
        AnimatedContent(result, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() }, label = "guess") { r ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (r == null) {
                    Text("猜猜 ${s.partnerName} 花了多少？", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmojiBubble(Catalog.category(e.category).emoji, Cute.colors.partnerSoft, 52.dp)
                        Column {
                            Text(e.merchant, style = MaterialTheme.typography.titleMedium)
                            Text("${Catalog.category(e.category).label} · ${Catalog.sources[e.source]} · ${Fmt.dayLabel(Fmt.localDate(e.time))} ${Fmt.time(e.time)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (avg != null) Note("提示：${s.partnerName} 在「${Catalog.category(e.category).label}」平均每筆 ${Fmt.money(avg)}", "🤫")
                    CuteField(input, { input = it.filter(Char::isDigit).take(7) }, "你猜（NT$）", keyboard = KeyboardType.Number, placeholder = "例如 150")
                    CuteButton("送出答案", Modifier.fillMaxWidth(), enabled = input.isNotEmpty() && !vm.busy) {
                        vm.act { repo ->
                            val res = repo.guess(id, input.toDouble())
                            result = res
                            if (res.scorePoints >= 80) confetti++
                        }
                    }
                    Text("誤差 3% 內 100 分、10% 內 80 分、25% 內 50 分、50% 內 20 分。每筆只能猜一次。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val g = input.toDoubleOrNull() ?: 0.0
                    val score = Guess.score(g, r.actualAmount)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (r.scorePoints >= 80) "🎉" else if (r.scorePoints >= 50) "😆" else "🙈", style = MaterialTheme.typography.displayMedium)
                        CountUpText(r.scorePoints, MaterialTheme.typography.displayLarge, suffix = " 分", color = MaterialTheme.colorScheme.primary)
                        Text(score.label, style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("你猜", Fmt.money(g), Modifier.weight(1f))
                            StatTile("實際", Fmt.money(r.actualAmount), Modifier.weight(1f))
                        }
                        Text(if (score.diff == 0.0) "一塊錢都不差！" else "猜${if (score.diff > 0) "高" else "低"}了 ${Fmt.money(kotlin.math.abs(score.diff))}（${Math.round(score.ratio * 100)}%）",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CuteButton("好", Modifier.fillMaxWidth(), onClick = close)
                }
            }
        }
        Confetti(confetti, Modifier.matchParentSize())
    }
}

@Composable
fun ExpenseViewSheet(s: AppState, id: String, close: () -> Unit) {
    val e = s.partnerExpenses.firstOrNull { it.id == id } ?: s.myExpenses.firstOrNull { it.id == id } ?: return
    val c = Catalog.category(e.category)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmojiBubble(c.emoji, Cute.colors.partnerSoft, 52.dp)
            Column {
                Text(e.merchant, style = MaterialTheme.typography.titleLarge)
                Text(Fmt.money(e.amount), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        // 三格放得下一行就並排；字放大時最長的「時間」自己一排，每格的字都完整
        ButtonRow {
            StatTile("類別", c.label)
            StatTile("來源", Catalog.sources[e.source] ?: "—")
            StatTile("時間", "${Fmt.dayLabel(Fmt.localDate(e.time))} ${Fmt.time(e.time)}")
        }
        if (e.myGuess != null) Note("你猜了 ${Fmt.money(e.myGuess)}，拿到 ${e.myPoints} 分", "💰")
        CuteButton("關閉", Modifier.fillMaxWidth(), onClick = close)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditSheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) {
    val e = id?.let { x -> s.myExpenses.firstOrNull { it.id == x } }
    var merchant by remember { mutableStateOf(e?.merchant ?: "") }
    var amount by remember { mutableStateOf(e?.amount?.toLong()?.toString() ?: "") }
    var category by remember { mutableStateOf(e?.category) }
    var visibility by remember { mutableStateOf(e?.visibility ?: "shared") }
    var reveal by remember { mutableStateOf(e?.revealOn ?: LocalDate.now().plusDays(30).toString()) }
    var guessable by remember { mutableStateOf(e?.guessable ?: false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (e == null) "記一筆 ✍️" else "編輯開銷", style = MaterialTheme.typography.titleLarge)
        CuteField(merchant, { merchant = it.take(60) }, "商家或項目", placeholder = "例如：鼎泰豐")
        CuteField(amount, { amount = it.filter(Char::isDigit).take(8) }, "金額（NT$）", keyboard = KeyboardType.Number)
        Text("類別", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.categories.forEach { c -> ChoiceChip(c.label, (category ?: PaymentParser.categorize(merchant)) == c.key, c.emoji) { category = c.key } }
        }
        Text("${s.partnerName} 看得到嗎", style = MaterialTheme.typography.labelLarge)
        Segmented(listOf("shared" to "看得到", "private" to "不給看", "surprise" to "禮物模式 🎁"), visibility) { visibility = it; if (it != "shared") guessable = false }
        if (visibility == "surprise") {
            DateField("這天之後才讓 ${s.partnerName} 看到", reveal) { reveal = it }
        }
        ToggleRow("開放 ${s.partnerName} 猜金額", guessable, "猜之前對方看不到金額", "💰", enabled = visibility == "shared") { guessable = it }
        CuteButton("儲存", Modifier.fillMaxWidth(), enabled = merchant.isNotBlank() && (amount.toLongOrNull() ?: 0) > 0 && !vm.busy) {
            val body = buildJsonObject {
                put("merchant", merchant.trim()); put("amount", amount.toDouble())
                put("category", category ?: PaymentParser.categorize(merchant)); put("visibility", visibility)
                if (visibility == "surprise") put("revealOn", reveal)
                put("guessable", guessable && visibility == "shared")
                if (e == null) put("source", "manual")
            }
            vm.act(if (visibility == "surprise") "已儲存，${Fmt.md(LocalDate.parse(reveal))} 前對方看不到 🤫" else "已記下 ✨", onDone = close) { it.saveExpense(id, body) }
        }
        if (e != null) SoftButton("刪除", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete) { vm.act("已刪除", onDone = close) { it.deleteExpense(e.id) } }
    }
}

/** 測試自動記帳：貼一則通知看看會怎麼記；下面是最近收到的付款通知（原文、有沒有記、為什麼） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParseTestSheet(vm: AppViewModel, close: () -> Unit) {
    // 通知標題、內容（LINE Pay 是 2026-10 實際收到的「LINE錢包」付款訊息）
    val samples = listOf(
        Triple("LINE Pay", "LINE錢包", "付款\nNT$ 24\n付款完成。\n2026.10.16 12:22 (GMT+0800)\n商店名稱\nOK超商\n付款後餘額\nNT$ 111"),
        Triple("信用卡", "國泰世華", "【國泰世華】您的卡片末四碼1234於10/15 12:03消費新台幣1,280元，商店：UNIQLO 微風南山店"),
        Triple("街口", "街口支付", "您已付款 65 元給 全家便利商店 松仁店"),
        Triple("行動支付", null, "Apple Pay 交易：台灣高鐵 NT$1,490"),
    )
    val log by vm.container.autoBookkeeping.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(samples[0].second) }
    var text by remember { mutableStateOf(samples[0].third) }
    val result = remember(title, text) { PaymentParser.parseNotification(PaymentParser.Notice("", title, listOf(text))) }
    val parsed = (result as? PaymentParser.Result.Payment)?.parsed
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("測試自動記帳 🤖", style = MaterialTheme.typography.titleLarge)
        Text("開啟通知存取權後，App 會在背景自動處理付款通知。這裡可以貼一則通知看看會怎麼被記下來。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            samples.forEach { (l, ti, t) -> ChoiceChip(l, text == t) { title = ti; text = t } }
        }
        CuteField(title.orEmpty(), { title = it.ifEmpty { null } }, "通知標題（例如 LINE錢包）")
        CuteField(text, { text = it }, "通知內容", singleLine = false)
        when {
            result is PaymentParser.Result.Skipped -> Note("不會記帳：${result.reason}", "🤔")
            parsed != null -> CuteCard(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("金額", Fmt.money(parsed.amount), Modifier.weight(1f))
                    StatTile("類別", Catalog.category(parsed.category).label, Modifier.weight(1f))
                }
                StatTile("商家", parsed.merchant, Modifier.fillMaxWidth(), sub = "來源：${Catalog.sources[parsed.source]}")
                if (!parsed.knownMerchant) {
                    Text("通知裡沒有店名，先記成「${parsed.merchant}」，之後點這筆就能改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        CuteButton("加入我的帳本", Modifier.fillMaxWidth(), enabled = parsed != null) {
            val p = parsed ?: return@CuteButton
            vm.act("已記下 ${p.merchant} ${Fmt.money(p.amount)}", onDone = close) {
                it.saveExpense(null, buildJsonObject {
                    put("merchant", p.merchant); put("amount", p.amount); put("category", p.category); put("source", "notification")
                })
            }
        }

        Text("最近收到的付款通知", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (log.isEmpty()) {
            Note("還沒有收到付款通知。開啟通知存取權後，付款通知會出現在這裡，看得到通知原文和有沒有記帳。", "📭")
        } else {
            log.forEach { e -> PaymentLogRow(e) { title = e.title.ifEmpty { null }; text = e.text } }
            Text("點一則可以把原文放到上面測試。紀錄只存在這支手機，最多留 ${tw.xiaoliangkou.app.service.AutoBookkeeping.MAX_ENTRIES} 則。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SoftButton("清除紀錄", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete) { scope.launch { vm.container.autoBookkeeping.clear() } }
        }
    }
}

@Composable
private fun PaymentLogRow(e: tw.xiaoliangkou.app.service.AutoBookkeeping.Entry, onClick: () -> Unit) {
    val (emoji, color) = when (e.outcome) {
        "記下" -> "✅" to Cute.colors.mint.copy(alpha = 0.25f)
        "補上店名" -> "✏️" to Cute.colors.sky.copy(alpha = 0.25f)
        else -> "⏭️" to MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color).bouncyClick(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TidyText(
            "$emoji ${e.outcome} · ${e.app}${e.title.takeIf { it.isNotBlank() && it != e.app }?.let { "（$it）" }.orEmpty()} · ${Fmt.rel(java.time.Instant.ofEpochMilli(e.at))}",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(e.detail, style = MaterialTheme.typography.bodyMedium)
        Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** 點一下跳出日期選擇器的欄位 */
@Composable
fun DateField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (open) 0.97f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "date")
    Column(
        modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(18.dp))
            .background(Cute.colors.card).bouncyClick { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val d = Fmt.parseDate(value)
        // 別年的日期（「2027/1/5（二）」）在半寬的欄位放不下一行：星期換到第二行，不會被切掉
        TidyText(
            d?.let { (if (it.year != LocalDate.now().year) "${it.year}/" else "") + "${it.monthValue}/${it.dayOfMonth}（${Fmt.weekday(it).substring(1)}）" } ?: "選擇日期",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Start,
        )
    }
    if (open) DatePickerPopup(value, onDismiss = { open = false }) { onChange(it); open = false }
}
