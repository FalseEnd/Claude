package com.perppilot.core.engine

import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.risk.RiskState
import com.perppilot.core.strategy.EntrySignal
import com.perppilot.core.strategy.PositionTrack
import com.perppilot.core.strategy.StrategyConfig
import kotlinx.serialization.Serializable

@Serializable
data class JournalEntry(
    val instId: String,
    val side: Side,
    val entryType: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val contracts: Double,
    val realizedPnl: Double,
    val rMultiple: Double,
    val exitReason: String,
)

@Serializable
enum class EventKind { SIGNAL, ORDER, CLOSE, STOP, RISK, ERROR, INFO }

@Serializable
data class EngineEvent(
    val kind: EventKind,
    val title: String,
    val message: String,
    val instId: String? = null,
    val time: Long = 0,
)

/** Everything the engine needs to remember between runs. */
@Serializable
data class EngineState(
    val positions: Map<String, PositionTrack> = emptyMap(),
    /** Bar time of the last signal already acted upon, per instrument (de-duplication). */
    val lastSignalBar: Map<String, Long> = emptyMap(),
    val risk: RiskState = RiskState(),
    val lastRun: Long = 0,
    val lastEquity: Double = 0.0,
    val lastAvailable: Double = 0.0,
    val journal: List<JournalEntry> = emptyList(),
    val events: List<EngineEvent> = emptyList(),
    /** Last signal per instrument for display. */
    val latestSignals: Map<String, EntrySignal> = emptyMap(),
    /** Ids of external (TradingView) signals already handled. */
    val processedSignals: List<String> = emptyList(),
    /** Last relay message id consumed. */
    val relayCursor: String? = null,
) {
    fun withEvent(e: EngineEvent): EngineState = copy(events = (listOf(e) + events).take(MAX_EVENTS))
    fun withJournal(j: JournalEntry): EngineState = copy(journal = (listOf(j) + journal).take(MAX_JOURNAL))

    companion object {
        const val MAX_EVENTS = 200
        const val MAX_JOURNAL = 500
    }
}

@Serializable
data class EngineSettings(
    val watchlist: List<String> = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP"),
    val strategy: StrategyConfig = StrategyConfig(),
    val risk: RiskConfig = RiskConfig(),
    val autoTrade: Boolean = false,
    val notifySignals: Boolean = true,
    /** Skip a signal if price has already moved more than this many ATR away from the signal close. */
    val maxChaseAtr: Double = 0.5,
    /** Open trades from the built-in strategy (turn off to trade only TradingView alerts). */
    val useBuiltInStrategy: Boolean = true,
) {
    val ltf: Timeframe get() = strategy.ltfFrame
    val htf: Timeframe get() = strategy.htfFrame
}

interface EngineStore {
    fun load(): EngineState
    fun save(state: EngineState)
}

fun interface Notifier {
    fun notify(event: EngineEvent)
}

class InMemoryEngineStore(private var state: EngineState = EngineState()) : EngineStore {
    override fun load(): EngineState = state
    override fun save(state: EngineState) { this.state = state }
}

data class CycleReport(
    val time: Long,
    val equity: Double?,
    val events: List<EngineEvent>,
    val signals: Map<String, EntrySignal?>,
    val errors: List<String>,
)
