@file:OptIn(ExperimentalMaterial3Api::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perppilot.app.AppGraph
import com.perppilot.app.ui.components.Hint
import com.perppilot.app.ui.components.SectionCard
import com.perppilot.app.ui.components.StatGrid
import com.perppilot.app.ui.components.SwitchRow
import com.perppilot.app.ui.components.Tag
import com.perppilot.app.ui.components.fmtPrice
import com.perppilot.app.ui.components.fmtTime
import com.perppilot.app.ui.components.fmtUsd
import com.perppilot.app.ui.components.shortSym
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.Blue
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.Red
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.app.work.Scheduler
import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EventKind
import kotlinx.coroutines.launch

@Composable
fun BotScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val state by graph.engineStore.state.collectAsStateWithLifecycle()
    val mc = LocalMarketColors.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var confirmLive by remember { mutableStateOf(false) }
    val hasKeys = graph.credentials(settings.demoMode) != null
    val now = System.currentTimeMillis()

    Scaffold(topBar = { TopAppBar(title = { Text("自動交易機器人") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(if (settings.demoMode) "OKX 模擬盤" else "OKX 實盤", if (settings.demoMode) Blue else Red)
                        Spacer(Modifier.width(8.dp))
                        Tag(if (settings.engine.autoTrade) "自動交易：開" else "僅訊號通知", if (settings.engine.autoTrade) Accent else TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text("上次執行 ${fmtTime(state.lastRun)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    if (!hasKeys) {
                        Spacer(Modifier.height(8.dp))
                        Hint("尚未設定${if (settings.demoMode) "模擬盤" else "實盤"} API 金鑰，目前只會分析與通知。", Accent)
                        TextButton(onClick = onOpenSettings) { Text("前往設定 API") }
                    }
                    SwitchRow(
                        label = "自動開倉",
                        desc = "訊號出現時依風控自動下單，並在交易所掛止損",
                        checked = settings.engine.autoTrade,
                        enabled = hasKeys,
                        onChange = { on ->
                            if (on && !settings.demoMode && !settings.liveRiskAccepted) {
                                confirmLive = true
                            } else {
                                graph.settings.update { it.copy(engine = it.engine.copy(autoTrade = on)) }
                                Scheduler.sync(graph.context, graph.settings.current)
                            }
                        },
                    )
                    SwitchRow(
                        label = "內建策略進場",
                        desc = "關閉後只執行 TradingView 警報（已有倉位仍會管理）",
                        checked = settings.engine.useBuiltInStrategy,
                        onChange = { on -> graph.settings.update { it.copy(engine = it.engine.copy(useBuiltInStrategy = on)) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                scope.launch {
                                    runCatching { graph.runTradingCycle() }
                                    running = false
                                }
                            },
                        ) { Text(if (running) "執行中…" else "立即執行一次") }
                        if (state.risk.haltReason != null) {
                            OutlinedButton(onClick = { graph.resetHalt() }) { Text("解除熔斷") }
                        }
                    }
                }
            }
            item {
                SectionCard("帳戶與風控") {
                    val risk = state.risk
                    StatGrid(listOf(
                        Triple("權益 USDT", fmtUsd(state.lastEquity.takeIf { it > 0 }), null),
                        Triple("可用", fmtUsd(state.lastAvailable.takeIf { it > 0 }), null),
                        Triple("今日盈虧", "%+.2f%%".format(risk.dailyPnlPct(state.lastEquity)), mc.of(risk.dailyPnlPct(state.lastEquity))),
                        Triple("距高點回撤", "%.2f%%".format(risk.drawdownPct(state.lastEquity)), null),
                        Triple("連續虧損", "${risk.consecutiveLosses} 筆", null),
                        Triple("冷卻至", if (risk.cooldownUntil > now) fmtTime(risk.cooldownUntil) else "-", null),
                    ))
                    risk.haltReason?.let { Hint("⛔ $it", Red) }
                    Hint("單筆風險 ${settings.engine.risk.riskPerTradePct}%｜最多 ${settings.engine.risk.maxOpenPositions} 倉｜日虧上限 ${settings.engine.risk.dailyLossLimitPct}%｜回撤熔斷 ${settings.engine.risk.maxDrawdownPct}%")
                }
            }
            item {
                SectionCard("持倉（${state.positions.size}）") {
                    if (state.positions.isEmpty()) Hint("目前沒有由機器人管理的倉位。")
                    state.positions.values.forEach { t ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(shortSym(t.instId), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Tag(t.side.label, if (t.side.sign > 0) mc.up else mc.down)
                                Spacer(Modifier.width(6.dp))
                                Tag(t.origin, TextSecondary)
                                Spacer(Modifier.weight(1f))
                                Text("${t.size} 張", fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "進場 ${fmtPrice(t.entryPrice)}｜止損 ${fmtPrice(t.stop)}${if (t.breakEvenDone) "（已保本）" else ""}" +
                                    (t.takeProfit?.let { "｜止盈 ${fmtPrice(it)}" } ?: "") + "｜持有 ${t.barsHeld} 根K",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                            )
                        }
                    }
                }
            }
            item {
                val j = state.journal
                SectionCard("交易紀錄（${j.size}）") {
                    if (j.isNotEmpty()) {
                        val wins = j.count { it.realizedPnl > 0 }
                        StatGrid(listOf(
                            Triple("勝率", "%.1f%%".format(wins * 100.0 / j.size), null),
                            Triple("總損益", "%+.2f".format(j.sumOf { it.realizedPnl }), mc.of(j.sumOf { it.realizedPnl })),
                            Triple("平均 R", "%+.2f".format(j.map { it.rMultiple }.average()), mc.of(j.map { it.rMultiple }.average())),
                        ))
                    } else Hint("尚無已平倉交易。")
                    j.take(30).forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${shortSym(e.instId)} ${e.side.label}｜${e.exitReason}", style = MaterialTheme.typography.bodySmall)
                                Text("${fmtTime(e.entryTime)} → ${fmtTime(e.exitTime)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("%+.2f".format(e.realizedPnl), color = mc.of(e.realizedPnl), fontFamily = Mono)
                                Text("%+.2fR".format(e.rMultiple), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }
            }
            item { Text("事件記錄", style = MaterialTheme.typography.titleMedium) }
            items(state.events.take(60)) { e -> EventRow(e) }
        }
    }
    if (confirmLive) {
        LiveRiskDialog(
            onDismiss = { confirmLive = false },
            onAccept = {
                confirmLive = false
                graph.settings.update { it.copy(liveRiskAccepted = true, engine = it.engine.copy(autoTrade = true)) }
                Scheduler.sync(graph.context, graph.settings.current)
            },
        )
    }
}

@Composable
fun EventRow(e: EngineEvent) {
    val color = when (e.kind) {
        EventKind.ERROR, EventKind.RISK -> Red
        EventKind.ORDER, EventKind.CLOSE -> Accent
        EventKind.SIGNAL -> Blue
        else -> TextSecondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(fmtTime(e.time), style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(78.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
            Text(e.message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
fun LiveRiskDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("啟用實盤自動交易") },
        text = {
            Text(
                "• 程式將使用真實資金在 OKX 下單。\n" +
                    "• 回測顯示策略在趨勢行情獲利、在震盪行情虧損，獲利並不穩定，過去績效不代表未來。\n" +
                    "• 請先在模擬盤運行至少數週，並只投入可承受全部損失的資金。\n" +
                    "• API 金鑰請只開啟「交易」權限，切勿開啟「提幣」，並綁定 IP 白名單（若可行）。\n" +
                    "• 手機斷網或休眠時，交易所端止損仍會保護倉位，但移動止損與新訊號會延遲。",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("我了解風險，啟用", color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
