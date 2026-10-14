@file:OptIn(ExperimentalMaterial3Api::class)

package com.perppilot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.perppilot.app.AppGraph
import com.perppilot.app.ui.components.ScoreBar
import com.perppilot.app.ui.components.Tag
import com.perppilot.app.ui.components.fmtPct
import com.perppilot.app.ui.components.fmtUsd
import com.perppilot.app.ui.components.fmtPrice
import com.perppilot.app.ui.components.shortSym
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.core.analysis.AnalysisSnapshot
import com.perppilot.core.analysis.MarketAnalyzer
import com.perppilot.core.model.Ticker
import com.perppilot.core.strategy.Regime
import com.perppilot.core.strategy.TrendStrategy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MarketUi(
    val loading: Boolean = false,
    val snapshots: Map<String, AnalysisSnapshot> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val allTickers: List<Ticker> = emptyList(),
    val updated: Long = 0,
)

class MarketViewModel(private val graph: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(MarketUi())
    val ui = _ui.asStateFlow()

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            val s = graph.settings.current
            val client = graph.publicClient(s)
            val strategy = TrendStrategy(s.engine.strategy)
            val gate = Semaphore(3)
            val results = s.engine.watchlist.map { inst ->
                async {
                    gate.withPermit {
                        inst to runCatching { analyze(client, strategy, inst) }
                    }
                }
            }.awaitAll()
            val tickers = runCatching { client.tickers().sortedByDescending { it.volQuote24h } }.getOrDefault(_ui.value.allTickers)
            _ui.value = MarketUi(
                loading = false,
                snapshots = results.mapNotNull { (k, r) -> r.getOrNull()?.let { k to it } }.toMap(),
                errors = results.mapNotNull { (k, r) -> r.exceptionOrNull()?.let { k to (it.message ?: "錯誤") } }.toMap(),
                allTickers = tickers,
                updated = System.currentTimeMillis(),
            )
        }
    }

    fun addSymbol(raw: String, onResult: (String?) -> Unit) {
        val inst = normalize(raw)
        viewModelScope.launch {
            val ok = runCatching { graph.publicClient().instrument(inst) }.isSuccess
            if (!ok) return@launch onResult("找不到合約 $inst")
            graph.settings.update { it.copy(engine = it.engine.copy(watchlist = (it.engine.watchlist + inst).distinct())) }
            onResult(null)
            refresh()
        }
    }

    fun removeSymbol(inst: String) {
        graph.settings.update { it.copy(engine = it.engine.copy(watchlist = it.engine.watchlist - inst)) }
        _ui.value = _ui.value.copy(snapshots = _ui.value.snapshots - inst)
    }

    companion object {
        fun normalize(raw: String): String {
            val s = raw.trim().uppercase().removePrefix("OKX:").removeSuffix(".P")
            return when {
                s.endsWith("-USDT-SWAP") -> s
                s.contains("-") -> s.substringBefore("-") + "-USDT-SWAP"
                s.endsWith("USDT") -> s.removeSuffix("USDT") + "-USDT-SWAP"
                else -> "$s-USDT-SWAP"
            }
        }

        suspend fun analyze(client: com.perppilot.core.exchange.okx.OkxClient, strategy: TrendStrategy, inst: String): AnalysisSnapshot {
            val cfg = strategy.cfg
            val ltf = client.candles(inst, cfg.ltfFrame, 300)
            val htf = client.candles(inst, cfg.htfFrame, 300)
            val ticker = runCatching { client.ticker(inst) }.getOrNull()
            val funding = runCatching { client.funding(inst) }.getOrNull()
            val oi = runCatching { client.openInterestUsd(inst) }.getOrNull()
            val lsr = runCatching { client.longShortRatio(inst) }.getOrNull()
            return MarketAnalyzer.analyze(inst, ltf, htf, strategy, ticker, funding, oi, lsr)
        }
    }
}

@Composable
fun MarketScreen(graph: AppGraph, onOpen: (String) -> Unit) {
    val vm: MarketViewModel = viewModel { MarketViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (ui.updated == 0L) vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("永續合約行情") },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "新增合約") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "重新整理") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("觀察清單") })
                Tab(tab == 1, { tab = 1 }, text = { Text("全市場") })
            }
            if (tab == 0) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(settings.engine.watchlist, key = { it }) { inst ->
                        WatchRow(inst, ui.snapshots[inst], ui.errors[inst], onClick = { onOpen(inst) }, onRemove = { vm.removeSymbol(inst) })
                    }
                    item {
                        Text(
                            "評分綜合 ${settings.engine.strategy.htfFrame.label} 趨勢、${settings.engine.strategy.ltfFrame.label} 動能、資金費率與多空比；僅供參考，非投資建議。",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    items(ui.allTickers.take(80), key = { it.instId }) { t -> TickerRow(t) { onOpen(t.instId) } }
                }
            }
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("新增合約") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text("例如 BTC、ETHUSDT、SOL-USDT-SWAP") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addSymbol(text) { e -> if (e == null) adding = false else err = e } }) { Text("新增") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun WatchRow(inst: String, s: AnalysisSnapshot?, error: String?, onClick: () -> Unit, onRemove: () -> Unit) {
    val mc = LocalMarketColors.current
    var confirm by remember { mutableStateOf(false) }
    com.perppilot.app.ui.components.SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(shortSym(inst), style = MaterialTheme.typography.titleMedium)
                Text("永續", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            val t = s?.ticker
            Column(horizontalAlignment = Alignment.End) {
                Text(t?.let { fmtPrice(it.last) } ?: "-", fontFamily = Mono, fontWeight = FontWeight.SemiBold)
                Text(fmtPct(t?.change24hPct), color = mc.of(t?.change24hPct ?: 0.0), fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { confirm = true }) { Text("移除", color = TextSecondary) }
        }
        if (s != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(s.regime.label, when (s.regime) { Regime.BULL -> mc.up; Regime.BEAR -> mc.down; else -> TextSecondary })
                Tag("${s.bias} ${s.score}", mc.of(s.score.toDouble()))
                s.signal?.let { Tag("訊號：${it.side.label}", Accent) }
            }
            Spacer(Modifier.height(6.dp))
            ScoreBar(s.score, mc.up, mc.down)
            Spacer(Modifier.height(6.dp))
            Row {
                Text("資金費率 ${s.fundingRatePct?.let { "%.4f%%".format(it) } ?: "-"}", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                Text("持倉量 ${fmtUsd(s.openInterestUsd)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.width(8.dp))
                Text("ADX ${"%.0f".format(s.htfAdx)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("移除 ${shortSym(inst)}？") },
            text = { Text("機器人將不再分析或交易此合約（已持有的倉位仍會繼續管理止損）。") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TickerRow(t: Ticker, onClick: () -> Unit) {
    val mc = LocalMarketColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(shortSym(t.instId), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(fmtPrice(t.last), Modifier.weight(1f), fontFamily = Mono)
        Text(fmtPct(t.change24hPct), Modifier.weight(0.8f), color = mc.of(t.change24hPct), fontFamily = Mono)
        Text(fmtUsd(t.volQuote24h), Modifier.weight(0.8f), color = TextSecondary, fontFamily = Mono)
    }
}
