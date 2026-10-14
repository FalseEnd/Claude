@file:OptIn(ExperimentalMaterial3Api::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.perppilot.app.AppGraph
import com.perppilot.app.ui.components.CandleChart
import com.perppilot.app.ui.components.HLine
import com.perppilot.app.ui.components.Hint
import com.perppilot.app.ui.components.ScoreBar
import com.perppilot.app.ui.components.SectionCard
import com.perppilot.app.ui.components.StatGrid
import com.perppilot.app.ui.components.Tag
import com.perppilot.app.ui.components.fmtNum
import com.perppilot.app.ui.components.fmtPct
import com.perppilot.app.ui.components.fmtPrice
import com.perppilot.app.ui.components.fmtTime
import com.perppilot.app.ui.components.fmtUsd
import com.perppilot.app.ui.components.shortSym
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.Blue
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.core.analysis.AnalysisSnapshot
import com.perppilot.core.risk.RiskManager
import com.perppilot.core.strategy.TrendStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUi(val loading: Boolean = false, val snapshot: AnalysisSnapshot? = null, val error: String? = null, val equity: Double? = null)

class DetailViewModel(private val graph: AppGraph, private val instId: String) : ViewModel() {
    private val _ui = MutableStateFlow(DetailUi())
    val ui = _ui.asStateFlow()

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val s = graph.settings.current
            val result = runCatching { MarketViewModel.analyze(graph.publicClient(s), TrendStrategy(s.engine.strategy), instId) }
            val equity = graph.engineStore.get().lastEquity.takeIf { it > 0 }
            _ui.value = DetailUi(false, result.getOrNull(), result.exceptionOrNull()?.message, equity)
        }
    }
}

@Composable
fun DetailScreen(graph: AppGraph, instId: String, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = instId) { DetailViewModel(graph, instId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val mc = LocalMarketColors.current
    LaunchedEffect(instId) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${shortSym(instId)} 永續分析") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "重新整理") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val s = ui.snapshot ?: return@Column
            val t = s.ticker
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t?.let { fmtPrice(it.last) } ?: "-", style = MaterialTheme.typography.titleLarge, fontFamily = Mono)
                Spacer(Modifier.padding(4.dp))
                Text(fmtPct(t?.change24hPct), color = mc.of(t?.change24hPct ?: 0.0), fontFamily = Mono)
            }
            val plan = s.plan
            val lines = buildList {
                if (plan != null) {
                    add(HLine(plan.entry, Accent, "進場"))
                    add(HLine(plan.stop, mc.down, "止損"))
                    add(HLine(plan.target1, mc.up, "2R"))
                } else {
                    if (!s.breakoutHigh.isNaN()) add(HLine(s.breakoutHigh, Blue, "突破上緣"))
                    if (!s.breakoutLow.isNaN()) add(HLine(s.breakoutLow, Blue, "突破下緣"))
                }
            }
            SectionCard("${settings.engine.strategy.ltfFrame.label} K線（雙指縮放、拖曳）") {
                CandleChart(s.ltf, lines = lines)
                Hint("黃線 EMA20、藍線 EMA50；下方為 RSI(14)")
            }
            SectionCard("綜合評分") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.score}", style = MaterialTheme.typography.titleLarge, color = mc.of(s.score.toDouble()), fontFamily = Mono)
                    Spacer(Modifier.padding(6.dp))
                    Tag(s.bias, mc.of(s.score.toDouble()))
                    Spacer(Modifier.padding(4.dp))
                    Tag(s.regime.label, TextSecondary)
                }
                Spacer(Modifier.height(8.dp))
                ScoreBar(s.score, mc.up, mc.down)
                Spacer(Modifier.height(8.dp))
                s.factors.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(f.value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("%+.1f".format(f.score), color = mc.of(f.score), fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SectionCard("市場數據") {
                StatGrid(listOf(
                    Triple("資金費率", s.fundingRatePct?.let { "%.4f%%".format(it) } ?: "-", s.fundingRatePct?.let { mc.of(-it) }),
                    Triple("下次結算", s.nextFundingTime?.let { fmtTime(it) } ?: "-", null),
                    Triple("年化資金費", s.fundingRatePct?.let { "%.1f%%".format(it * 3 * 365) } ?: "-", null),
                    Triple("持倉量", fmtUsd(s.openInterestUsd), null),
                    Triple("多空人數比", fmtNum(s.longShortRatio), null),
                    Triple("24h 成交額", fmtUsd(t?.volQuote24h), null),
                    Triple("ADX(${settings.engine.strategy.htfFrame.label})", fmtNum(s.htfAdx, 1), null),
                    Triple("RSI(${settings.engine.strategy.ltfFrame.label})", fmtNum(s.rsi1h, 1), null),
                    Triple("ATR%", fmtNum(s.atrPct) + "%", null),
                    Triple("布林寬度", fmtNum(s.bbWidthPct) + "%", null),
                    Triple("24h 高", t?.let { fmtPrice(it.high24h) } ?: "-", null),
                    Triple("24h 低", t?.let { fmtPrice(it.low24h) } ?: "-", null),
                ))
            }
            SectionCard("策略判斷") {
                if (plan == null) {
                    Text("目前沒有符合條件的進場訊號。", fontWeight = FontWeight.SemiBold)
                    Hint(when (s.regime) {
                        com.perppilot.core.strategy.Regime.NEUTRAL -> "大週期無明確趨勢（ADX 不足或均線糾結），策略選擇觀望，這是避免震盪虧損的關鍵。"
                        else -> "大週期為${s.regime.label}，等待回踩 EMA20 後重新轉強，或放量突破 ${fmtPrice(s.breakoutHigh)} / 跌破 ${fmtPrice(s.breakoutLow)}。"
                    })
                } else {
                    val sig = s.signal!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag("${sig.side.label}訊號", Accent)
                        Spacer(Modifier.padding(4.dp))
                        Text(sig.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    StatGrid(listOf(
                        Triple("參考進場", fmtPrice(plan.entry), null),
                        Triple("止損", fmtPrice(plan.stop), mc.down),
                        Triple("止損距離", "%.2f%%".format(plan.riskPct), null),
                        Triple("目標 2R", fmtPrice(plan.target1), mc.up),
                        Triple("目標 3R", fmtPrice(plan.target2), mc.up),
                        Triple("出場方式", "移動止損", null),
                    ))
                    val equity = ui.equity ?: 1000.0
                    val size = RiskManager.size(equity, plan.entry, plan.stop, settings.engine.risk)
                    Hint(
                        if (size == null) "止損距離相對槓桿過寬，不建議進場。"
                        else "以權益 ${fmtUsd(equity)} USDT、單筆風險 ${settings.engine.risk.riskPerTradePct}% 計算：" +
                            "名目倉位 ${fmtUsd(size.notional)} USDT（約 ${fmtNum(size.quantity, 4)} ${shortSym(instId)}），" +
                            "保證金 ${fmtUsd(size.margin)}（${settings.engine.risk.leverage}x），觸及止損虧損約 ${fmtUsd(size.riskAmount)} USDT。",
                    )
                }
            }
            Hint("所有分析僅供參考，不構成投資建議。合約交易具高風險，可能損失全部保證金。")
        }
    }
}
