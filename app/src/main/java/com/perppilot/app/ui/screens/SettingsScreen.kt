@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perppilot.app.AppGraph
import com.perppilot.app.data.SecretKey
import com.perppilot.app.ui.components.FieldRow
import com.perppilot.app.ui.components.Hint
import com.perppilot.app.ui.components.NumberField
import com.perppilot.app.ui.components.SectionCard
import com.perppilot.app.ui.components.SwitchRow
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.Red
import com.perppilot.app.work.MonitorService
import com.perppilot.app.work.Scheduler
import com.perppilot.core.model.Timeframe
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.strategy.MeanRevConfig
import com.perppilot.core.strategy.OrbConfig
import com.perppilot.core.strategy.StrategyConfig
import com.perppilot.core.strategy.StrategyType
import com.perppilot.core.strategy.TradingSession
import kotlinx.coroutines.launch
import java.security.SecureRandom

@Composable
fun SettingsScreen(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var showLiveDialog by remember { mutableStateOf(false) }
    val st = settings.engine.strategy
    val rk = settings.engine.risk
    fun strategy(f: (StrategyConfig) -> StrategyConfig) = graph.settings.update { it.copy(engine = it.engine.copy(strategy = f(it.engine.strategy))) }
    fun risk(f: (RiskConfig) -> RiskConfig) = graph.settings.update { it.copy(engine = it.engine.copy(risk = f(it.engine.risk))) }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ------------------------------------------------------------- exchange
            SectionCard("OKX 交易所") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(settings.demoMode, { graph.settings.update { it.copy(demoMode = true) } }, label = { Text("模擬盤（建議）") })
                    FilterChip(!settings.demoMode, {
                        if (!settings.liveRiskAccepted) showLiveDialog = true
                        else graph.settings.update { it.copy(demoMode = false) }
                    }, label = { Text("實盤") })
                }
                Text("API 網域", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("https://www.okx.com", "https://my.okx.com", "https://app.okx.com").forEach { u ->
                        FilterChip(settings.baseUrl == u, { graph.settings.update { it.copy(baseUrl = u) } }, label = { Text(u.removePrefix("https://")) })
                    }
                }
                val demo = settings.demoMode
                KeyFields(
                    graph,
                    title = if (demo) "模擬盤 API（在 OKX「模擬交易」中建立）" else "實盤 API",
                    keys = if (demo) Triple(SecretKey.DEMO_API_KEY, SecretKey.DEMO_SECRET, SecretKey.DEMO_PASSPHRASE)
                    else Triple(SecretKey.LIVE_API_KEY, SecretKey.LIVE_SECRET, SecretKey.LIVE_PASSPHRASE),
                )
                Hint("權限只需「讀取」＋「交易」，切勿勾選「提幣」。金鑰以 Android Keystore AES-256 加密存放在本機。", Accent)
                OutlinedButton(onClick = {
                    testResult = "連線中…"
                    scope.launch {
                        val client = graph.tradingClient()
                        testResult = if (client == null) "尚未完整填寫 API 金鑰" else runCatching {
                            val cfg = client.accountConfig()
                            val bal = client.balance()
                            "✅ 連線成功｜帳戶模式 ${cfg.accountLevel}${if (!cfg.supportsDerivatives) "（簡單模式不支援合約，請切換）" else ""}｜持倉模式 ${if (cfg.hedgeMode) "雙向" else "單向"}｜權益 %.2f USDT".format(bal.totalEquity)
                        }.getOrElse { "❌ ${it.message}" }
                    }
                }) { Text("測試連線") }
                testResult?.let { Hint(it) }
            }

            // ------------------------------------------------------------- strategy choice
            StrategyPicker(graph)

            // ------------------------------------------------------------- strategy
            SectionCard("趨勢跟隨策略參數") {
                Text("週期（進場 / 趨勢）", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Timeframe.H4 to Timeframe.D1, Timeframe.H1 to Timeframe.H4).forEach { (l, h) ->
                        FilterChip(st.ltfFrame == l && st.htfFrame == h, { strategy { it.copy(ltfFrame = l, htfFrame = h) } }, label = { Text("${l.label} / ${h.label}") })
                    }
                }
                Text("進場時段", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TradingSession.entries.forEach { s ->
                        FilterChip(st.session == s, { strategy { it.copy(session = s) } }, label = { Text(s.label) })
                    }
                }
                FieldRow {
                    NumberField("ADX 門檻", st.adxMin, { v -> strategy { it.copy(adxMin = v.coerceIn(5.0, 60.0)) } }, Modifier.weight(1f))
                    NumberField("止損 ATR 倍數", st.stopAtrMult, { v -> strategy { it.copy(stopAtrMult = v.coerceIn(0.5, 6.0)) } }, Modifier.weight(1f))
                }
                FieldRow {
                    NumberField("移動止損 ATR", st.trailAtrMult, { v -> strategy { it.copy(trailAtrMult = v.coerceIn(1.0, 10.0)) } }, Modifier.weight(1f))
                    NumberField("保本觸發 R", st.breakEvenR, { v -> strategy { it.copy(breakEvenR = v.coerceIn(0.0, 5.0)) } }, Modifier.weight(1f))
                }
                FieldRow {
                    NumberField("固定止盈 R（0=關）", st.takeProfitR, { v -> strategy { it.copy(takeProfitR = v.coerceIn(0.0, 20.0)) } }, Modifier.weight(1f))
                    NumberField("時間止損 K棒", st.maxHoldBars.toDouble(), { v -> strategy { it.copy(maxHoldBars = v.toInt().coerceIn(0, 1000)) } }, Modifier.weight(1f), integer = true)
                }
                NumberField("資金費率過濾 ±", st.maxFundingRatePct, { v -> strategy { it.copy(maxFundingRatePct = v.coerceIn(0.0, 1.0)) } }, Modifier.fillMaxWidth(), "%")
                SwitchRow("回踩續勢進場", st.usePullback, { on -> strategy { it.copy(usePullback = on) } })
                SwitchRow("放量突破進場", st.useBreakout, { on -> strategy { it.copy(useBreakout = on) } })
                SwitchRow("允許做多", st.allowLongs, { on -> strategy { it.copy(allowLongs = on) } })
                SwitchRow("允許做空", st.allowShorts, { on -> strategy { it.copy(allowShorts = on) } })
                OutlinedButton(onClick = { strategy { StrategyConfig() } }) { Text("恢復預設策略") }
            }

            // ------------------------------------------------------------- risk
            SectionCard("風險控管") {
                FieldRow {
                    NumberField("單筆風險", rk.riskPerTradePct, { v -> risk { it.copy(riskPerTradePct = v.coerceIn(0.05, 5.0)) } }, Modifier.weight(1f), "%")
                    NumberField("槓桿", rk.leverage.toDouble(), { v -> risk { it.copy(leverage = v.toInt().coerceIn(1, 20)) } }, Modifier.weight(1f), "x", integer = true)
                }
                FieldRow {
                    NumberField("最多持倉", rk.maxOpenPositions.toDouble(), { v -> risk { it.copy(maxOpenPositions = v.toInt().coerceIn(1, 10)) } }, Modifier.weight(1f), integer = true)
                    NumberField("總風險上限", rk.maxTotalRiskPct, { v -> risk { it.copy(maxTotalRiskPct = v.coerceIn(0.1, 20.0)) } }, Modifier.weight(1f), "%")
                }
                FieldRow {
                    NumberField("每日虧損上限", rk.dailyLossLimitPct, { v -> risk { it.copy(dailyLossLimitPct = v.coerceIn(0.0, 50.0)) } }, Modifier.weight(1f), "%")
                    NumberField("回撤熔斷", rk.maxDrawdownPct, { v -> risk { it.copy(maxDrawdownPct = v.coerceIn(0.0, 90.0)) } }, Modifier.weight(1f), "%")
                }
                FieldRow {
                    NumberField("連虧暫停（筆）", rk.maxConsecutiveLosses.toDouble(), { v -> risk { it.copy(maxConsecutiveLosses = v.toInt().coerceIn(0, 20)) } }, Modifier.weight(1f), integer = true)
                    NumberField("暫停時數", rk.cooldownHours.toDouble(), { v -> risk { it.copy(cooldownHours = v.toInt().coerceIn(0, 168)) } }, Modifier.weight(1f), "h", integer = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(rk.marginMode == "isolated", { risk { it.copy(marginMode = "isolated") } }, label = { Text("逐倉（建議）") })
                    FilterChip(rk.marginMode == "cross", { risk { it.copy(marginMode = "cross") } }, label = { Text("全倉") })
                }
                Hint("倉位大小 = 權益 × 單筆風險 ÷ 止損距離；槓桿只影響保證金佔用。止損距離超過強平距離一半的交易會被拒絕。")
            }

            // ------------------------------------------------------------- TradingView
            TradingViewSection(graph)

            // ------------------------------------------------------------- notifications
            SectionCard("通知") {
                SwitchRow("背景掃描（每 15 分鐘）", settings.backgroundScan, { on ->
                    graph.settings.update { it.copy(backgroundScan = on) }
                    Scheduler.sync(graph.context, graph.settings.current)
                }, desc = "分析觀察清單、推送訊號、管理移動止損")
                SwitchRow("交易訊號通知", settings.engine.notifySignals, { on -> graph.settings.update { it.copy(engine = it.engine.copy(notifySignals = on)) } })
                SwitchRow("移動止損通知", settings.notifyStops, { on -> graph.settings.update { it.copy(notifyStops = on) } })
                SwitchRow("同步推送到 Telegram", settings.telegramEnabled, { on -> graph.settings.update { it.copy(telegramEnabled = on) } })
                if (settings.telegramEnabled) {
                    SecretField(graph, SecretKey.TELEGRAM_TOKEN, "Bot Token（@BotFather 取得）")
                    var chat by remember { mutableStateOf(settings.telegramChatId) }
                    OutlinedTextField(chat, { chat = it; graph.settings.update { s -> s.copy(telegramChatId = it.trim()) } }, label = { Text("Chat ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    var tgResult by remember { mutableStateOf<String?>(null) }
                    OutlinedButton(onClick = { graph.notifier.sendTelegram("測試訊息 ✅") { tgResult = it ?: "已送出" } }) { Text("傳送測試") }
                    tgResult?.let { Hint(it) }
                }
            }

            SectionCard("顯示") {
                SwitchRow("紅漲綠跌", settings.redUp, { on -> graph.settings.update { it.copy(redUp = on) } })
            }

            SectionCard("風險聲明") {
                Hint(
                    "本程式為技術分析與自動化工具，不保證獲利。內建策略在 2022–2026 的回測中，趨勢年份獲利、震盪年份虧損，" +
                        "最大回撤約 15–25%。槓桿合約可能在短時間內損失全部保證金。請先以模擬盤驗證，並自行承擔交易決策與結果。",
                    Red,
                )
            }
        }
    }
    if (showLiveDialog) {
        LiveRiskDialog(
            onDismiss = { showLiveDialog = false },
            onAccept = {
                showLiveDialog = false
                graph.settings.update { it.copy(liveRiskAccepted = true, demoMode = false) }
            },
        )
    }
}

@Composable
private fun StrategyPicker(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val e = settings.engine
    fun orb(f: (OrbConfig) -> OrbConfig) = graph.settings.update { it.copy(engine = it.engine.copy(orb = f(it.engine.orb))) }
    fun mr(f: (MeanRevConfig) -> MeanRevConfig) = graph.settings.update { it.copy(engine = it.engine.copy(meanRev = f(it.engine.meanRev))) }

    SectionCard("進場策略") {
        StrategyType.entries.forEach { t ->
            val selected = e.strategyType == t
            FilterChip(
                selected = selected,
                onClick = {
                    graph.settings.update { it.copy(engine = it.engine.copy(strategyType = t)) }
                    MonitorService.sync(graph.context)
                },
                label = { Text(t.label + "｜" + StrategyVerdicts.badge(t)) },
            )
            if (selected) {
                Hint(t.description)
                Hint(StrategyVerdicts.detail(t), if (StrategyVerdicts.validated(t)) Accent else Red)
            }
        }
        Hint("已有倉位一律由開倉時的策略管理到平倉；切換只影響新進場。5／15 分鐘策略會啟動前景服務每分鐘執行。")

        when (e.strategyType) {
            StrategyType.ORB -> {
                val c = e.orb
                Text("開盤區間突破參數", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Timeframe.M5 to Timeframe.H1, Timeframe.M15 to Timeframe.H4).forEach { (l, h) ->
                        FilterChip(c.ltfFrame == l, { orb { it.copy(ltfFrame = l, htfFrame = h) } }, label = { Text("${l.label} / ${h.label}") })
                    }
                }
                FieldRow {
                    NumberField("區間分鐘", c.rangeMinutes.toDouble(), { v -> orb { it.copy(rangeMinutes = v.toInt().coerceIn(5, 120)) } }, Modifier.weight(1f), integer = true)
                    NumberField("止盈 R（0=收盤平）", c.takeProfitR, { v -> orb { it.copy(takeProfitR = v.coerceIn(0.0, 10.0)) } }, Modifier.weight(1f))
                }
                SwitchRow("順大週期趨勢", c.trendFilter, { on -> orb { it.copy(trendFilter = on) } })
                SwitchRow("止損放在區間中線", c.stopAtMid, { on -> orb { it.copy(stopAtMid = on) } })
                Hint("區間：美東 09:30 起 ${c.rangeMinutes} 分鐘；12:00 後不再進場；16:00 前全部平倉。")
            }
            StrategyType.MEAN_REVERSION -> {
                val c = e.meanRev
                Text("均值回歸參數", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Timeframe.M5 to Timeframe.H1, Timeframe.M15 to Timeframe.H4, Timeframe.H1 to Timeframe.H4).forEach { (l, h) ->
                        FilterChip(c.ltfFrame == l && c.htfFrame == h, { mr { it.copy(ltfFrame = l, htfFrame = h) } }, label = { Text("${l.label} / ${h.label}") })
                    }
                }
                FieldRow {
                    NumberField("布林倍數", c.bbMult, { v -> mr { it.copy(bbMult = v.coerceIn(1.0, 4.0)) } }, Modifier.weight(1f))
                    NumberField("RSI(2) 極值", c.rsiExtreme, { v -> mr { it.copy(rsiExtreme = v.coerceIn(1.0, 30.0)) } }, Modifier.weight(1f))
                }
                FieldRow {
                    NumberField("大週期 ADX 上限", c.maxHtfAdx, { v -> mr { it.copy(maxHtfAdx = v.coerceIn(0.0, 60.0)) } }, Modifier.weight(1f))
                    NumberField("止損 ATR", c.stopAtrMult, { v -> mr { it.copy(stopAtrMult = v.coerceIn(0.5, 5.0)) } }, Modifier.weight(1f))
                }
                NumberField("最長持有 K 棒", c.maxHoldBars.toDouble(), { v -> mr { it.copy(maxHoldBars = v.toInt().coerceIn(1, 200)) } }, Modifier.fillMaxWidth(), integer = true)
                Text("交易時段", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TradingSession.entries.forEach { ss -> FilterChip(c.session == ss, { mr { it.copy(session = ss) } }, label = { Text(ss.label) }) }
                }
            }
            StrategyType.TREND -> Unit
        }
    }
}

@Composable
private fun KeyFields(graph: AppGraph, title: String, keys: Triple<SecretKey, SecretKey, SecretKey>) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    SecretField(graph, keys.first, "API Key")
    SecretField(graph, keys.second, "Secret Key")
    SecretField(graph, keys.third, "Passphrase")
}

/** Write-only field: shows whether a value is stored, never displays it back. */
@Composable
private fun SecretField(graph: AppGraph, key: SecretKey, label: String) {
    var text by remember(key) { mutableStateOf("") }
    var stored by remember(key) { mutableStateOf(graph.secure.has(key)) }
    FieldRow {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(if (stored) "$label（已儲存）" else label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                graph.secure.put(key, text)
                stored = text.isNotBlank()
                text = ""
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (text.isBlank() && stored) "清除" else "儲存") }
    }
}

@Composable
private fun TradingViewSection(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val cfg = settings.signals
    var status by remember { mutableStateOf<String?>(null) }
    fun update(f: (com.perppilot.core.signals.ExternalSignalConfig) -> com.perppilot.core.signals.ExternalSignalConfig) =
        graph.settings.update { it.copy(signals = f(it.signals)) }

    SectionCard("TradingView 警報自動交易") {
        Hint("流程：TradingView 警報 → Webhook → ntfy 中繼 → 本 App（每 15 秒）→ 風控檢查 → OKX 下單。Webhook 需 TradingView 付費方案。")
        if (cfg.ntfyTopic.isBlank() || cfg.secret.isBlank()) {
            Button(onClick = {
                update { it.copy(ntfyTopic = newTopic(), secret = randomToken(16)) }
            }) { Text("產生 Webhook 位址與密鑰") }
        } else {
            val url = graph.relayClient()?.webhookUrl.orEmpty()
            Text("Webhook URL（貼到 TradingView 警報的 Webhook 欄位）", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(url, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = Accent) }
            val template = alertTemplate(cfg.secret)
            Text("警報訊息範本（Pine 腳本已內建，也可手動貼上）", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(template, fontFamily = Mono, style = MaterialTheme.typography.labelSmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("複製 URL") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(template)) }) { Text("複製訊息") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(cfg.secret)) }) { Text("複製密鑰") }
                OutlinedButton(onClick = {
                    status = "傳送測試警報…"
                    scope.launch {
                        status = runCatching {
                            graph.relayClient()!!.publish("""{"secret":"${cfg.secret}","action":"long","symbol":"BTCUSDT.P","note":"測試（請勿交易）","time":"${java.time.Instant.now()}"}""")
                            val n = graph.pollRelay()
                            "已送出並接收 $n 則（若自動交易開啟，BTC 不在觀察清單或風控不允許時不會下單）"
                        }.getOrElse { "失敗：${it.message}" }
                    }
                }) { Text("測試") }
            }
            status?.let { Hint(it) }
            Hint("此 URL 等同密碼：任何知道它的人都能發送警報，因此另需密鑰比對。外洩時請重新產生。", Red)
            Hint("ntfy.sh 免費版有每日訊息額度；TradingView 的 Webhook 來自少數共用 IP，繁忙時可能被拒（HTTP 429）。需要高可靠度請自架 ntfy（一行 Docker）並在下方填入伺服器位址。")
            OutlinedButton(onClick = { update { it.copy(ntfyTopic = newTopic(), secret = randomToken(16)) } }) { Text("重新產生") }
        }
        SwitchRow("啟用 TradingView 訊號", cfg.enabled, { on ->
            update { it.copy(enabled = on) }
            MonitorService.sync(graph.context)
            Scheduler.sync(graph.context, graph.settings.current)
        }, desc = "啟用後會常駐前景服務以即時接收警報", enabled = cfg.ntfyTopic.isNotBlank())
        SwitchRow("由 App 管理出場", cfg.appManagesExits, { on -> update { it.copy(appManagesExits = on) } }, desc = "開：保本＋移動止損；關：只保留初始止損，等 TradingView 平倉警報")
        SwitchRow("只交易觀察清單", cfg.requireWatchlist, { on -> update { it.copy(requireWatchlist = on) } })
        SwitchRow("反向訊號自動反手", cfg.allowReverse, { on -> update { it.copy(allowReverse = on) } })
        NumberField("訊號有效秒數", cfg.maxAgeSeconds.toDouble(), { v -> update { it.copy(maxAgeSeconds = v.toInt().coerceIn(30, 3600)) } }, Modifier.fillMaxWidth(), "秒", integer = true)
        var server by remember { mutableStateOf(cfg.ntfyServer) }
        OutlinedTextField(server, { server = it; update { c -> c.copy(ntfyServer = it.trim()) } }, label = { Text("ntfy 伺服器（可自架）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Hint("TradingView 訊號同樣經過單筆風險、持倉上限、日虧與熔斷檢查；警報未附 stop 時以 ${settings.engine.strategy.stopAtrMult} ATR 自動設止損。")
    }
}

fun alertTemplate(secret: String): String =
    """{"secret":"$secret","action":"{{strategy.order.action}}","position":"{{strategy.market_position}}","symbol":"{{ticker}}","price":{{close}},"time":"{{timenow}}"}"""

/**
 * "up"-prefixed topics are UnifiedPush-style: when the server enables subscriber-based rate limiting,
 * publishes are charged to the subscriber (this phone) instead of TradingView's shared webhook IPs.
 */
private fun newTopic(): String = "up" + randomToken(18)

private fun randomToken(n: Int): String {
    val chars = "abcdefghijkmnopqrstuvwxyz23456789"
    val rnd = SecureRandom()
    return (1..n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}
