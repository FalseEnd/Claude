package com.perppilot.app.ui.screens

import com.perppilot.core.strategy.StrategyType

/** Backtest verdicts shown next to each strategy (see docs/STRATEGY.md for the full tables). */
object StrategyVerdicts {
    fun validated(t: StrategyType): Boolean = t == StrategyType.TREND

    fun badge(t: StrategyType): String = if (validated(t)) "回測正報酬" else "回測虧損・僅供研究"

    fun detail(t: StrategyType): String = when (t) {
        StrategyType.TREND ->
            "4H／日線，8 幣 4 年：+46%（年化約 10%）、最大回撤 20%、PF 1.27；前兩年僅 +6%，17 季中 8 季虧損。"
        StrategyType.ORB ->
            "5 幣回測（taker 手續費）：5 分鐘線一年 −32%、15 分鐘線兩年 −65%。只有在全部以掛單成交的理想假設下 30 分鐘區間版本為正，實務上突破進場與止損難以掛單成交。"
        StrategyType.MEAN_REVERSION ->
            "5 分、15 分、1 小時線全部大幅虧損（−80% 至 −100%），即使改用掛單手續費 PF 仍 < 1：加密貨幣短線偏向延續而非回歸。"
    }
}
