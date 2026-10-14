"""
Delta-neutral funding carry backtest: long spot + short USDT perpetual, collect funding.

Data: Binance USDⓈ-M monthly funding archives (data.binance.vision), used as a proxy for OKX
(OKX's API only serves ~3 months of funding history). Funding across major venues is highly
correlated but not identical.

Decisions at each settlement use only rates already settled (no look-ahead): a position held
through settlement t earns rate r_t; the signal is then updated with r_t and the book rebalanced.

usage: python3 research/funding_carry.py <funding_csv_dir>
"""
import csv, glob, os, sys, math
from collections import defaultdict
from datetime import datetime, timezone

H8 = 8 * 3600 * 1000

def load(d):
    rates = defaultdict(dict)  # sym -> {t8 -> rate per 8h}
    for f in glob.glob(os.path.join(d, "*.csv")):
        sym = os.path.basename(f).split("-")[0]
        with open(f) as fh:
            for row in csv.DictReader(fh):
                t = int(row["calc_time"]); h = float(row.get("funding_interval_hours") or 8)
                r = float(row["last_funding_rate"])
                b = t // H8 * H8  # bucket to 8h; 4h-interval coins sum into their 8h bucket
                rates[sym][b] = rates[sym].get(b, 0.0) + r
    return rates

def run(rates, slots=3, entry=0.0001, exit_=0.00003, lookback=9, start=None, end=None,
        spot_fee=0.0010, perp_fee=0.0005, slip=0.0003, cap_per_notional=1.5, universe=None):
    syms = sorted(universe or rates.keys())
    times = sorted({t for s in syms for t in rates[s]})
    if start: times = [t for t in times if t >= start]
    if end: times = [t for t in times if t < end]
    equity, held, hist = 1.0, {}, defaultdict(list)
    curve, trades, cost_paid, funding_got, invested_steps = [], 0, 0.0, 0.0, 0
    side_cost = spot_fee + perp_fee + 2 * slip
    for t in times:
        # 1) credit funding for positions held through this settlement
        for s, notional in held.items():
            r = rates[s].get(t)
            if r is not None:
                equity += notional * r; funding_got += notional * r
        # 2) update signals with the just-settled rate
        for s in syms:
            r = rates[s].get(t)
            if r is not None: hist[s].append(r)
        avg = {s: sum(hist[s][-lookback:]) / len(hist[s][-lookback:]) for s in syms if len(hist[s]) >= lookback}
        # 3) exits
        for s in list(held):
            if avg.get(s, -1) < exit_:
                c = held.pop(s) * side_cost; equity -= c; cost_paid += c
        # 4) entries: best trailing funding first
        cands = sorted((a, s) for s, a in avg.items() if a >= entry and s not in held)[::-1]
        for a, s in cands:
            if len(held) >= slots: break
            notional = equity / slots / cap_per_notional
            c = notional * side_cost; equity -= c; cost_paid += c
            held[s] = notional; trades += 1
        if held: invested_steps += 1
        curve.append((t, equity))
    # close everything at the end
    for s in list(held):
        c = held.pop(s) * side_cost; equity -= c; cost_paid += c
    if curve: curve[-1] = (curve[-1][0], equity)
    return curve, dict(trades=trades, cost=cost_paid, funding=funding_got, invested=invested_steps / max(1, len(times)))

def stats(curve):
    if len(curve) < 2: return {}
    years = (curve[-1][0] - curve[0][0]) / (365.25 * 86400e3)
    tot = curve[-1][1] / curve[0][1] - 1 if curve[0][1] else 0
    peak, mdd = -1e9, 0.0
    for _, e in curve:
        peak = max(peak, e); mdd = max(mdd, (peak - e) / peak)
    monthly = {}
    for t, e in curve:
        monthly[datetime.fromtimestamp(t / 1000, timezone.utc).strftime("%Y-%m")] = e
    vals = list(monthly.values()); keys = list(monthly.keys())
    mrets = [vals[i] / vals[i - 1] - 1 for i in range(1, len(vals))]
    yearly = {}
    for t, e in curve:
        yearly[datetime.fromtimestamp(t / 1000, timezone.utc).year] = e
    yv = sorted(yearly.items()); prev = curve[0][1]; yr = {}
    for y, e in yv: yr[y] = e / prev - 1; prev = e
    return dict(total=tot, cagr=(1 + tot) ** (1 / years) - 1 if years > 0 else 0, mdd=mdd,
                neg_months=sum(1 for r in mrets if r < 0), months=len(mrets),
                worst_month=min(mrets) if mrets else 0, yearly=yr)

def fmt(name, curve, info):
    s = stats(curve)
    yrs = " ".join(f"{y}:{r*100:+.1f}%" for y, r in s["yearly"].items())
    print(f"{name:<30} 總 {s['total']*100:+6.1f}%  年化 {s['cagr']*100:+5.1f}%  回撤 {s['mdd']*100:4.1f}%  "
          f"虧損月 {s['neg_months']}/{s['months']}  最差月 {s['worst_month']*100:+.2f}%  進出 {info['trades']:3d}  "
          f"在場 {info['invested']*100:3.0f}%  | {yrs}")

if __name__ == "__main__":
    rates = load(sys.argv[1] if len(sys.argv) > 1 else "cli/data-cache/funding")
    mid = int(datetime(2024, 5, 1, tzinfo=timezone.utc).timestamp() * 1000)
    print("資料：", ", ".join(sorted(rates)), "\n成本：現貨 0.10% + 合約 0.05% + 每腿滑價 0.03%（單邊 0.21%）；資本 1.5 倍名目\n")
    print("— 基準：只做 BTC，永遠持有 —")
    c, i = run(rates, slots=1, entry=-1, exit_=-1, universe=["BTCUSDT"]); fmt("BTC 永久套利", c, i)
    c, i = run(rates, slots=1, entry=-1, exit_=-1, universe=["ETHUSDT"]); fmt("ETH 永久套利", c, i)
    print("\n— 輪動：持有資金費最高的幣（20 幣）—")
    for slots in (3, 5):
        for entry, exit_ in ((0.0001, 0.00003), (0.0002, 0.00005), (0.0003, 0.0001)):
            for lb in (9, 21):
                c, i = run(rates, slots=slots, entry=entry, exit_=exit_, lookback=lb)
                fmt(f"{slots}槽 進{entry*1e4:.0f}bp 出{exit_*1e4:.1f}bp 回看{lb//3}天", c, i)
    print("\n— 前後半段（2022-01→2024-04 / 2024-05→2026-09），3 槽 進1bp 出0.3bp 回看3天 —")
    c, i = run(rates, 3, 0.0001, 0.00003, 9, end=mid); fmt("前半", c, i)
    c, i = run(rates, 3, 0.0001, 0.00003, 9, start=mid); fmt("後半", c, i)
    print("\n— 只用前 13 個主流幣（不含 2023 後上市的 APT ARB OP 等）—")
    majors = ["BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","BNBUSDT","LINKUSDT","AVAXUSDT","ADAUSDT","LTCUSDT","DOTUSDT","TRXUSDT","BCHUSDT"]
    c, i = run(rates, 3, 0.0001, 0.00003, 9, universe=majors); fmt("主流 3槽 進1bp", c, i)
    print("\n— 成本敏感度（3 槽 進1bp 出0.3bp 回看3天）—")
    c, i = run(rates, 3, 0.0001, 0.00003, 9, spot_fee=0.0008, perp_fee=0.0002, slip=0.0001); fmt("掛單成交（maker）", c, i)
    c, i = run(rates, 3, 0.0001, 0.00003, 9, slip=0.001); fmt("滑價/基差 0.1%/腿", c, i)

    print("\n— App 預設候選：BTC＋ETH 兩槽 —")
    be = ["BTCUSDT", "ETHUSDT"]
    c, i = run(rates, 2, -1, -1, 21, universe=be); fmt("永遠持有", c, i)
    c, i = run(rates, 2, 0.0, -0.00002, 21, universe=be); fmt("7日均>0 進, < -0.2bp 出", c, i)
    c, i = run(rates, 2, 0.00003, 0.0, 21, universe=be); fmt("7日均>0.3bp 進, <0 出", c, i)
    c, i = run(rates, 2, 0.0, -0.00002, 21, universe=be, cap_per_notional=1.0); fmt("同上，多幣種保證金(資本1.0x)", c, i)
    c, i = run(rates, 2, 0.0, -0.00002, 21, universe=be, end=mid); fmt("  前半", c, i)
    c, i = run(rates, 2, 0.0, -0.00002, 21, universe=be, start=mid); fmt("  後半", c, i)
