@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.perppilot.app.AppGraph
import com.perppilot.app.ui.components.Hint
import com.perppilot.app.ui.components.LineChart
import com.perppilot.app.ui.components.SectionCard
import com.perppilot.app.ui.components.StatGrid
import com.perppilot.app.ui.components.fmtPrice
import com.perppilot.app.ui.components.fmtTime
import com.perppilot.app.ui.components.shortSym
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.core.backtest.BacktestConfig
import com.perppilot.core.backtest.BacktestResult
import com.perppilot.core.backtest.Backtester
import com.perppilot.core.backtest.SymbolData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BacktestUi(val running: Boolean = false, val progress: String = "", val result: BacktestResult? = null, val error: String? = null)

class BacktestViewModel(private val graph: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(BacktestUi())
    val ui = _ui.asStateFlow()

    fun run(symbols: List<String>, days: Int) {
        if (_ui.value.running || symbols.isEmpty()) return
        _ui.value = BacktestUi(running = true, progress = "準備中…")
        viewModelScope.launch {
            try {
                val s = graph.settings.current
                val strategy = s.engine.activeStrategy()
                val client = graph.publicClient(s)
                val start = System.currentTimeMillis() - days * 86_400_000L
                val warm = start - 260 * strategy.htfFrame.millis
                val data = symbols.map { inst ->
                    _ui.value = _ui.value.copy(progress = "下載 ${shortSym(inst)} ${strategy.ltfFrame.label} K線…")
                    val ltf = client.historyCandles(inst, strategy.ltfFrame, warm) { n -> _ui.value = _ui.value.copy(progress = "下載 ${shortSym(inst)}：$n 根") }
                    val htf = client.historyCandles(inst, strategy.htfFrame, warm)
                    SymbolData(inst, ltf, htf)
                }
                _ui.value = _ui.value.copy(progress = "回測計算中…")
                val result = withContext(Dispatchers.Default) {
                    Backtester(strategy, s.engine.risk, BacktestConfig(startTime = start)).run(data)
                }
                _ui.value = BacktestUi(result = result)
            } catch (e: Exception) {
                _ui.value = BacktestUi(error = e.message ?: "回測失敗")
            }
        }
    }
}

@Composable
fun BacktestScreen(graph: AppGraph) {
    val vm: BacktestViewModel = viewModel { BacktestViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val mc = LocalMarketColors.current
    var selected by remember { mutableStateOf(settings.engine.watchlist.toSet()) }
    var days by remember { mutableIntStateOf(365) }

    Scaffold(topBar = { TopAppBar(title = { Text("策略回測") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("回測設定") {
                    Text("合約", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.engine.watchlist.forEach { inst ->
                            FilterChip(selected = inst in selected, onClick = { selected = if (inst in selected) selected - inst else selected + inst }, label = { Text(shortSym(inst)) })
                        }
                    }
                    Text("期間", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(90, 180, 365, 730, 1460).forEach { d ->
                            FilterChip(selected = days == d, onClick = { days = d }, label = { Text(if (d >= 365) "${d / 365} 年" else "$d 天") })
                        }
                    }
                    val active = settings.engine.activeStrategy()
                    Hint("策略：${active.type.label}（${active.ltfFrame.label}/${active.htfFrame.label}），使用目前的風控設定，含手續費 0.05%、滑價 0.02%、資金費 0.01%/8h。訊號於收盤產生、下一根開盤成交。")
                    if (active.ltfFrame.millis <= com.perppilot.core.model.Timeframe.M15.millis && days > 180) {
                        Hint("短週期 K 線資料量大，下載 ${days} 天可能需要數分鐘。", com.perppilot.app.ui.theme.Accent)
                    }
                    Button(enabled = !ui.running && selected.isNotEmpty(), onClick = { vm.run(selected.toList(), days) }) { Text("開始回測") }
                    if (ui.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        Hint(ui.progress)
                    }
                    ui.error?.let { Hint(it, MaterialTheme.colorScheme.error) }
                }
            }
            val r = ui.result ?: return@LazyColumn
            val m = r.metrics
            item {
                SectionCard("績效") {
                    StatGrid(listOf(
                        Triple("總報酬", "%+.1f%%".format(m.totalReturnPct), mc.of(m.totalReturnPct)),
                        Triple("年化", "%+.1f%%".format(m.cagrPct), mc.of(m.cagrPct)),
                        Triple("最大回撤", "%.1f%%".format(m.maxDrawdownPct), null),
                        Triple("交易數", "${m.trades}", null),
                        Triple("勝率", "%.1f%%".format(m.winRatePct), null),
                        Triple("盈虧比 PF", "%.2f".format(m.profitFactor), null),
                        Triple("期望值", "%+.2fR".format(m.expectancyR), mc.of(m.expectancyR)),
                        Triple("Sharpe", "%.2f".format(m.sharpe), null),
                        Triple("最長連虧", "${m.maxConsecutiveLosses}", null),
                        Triple("平均贏", "%+.2fR".format(m.avgWinR), null),
                        Triple("平均輸", "%+.2fR".format(m.avgLossR), null),
                        Triple("成本合計", "%.0f".format(m.totalFees), null),
                    ))
                    val step = maxOf(1, r.equityCurve.size / 400)
                    LineChart(r.equityCurve.filterIndexed { i, _ -> i % step == 0 }.map { it.second }, baseline = m.initialEquity)
                    Hint("初始 ${"%.0f".format(m.initialEquity)} → 最終 ${"%.0f".format(m.finalEquity)} USDT｜多 ${m.longTrades} 空 ${m.shortTrades}｜熔斷 ${m.killSwitchTriggers} 次")
                    Hint("回測不代表未來績效；不同期間結果差異很大，趨勢策略在震盪年可能虧損。")
                }
            }
            item { Text("最近交易", style = MaterialTheme.typography.titleMedium) }
            items(r.trades.takeLast(60).reversed()) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${shortSym(t.instId)} ${t.side.label}｜${t.exitReason}", style = MaterialTheme.typography.bodySmall)
                        Text("${fmtTime(t.entryTime)} ${fmtPrice(t.entryPrice)} → ${fmtPrice(t.exitPrice)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Text("%+.2fR".format(t.rMultiple), color = mc.of(t.rMultiple), fontFamily = Mono)
                }
            }
        }
    }
}
