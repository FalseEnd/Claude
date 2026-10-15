/*
 * 小倆口 core logic
 *
 * 與 UI 無關的純函式：日期、紀念日、經期預測、地理圍欄、猜花費計分、
 * 自動記帳的通知解析與分類。瀏覽器中掛在 window.CoupleCore，
 * Node 中以 require('./core.js') 載入（見 core.test.js）。
 *
 * 日期一律使用本地日期字串 'YYYY-MM-DD'，內部轉成「UTC 日序號」計算，
 * 避免日光節約時間與時區造成的差一天問題。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.CoupleCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var DAY_MS = 86400000;

  // ---------------------------------------------------------------- dates

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function toDayNum(s) {
    var p = String(s).split('-').map(Number);
    if (p.length !== 3 || p.some(isNaN)) throw new Error('Invalid date: ' + s);
    return Math.round(Date.UTC(p[0], p[1] - 1, p[2]) / DAY_MS);
  }

  function fromDayNum(n) {
    var d = new Date(n * DAY_MS);
    return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
  }

  /** 本地時區的今天 'YYYY-MM-DD' */
  function localDate(date) {
    var d = date || new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function addDays(s, n) { return fromDayNum(toDayNum(s) + n); }

  /** b - a，以天為單位 */
  function diffDays(a, b) { return toDayNum(b) - toDayNum(a); }

  function isLeapYear(y) { return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0; }

  /** 在一起的第幾天（開始那天算第 1 天） */
  function daysTogether(since, today) { return diffDays(since, today) + 1; }

  /**
   * 紀念日下一次發生的日期。
   * repeat: 'yearly' | 'none'
   * 2/29 的紀念日在平年以 2/28 慶祝。
   * 回傳 { date, daysLeft, years }，years 為第幾週年（none 時為 0）。
   */
  function nextOccurrence(dateStr, today, repeat) {
    if (repeat !== 'yearly') {
      return { date: dateStr, daysLeft: diffDays(today, dateStr), years: 0 };
    }
    var p = dateStr.split('-').map(Number);
    var origYear = p[0], month = p[1], day = p[2];
    var year = Number(today.slice(0, 4));
    if (year < origYear) year = origYear;
    for (var i = 0; i < 3; i++, year++) {
      var d = (month === 2 && day === 29 && !isLeapYear(year)) ? 28 : day;
      var candidate = year + '-' + pad(month) + '-' + pad(d);
      if (diffDays(today, candidate) >= 0) {
        return { date: candidate, daysLeft: diffDays(today, candidate), years: year - origYear };
      }
    }
    throw new Error('unreachable');
  }

  /**
   * 交往天數的里程碑（每 100 天，以及 520、1314 這類有意義的數字）。
   * 回傳之後的 count 個：[{ day, date, daysLeft }]
   */
  var SPECIAL_DAYS = [520, 1314];
  function upcomingMilestones(since, today, count) {
    var current = daysTogether(since, today);
    var out = [];
    var hundred = Math.max(100, Math.ceil(current / 100) * 100);
    var specials = SPECIAL_DAYS.filter(function (d) { return d >= current; });
    while (out.length < (count || 3)) {
      var next = hundred;
      if (specials.length && specials[0] <= hundred) {
        next = specials.shift();
        if (next === hundred) hundred += 100;
      } else {
        hundred += 100;
      }
      var date = addDays(since, next - 1);
      out.push({ day: next, date: date, daysLeft: diffDays(today, date) });
    }
    return out;
  }

  // ---------------------------------------------------------------- cycle

  var CYCLE_DEFAULTS = { cycleLength: 28, periodLength: 5, lutealLength: 14 };
  var CYCLE_WINDOW = 6;            // 用最近 6 個週期計算平均
  var CYCLE_VALID = [15, 60];      // 超出範圍視為漏記，不納入平均
  var PERIOD_VALID = [1, 10];
  var PROJECT_CYCLES = 6;          // 日曆最多往後推算 6 個週期

  function sortPeriods(periods) {
    return (periods || []).slice().sort(function (a, b) { return toDayNum(a.start) - toDayNum(b.start); });
  }

  function mean(list) { return list.reduce(function (s, v) { return s + v; }, 0) / list.length; }

  /**
   * 由歷史紀錄算出平均週期與經期天數。
   * settings.cycleLength / periodLength 若有值，代表使用者手動指定，優先使用。
   */
  function cycleStats(periods, settings) {
    settings = settings || {};
    var sorted = sortPeriods(periods);
    var cycles = [];
    for (var i = 1; i < sorted.length; i++) {
      var len = diffDays(sorted[i - 1].start, sorted[i].start);
      if (len >= CYCLE_VALID[0] && len <= CYCLE_VALID[1]) cycles.push(len);
    }
    cycles = cycles.slice(-CYCLE_WINDOW);
    var lengths = sorted
      .filter(function (p) { return p.end; })
      .map(function (p) { return diffDays(p.start, p.end) + 1; })
      .filter(function (n) { return n >= PERIOD_VALID[0] && n <= PERIOD_VALID[1]; })
      .slice(-CYCLE_WINDOW);
    var cycleLength = settings.cycleLength || (cycles.length ? Math.round(mean(cycles)) : CYCLE_DEFAULTS.cycleLength);
    var periodLength = settings.periodLength || (lengths.length ? Math.round(mean(lengths)) : CYCLE_DEFAULTS.periodLength);
    return {
      cycleLength: cycleLength,
      periodLength: periodLength,
      lutealLength: settings.lutealLength || CYCLE_DEFAULTS.lutealLength,
      samples: cycles.length,
      // 週期最長與最短相差超過 7 天時，提醒使用者預測準確度較低
      irregular: cycles.length >= 3 && (Math.max.apply(null, cycles) - Math.min.apply(null, cycles)) > 7
    };
  }

  /**
   * 每個週期的起始日：已記錄的開始日 + 之後推算的開始日。
   * 回傳 [{ start, end, predicted }]，依日期排序。
   */
  function cycleStarts(periods, stats, today) {
    var sorted = sortPeriods(periods);
    if (!sorted.length) return [];
    var out = sorted.map(function (p) {
      return { start: p.start, end: p.end || addDays(p.start, stats.periodLength - 1), predicted: false };
    });
    var last = sorted[sorted.length - 1].start;
    var next = addDays(last, stats.cycleLength);
    // 超過一整個週期沒有記錄，視為忘了記：把預測推到今天之後
    while (today && diffDays(next, today) >= stats.cycleLength) next = addDays(next, stats.cycleLength);
    for (var k = 0; k < PROJECT_CYCLES; k++) {
      out.push({ start: next, end: addDays(next, stats.periodLength - 1), predicted: true });
      next = addDays(next, stats.cycleLength);
    }
    return out;
  }

  /**
   * 某一天屬於週期中的哪個階段。
   * phase: 'period' 月經期 | 'ovulation' 排卵日 | 'fertile' 易孕期 | 'safe' 相對安全期 | null（無資料）
   * sub:   safe 時區分 'follicular' 濾泡期 / 'luteal' 黃體期
   */
  function dayPhase(dateStr, periods, settings, today) {
    var stats = cycleStats(periods, settings);
    var starts = cycleStarts(periods, stats, today);
    if (!starts.length) return { phase: null };
    var d = toDayNum(dateStr);
    if (d < toDayNum(starts[0].start)) return { phase: null };
    for (var i = 0; i < starts.length; i++) {
      var s = toDayNum(starts[i].start);
      var nextStart = i + 1 < starts.length ? toDayNum(starts[i + 1].start) : null;
      if (nextStart !== null && d >= nextStart) continue;
      if (nextStart === null) return { phase: null };
      var predicted = starts[i].predicted;
      if (d <= toDayNum(starts[i].end)) return { phase: 'period', predicted: predicted, cycleDay: d - s + 1 };
      var ovulation = nextStart - stats.lutealLength;
      var info = { predicted: predicted || starts[i + 1].predicted, cycleDay: d - s + 1 };
      if (d === ovulation) info.phase = 'ovulation';
      else if (d >= ovulation - 5 && d <= ovulation + 1) info.phase = 'fertile';
      else { info.phase = 'safe'; info.sub = d < ovulation ? 'follicular' : 'luteal'; }
      return info;
    }
    return { phase: null };
  }

  /**
   * 今天的週期摘要：第幾天、下次經期、排卵日、易孕期、是否延遲。
   */
  function predictCycle(periods, settings, today) {
    var sorted = sortPeriods(periods);
    if (!sorted.length) return null;
    var stats = cycleStats(sorted, settings);
    var starts = cycleStarts(sorted, stats, today);
    var t = toDayNum(today);
    var current = null, next = null;
    for (var i = 0; i < starts.length; i++) {
      if (toDayNum(starts[i].start) <= t) current = starts[i];
      else { next = starts[i]; break; }
    }
    var lastRecorded = sorted[sorted.length - 1];
    var expected = addDays(lastRecorded.start, stats.cycleLength);
    var lateDays = diffDays(expected, today);
    // 預測日已過、但還在一個週期內：視為延遲，而不是直接跳到下一個週期
    if (lateDays > 0 && lateDays < stats.cycleLength) {
      current = { start: lastRecorded.start };
      next = { start: expected };
    } else {
      lateDays = 0;
    }
    var ovulation = addDays(next.start, -stats.lutealLength);
    return {
      stats: stats,
      cycleStart: current ? current.start : null,
      cycleDay: current ? t - toDayNum(current.start) + 1 : null,
      nextStart: next.start,
      daysUntilNext: diffDays(today, next.start),
      ovulation: ovulation,
      fertileStart: addDays(ovulation, -5),
      fertileEnd: addDays(ovulation, 1),
      lateDays: lateDays,
      today: lateDays ? { phase: 'late' } : dayPhase(today, sorted, settings, today)
    };
  }

  // ---------------------------------------------------------------- geo

  /** 平面距離（公尺），原型的虛擬地圖使用 */
  function distance(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

  /** 經緯度距離（公尺），正式版使用 */
  function haversine(a, b) {
    var R = 6371000, rad = Math.PI / 180;
    var dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * R * Math.asin(Math.sqrt(h));
  }

  /**
   * 地理圍欄狀態機。
   * 進入：距離 <= 半徑；離開：距離 > 半徑 + 緩衝（半徑 20%，至少 25 公尺），
   * 避免 GPS 飄移在邊界上反覆觸發。
   * prevInside: 先前在範圍內的 place id 陣列
   * 回傳 { inside: [...ids], events: [{ placeId, type: 'arrive' | 'leave' }] }
   */
  function geofenceStep(places, prevInside, pos, distFn) {
    distFn = distFn || distance;
    var prev = new Set(prevInside || []);
    var inside = [], events = [];
    places.forEach(function (p) {
      var d = distFn(p, pos);
      var buffer = Math.max(25, p.radius * 0.2);
      var was = prev.has(p.id);
      var now = was ? d <= p.radius + buffer : d <= p.radius;
      if (now) inside.push(p.id);
      if (now && !was) events.push({ placeId: p.id, type: 'arrive' });
      if (!now && was) events.push({ placeId: p.id, type: 'leave' });
    });
    return { inside: inside, events: events };
  }

  // ---------------------------------------------------------------- battery

  function batteryState(level, charging) {
    if (charging) return 'charging';
    if (level <= 10) return 'critical';
    if (level <= 20) return 'low';
    return 'ok';
  }

  // ---------------------------------------------------------------- guess game

  var GUESS_TIERS = [
    { within: 0.03, points: 100, label: '神準！心有靈犀' },
    { within: 0.10, points: 80, label: '超懂你' },
    { within: 0.25, points: 50, label: '很接近了' },
    { within: 0.50, points: 20, label: '差了一點' },
    { within: Infinity, points: 5, label: '要多了解對方喔' }
  ];

  /** 依誤差比例給分。回傳 { diff, ratio, points, label, direction } */
  function scoreGuess(guess, actual) {
    if (!(actual > 0)) throw new Error('actual must be > 0');
    if (!(guess >= 0)) throw new Error('guess must be >= 0');
    var diff = guess - actual;
    var ratio = Math.abs(diff) / actual;
    var tier = GUESS_TIERS.find(function (t) { return ratio <= t.within; });
    return {
      diff: diff,
      ratio: ratio,
      points: tier.points,
      label: tier.label,
      direction: diff > 0 ? 'high' : diff < 0 ? 'low' : 'exact'
    };
  }

  // ---------------------------------------------------------------- expenses

  var CATEGORIES = [
    { key: 'drink', label: '咖啡飲料', words: ['星巴克', 'starbucks', '路易莎', 'louisa', 'cama', '50嵐', '清心', '可不可', '迷客夏', '咖啡', 'coffee', '茶'] },
    { key: 'food', label: '餐飲', words: ['麥當勞', '摩斯', '肯德基', '鼎泰豐', '餐廳', '便當', '拉麵', '火鍋', '小吃', '食堂', 'uber eats', 'foodpanda'] },
    { key: 'transport', label: '交通', words: ['高鐵', '台鐵', '捷運', '悠遊卡', 'uber', '計程車', '中油', '加油', '停車', 'youbike'] },
    { key: 'daily', label: '日用', words: ['7-eleven', '統一超商', '全家', '萊爾富', '全聯', '家樂福', '屈臣氏', '康是美', '寶雅'] },
    { key: 'shopping', label: '購物', words: ['uniqlo', 'zara', 'momo', '蝦皮', 'pchome', '誠品', 'nike', '百貨'] },
    { key: 'fun', label: '娛樂', words: ['威秀', '電影', 'netflix', 'spotify', 'steam', 'ktv', '好樂迪', '錢櫃'] },
    { key: 'gift', label: '禮物', words: [] },
    { key: 'other', label: '其他', words: [] }
  ];

  function categoryLabel(key) {
    var c = CATEGORIES.find(function (c) { return c.key === key; });
    return c ? c.label : '其他';
  }

  /** 依商家名稱關鍵字分類（正式版另外會學習使用者手動修正的結果） */
  function categorize(merchant) {
    var m = String(merchant || '').toLowerCase();
    for (var i = 0; i < CATEGORIES.length; i++) {
      if (CATEGORIES[i].words.some(function (w) { return m.indexOf(w) !== -1; })) return CATEGORIES[i].key;
    }
    return 'other';
  }

  var SOURCES = [
    { key: 'linepay', label: 'LINE Pay', re: /line\s*pay/i },
    { key: 'jkopay', label: '街口支付', re: /街口/ },
    { key: 'applepay', label: 'Apple Pay', re: /apple\s*pay/i },
    { key: 'card', label: '信用卡', re: /信用卡|卡片|刷卡|末四碼/ },
    { key: 'easywallet', label: '悠遊付', re: /悠遊付/ }
  ];

  var MERCHANT_PATTERNS = [
    /商店[:：]\s*([^\n，,。]+)/,
    /給\s*([^\n，,。]+)/,
    /於\s*([^\s\d][^\n，,。]*?)\s*消費/,
    /交易[:：]\s*([^\n，,。$]+?)\s*(?:NT\$|NTD|新台幣|\$|$)/i
  ];

  /**
   * 解析付款通知文字（Android 通知監聽 / iOS 捷徑轉傳的內容）。
   * 解析不到金額時回傳 null。
   */
  function parseNotification(text) {
    var t = String(text || '');
    var amountMatch = t.match(/(?:NT\$|NTD|新台幣|\$)\s*([\d,]+(?:\.\d+)?)/i) || t.match(/([\d,]+(?:\.\d+)?)\s*元/);
    if (!amountMatch) return null;
    var amount = Number(amountMatch[1].replace(/,/g, ''));
    if (!(amount > 0)) return null;
    var merchant = '';
    for (var i = 0; i < MERCHANT_PATTERNS.length && !merchant; i++) {
      var m = t.match(MERCHANT_PATTERNS[i]);
      if (m) merchant = m[1].trim();
    }
    var source = SOURCES.find(function (s) { return s.re.test(t); });
    merchant = merchant || '未知商家';
    return {
      amount: amount,
      merchant: merchant,
      source: source ? source.key : 'other',
      sourceLabel: source ? source.label : '通知',
      category: categorize(merchant)
    };
  }

  /** 指定月份（'YYYY-MM'）的支出統計，依類別金額排序 */
  function summarizeExpenses(expenses, month) {
    var list = expenses.filter(function (e) { return !month || localDate(new Date(e.ts)).slice(0, 7) === month; });
    var by = {};
    list.forEach(function (e) { by[e.category] = (by[e.category] || 0) + e.amount; });
    var byCategory = Object.keys(by)
      .map(function (k) { return { key: k, label: categoryLabel(k), amount: by[k] }; })
      .sort(function (a, b) { return b.amount - a.amount; });
    return {
      total: list.reduce(function (s, e) { return s + e.amount; }, 0),
      count: list.length,
      byCategory: byCategory
    };
  }

  // ---------------------------------------------------------------- mood & comfort

  var MOODS = [
    { key: 'happy', label: '開心', emoji: '😊', valence: 2 },
    { key: 'sweet', label: '甜蜜', emoji: '🥰', valence: 2 },
    { key: 'calm', label: '平靜', emoji: '🙂', valence: 1 },
    { key: 'missyou', label: '想你', emoji: '🥺', valence: 0 },
    { key: 'tired', label: '好累', emoji: '😩', valence: -1 },
    { key: 'anxious', label: '焦慮', emoji: '😰', valence: -1 },
    { key: 'sad', label: '難過', emoji: '😢', valence: -2 },
    { key: 'angry', label: '生氣', emoji: '😠', valence: -2 },
    { key: 'sick', label: '不舒服', emoji: '🤒', valence: -2 }
  ];

  function mood(key) { return MOODS.find(function (m) { return m.key === key; }) || null; }
  function needsComfort(key) { var m = mood(key); return !!m && m.valence < 0; }

  var COMFORTS = [
    { key: 'hug', label: '抱抱', emoji: '🤗', push: '給了你一個大大的抱抱' },
    { key: 'pat', label: '摸摸頭', emoji: '💆', push: '摸摸你的頭，說辛苦了' },
    { key: 'kiss', label: '親親', emoji: '😘', push: '親了你一下' },
    { key: 'here', label: '我在', emoji: '💬', push: '說：我在，想聊隨時找我' },
    { key: 'cheer', label: '加油', emoji: '💪', push: '幫你加油打氣' },
    { key: 'treat', label: '請你喝', emoji: '🥤', push: '說等等請你喝一杯' }
  ];

  function comfort(key) { return COMFORTS.find(function (c) { return c.key === key; }) || null; }

  // ---------------------------------------------------------------- formatting

  function money(n) {
    return 'NT$' + Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  /** 相對時間：剛剛 / 3 分鐘前 / 2 小時前 / 昨天 21:30 / 10/12 09:05 */
  function relTime(ts, now) {
    now = now || Date.now();
    var sec = Math.max(0, Math.round((now - ts) / 1000));
    var d = new Date(ts);
    var hm = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (sec < 60) return '剛剛';
    if (sec < 3600) return Math.floor(sec / 60) + ' 分鐘前';
    var days = diffDays(localDate(d), localDate(new Date(now)));
    if (days === 0) return Math.floor(sec / 3600) + ' 小時前';
    if (days === 1) return '昨天 ' + hm;
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hm;
  }

  return {
    // dates
    pad: pad, toDayNum: toDayNum, fromDayNum: fromDayNum, localDate: localDate, addDays: addDays,
    diffDays: diffDays, isLeapYear: isLeapYear, daysTogether: daysTogether,
    nextOccurrence: nextOccurrence, upcomingMilestones: upcomingMilestones,
    // cycle
    CYCLE_DEFAULTS: CYCLE_DEFAULTS, cycleStats: cycleStats, dayPhase: dayPhase, predictCycle: predictCycle,
    // geo
    distance: distance, haversine: haversine, geofenceStep: geofenceStep,
    // device
    batteryState: batteryState,
    // guess
    GUESS_TIERS: GUESS_TIERS, scoreGuess: scoreGuess,
    // expenses
    CATEGORIES: CATEGORIES, categoryLabel: categoryLabel, categorize: categorize,
    parseNotification: parseNotification, summarizeExpenses: summarizeExpenses,
    // mood & comfort
    MOODS: MOODS, mood: mood, needsComfort: needsComfort, COMFORTS: COMFORTS, comfort: comfort,
    // format
    money: money, relTime: relTime
  };
});
