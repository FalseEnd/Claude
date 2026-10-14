@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.perppilot.app.ui.components.FieldRow
import com.perppilot.app.ui.components.Hint
import com.perppilot.app.ui.components.NumberField
import com.perppilot.app.ui.components.SectionCard
import com.perppilot.app.ui.components.StatGrid
import com.perppilot.app.ui.components.SwitchRow
import com.perppilot.app.ui.components.Tag
import com.perppilot.app.ui.components.fmtPrice
import com.perppilot.app.ui.components.fmtTime
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.Red
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.app.work.MonitorService
import com.perppilot.core.events.EventSeries
import kotlinx.coroutines.launch

@Composable
fun EventsScreen(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val state by graph.eventStore.state.collectAsStateWithLifecycle()
    val serviceOn by MonitorService.isRunning.collectAsStateWithLifecycle()
    val mc = LocalMarketColors.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var series by remember { mutableStateOf<List<EventSeries>>(emptyList()) }
    var confirmAuto by remember { mutableStateOf(false) }
    val cfg = settings.events
    LaunchedEffect(Unit) {
        series = runCatching { graph.publicClient().eventSeries() }.getOrDefault(emptyList())
            .filter { !it.seriesId.uppercase().startsWith("TEST") }
            .sortedBy { it.seriesId }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("事件合約") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("先讀這段") {
                    Hint(
                        "事件合約是二元結果：猜對每張拿回 1 USDT，猜錯歸零。價格 0.6 代表市場認為約 60% 機率。" +
                            "以 0.5 買入、手續費約 0.02，勝率需約 54% 才打平。短週期漲跌接近擲硬幣。",
                    )
                    Hint(
                        "本頁用「指數即時波動率」計算每份合約的理論機率，只在市價明顯偏離（扣手續費後期望值 ≥ ${"%.0f".format(cfg.minEdge * 100)} 美分）時提示。" +
                            "7 天歷史回測：模型與成交價同步時看似有優勢，但只要模型資訊延遲 1 分鐘就轉為虧損，代表優勢主要來自時間差，手機輪詢很難持續取得。",
                        Accent,
                    )
                    Hint("建議僅用於觀察與通知；自動下注預設關閉，且有每日虧損上限。", Red)
                }
            }
            item {
                SectionCard("監控設定") {
                    SwitchRow(
                        "即時監控（前景服務，每 30 秒）",
                        settings.eventMonitor,
                        { on ->
                            graph.settings.update { it.copy(eventMonitor = on) }
                            MonitorService.sync(graph.context)
                        },
                        desc = if (serviceOn) "服務執行中" else "服務未啟動",
                    )
                    SwitchRow("機會通知", cfg.notify, { on -> graph.settings.update { it.copy(events = it.events.copy(notify = on)) } })
                    SwitchRow(
                        "自動下注（高風險）",
                        cfg.autoTrade,
                        { on ->
                            if (on) confirmAuto = true
                            else graph.settings.update { it.copy(events = it.events.copy(autoTrade = false)) }
                        },
                        desc = "以限價單買入被低估的一方，四分之一凱利，單事件上限 ${cfg.maxStakePct}% 權益",
                    )
                    FieldRow {
                        NumberField("最低期望值", cfg.minEdge, { v -> graph.settings.update { it.copy(events = it.events.copy(minEdge = v.coerceIn(0.01, 0.5))) } }, Modifier.weight(1f))
                        NumberField("手續費係數 K", cfg.feeK, { v -> graph.settings.update { it.copy(events = it.events.copy(feeK = v.coerceIn(0.0, 0.5))) } }, Modifier.weight(1f))
                    }
                    FieldRow {
                        NumberField("單事件上限", cfg.maxStakePct, { v -> graph.settings.update { it.copy(events = it.events.copy(maxStakePct = v.coerceIn(0.1, 10.0))) } }, Modifier.weight(1f), "%")
                        NumberField("每日虧損上限", cfg.dailyLossLimitPct, { v -> graph.settings.update { it.copy(events = it.events.copy(dailyLossLimitPct = v.coerceIn(0.5, 20.0))) } }, Modifier.weight(1f), "%")
                    }
                    Text("監控系列", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        series.filter { it.freq in setOf("five_min", "fifteen_min", "hourly", "daily") }.forEach { s ->
                            val on = s.seriesId in cfg.series
                            FilterChip(
                                selected = on,
                                onClick = {
                                    graph.settings.update {
                                        val list = if (on) it.events.series - s.seriesId else it.events.series + s.seriesId
                                        it.copy(events = it.events.copy(series = list))
                                    }
                                },
                                label = { Text(s.seriesId) },
                            )
                        }
                    }
                    Button(
                        enabled = !scanning,
                        onClick = {
                            scanning = true
                            scope.launch {
                                runCatching { graph.runEventCycle() }
                                scanning = false
                            }
                        },
                    ) { Text(if (scanning) "掃描中…" else "立即掃描") }
                }
            }
            item {
                SectionCard("目前機會（${state.opportunities.size}）｜${fmtTime(state.lastRun)}") {
                    if (state.opportunities.isEmpty()) Hint("沒有符合條件的錯價。大多數時候市場定價合理，這是正常的。")
                    state.opportunities.forEach { op ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(op.instId, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Tag(op.outcome.label(op.method), Accent)
                            }
                            Text(
                                "指數 ${fmtPrice(op.spot)} / 履約 ${fmtPrice(op.strike)}｜剩 %.0f 分｜模型 %.0f%%｜市價 %.2f｜期望 %+.3f".format(
                                    op.minutesLeft, op.fairProb * 100, op.ask, op.edge,
                                ),
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontFamily = Mono,
                            )
                        }
                    }
                }
            }
            item {
                val settled = state.bets.filter { it.settled && it.filled > 0 }
                SectionCard("下注紀錄（${state.bets.size}）") {
                    if (settled.isNotEmpty()) {
                        val pnl = settled.sumOf { it.pnl ?: 0.0 }
                        StatGrid(listOf(
                            Triple("已結算", "${settled.size}", null),
                            Triple("勝率", "%.0f%%".format(settled.count { it.won == true } * 100.0 / settled.size), null),
                            Triple("總損益", "%+.2f".format(pnl), mc.of(pnl)),
                        ))
                    }
                    if (state.bets.isEmpty()) Hint("尚無下注。")
                    state.bets.take(40).forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${b.instId} ${b.outcome.label(b.method)}", style = MaterialTheme.typography.bodySmall)
                                Text("${fmtTime(b.placedAt)}｜限價 ${b.limitPrice}｜成交 ${b.filled}/${b.contracts}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                            Text(
                                when {
                                    !b.settled -> "等待結算"
                                    b.filled <= 0 -> "未成交"
                                    else -> "%+.2f".format(b.pnl ?: 0.0)
                                },
                                color = if (b.settled && b.filled > 0) mc.of(b.pnl ?: 0.0) else TextSecondary,
                                fontFamily = Mono,
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.width(1.dp))
                Hint("事件合約於美國、英國、歐洲經濟區不提供。API 下單參數依 OKX 文件（outcome=yes/no、tdMode=isolated），請先以極小金額驗證。")
            }
        }
    }
    if (confirmAuto) {
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("開啟事件合約自動下注？") },
            text = {
                Text(
                    "回測未能證明此模型有穩定優勢（考慮資訊延遲後為虧損）。開啟後程式會以真實資金（或模擬盤，若 OKX 支援）買入事件合約，" +
                        "單筆最多虧損全部投入。建議維持關閉，只使用通知。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAuto = false
                    graph.settings.update { it.copy(events = it.events.copy(autoTrade = true)) }
                }) { Text("仍要開啟", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("取消") } },
        )
    }
}
