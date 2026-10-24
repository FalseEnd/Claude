package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.core.WeatherNow
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.WeatherException
import tw.xiaoliangkou.app.data.WeatherSource
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.service.Locator
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.StatTile
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** 要看天氣的地方：[at] 是位置的時間（對方的位置可能是一陣子以前的） */
internal data class WeatherSpot(val lat: Double, val lng: Double, val at: Instant? = null)

/** 對方那邊：沒分享位置、暫停分享、還沒有位置時是說明的文字 */
internal fun partnerSpot(s: AppState): Pair<WeatherSpot?, String> {
    val ps = s.partnerSharing
    val loc = s.partnerStatus.location
    return when {
        ps?.paused == true -> null to "${s.partnerName} 暫停分享中，看不到那邊的天氣 🙈"
        ps?.shareLocation != true -> null to "${s.partnerName} 沒有分享位置，看不到那邊的天氣"
        loc == null -> null to "還沒收到 ${s.partnerName} 的位置 📡"
        else -> WeatherSpot(loc.lat, loc.lng, (loc.recordedAt ?: loc.updatedAt)?.toInstantOrNull()) to ""
    }
}

/** 抓天氣的狀態 */
internal sealed interface WeatherLoad {
    data object Loading : WeatherLoad
    data class Ready(val w: WeatherNow, val area: String?) : WeatherLoad
    data class Failed(val message: String) : WeatherLoad
}

/** 抓 [spot] 的天氣，每 20 分鐘自己更新；[reload] 改變時重抓 */
@Composable
internal fun rememberWeather(source: WeatherSource, spot: WeatherSpot?, reload: Int = 0): WeatherLoad? {
    // 位置動一點點不重抓（Open-Meteo 也只看到小數點兩位）
    val key = spot?.let { String.format(Locale.US, "%.2f,%.2f", it.lat, it.lng) }
    var load by remember(key) { mutableStateOf<WeatherLoad?>(if (spot == null) null else WeatherLoad.Loading) }
    LaunchedEffect(key, reload) {
        if (spot == null) return@LaunchedEffect
        while (true) {
            load = try {
                val w = source.now(spot.lat, spot.lng)
                WeatherLoad.Ready(w, runCatching { source.area(spot.lat, spot.lng) }.getOrNull())
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherException) {
                WeatherLoad.Failed(e.message ?: "抓不到天氣")
            } catch (e: Exception) {
                WeatherLoad.Failed("抓不到天氣")
            }
            delay(REFRESH_MS)
        }
    }
    return load
}

private const val REFRESH_MS = 20 * 60 * 1000L

/** 自己的位置還不知道時，有權限就悄悄找一次（不跳出要權限的畫面） */
@Composable
internal fun LocateMeQuietly(s: AppState, vm: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(s.myLocation == null) {
        if (s.myLocation != null || vm.demo != null || Locator.status(context) != Locator.Status.OK) return@LaunchedEffect
        Locator.current(context)?.let { vm.repo.value?.setMyLocation(it.latitude, it.longitude) }
    }
}

/** 自己那邊還沒有位置時的說明 */
@Composable
private fun myMissingText(s: AppState): String {
    val context = LocalContext.current
    return when (if (s.demo) Locator.Status.OK else Locator.status(context)) {
        Locator.Status.NO_PERMISSION -> "打開定位權限，就看得到你這邊的天氣"
        Locator.Status.OFF -> "手機的定位沒有打開，打開後就看得到你這邊的天氣"
        else -> "正在找你的位置…"
    }
}

// ================================================================ 首頁的小卡片

/** 首頁：兩邊現在的天氣和一句外出建議，點了看詳細的 */
@Composable
internal fun HomeWeatherCard(s: AppState, vm: AppViewModel, open: (String) -> Unit, source: WeatherSource = vm.container.weather) {
    LocateMeQuietly(s, vm)
    val (pSpot, pWhy) = partnerSpot(s)
    val mine = s.myLocation?.let { WeatherSpot(it.lat, it.lng) }
    val p = rememberWeather(source, pSpot)
    val m = rememberWeather(source, mine)
    CuteCard(onClick = { open("weather") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今天的天氣", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("外出建議 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeatherHalf(s.partnerName, p, pWhy, Modifier.weight(1f))
            WeatherHalf("我", m, myMissingText(s), Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeatherHalf(name: String, load: WeatherLoad?, missing: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (load) {
            is WeatherLoad.Ready -> {
                val (emoji, text) = Weather.describe(load.w.code, load.w.isDay)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    Text(Weather.deg(load.w.temp), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                }
                TidyText(listOfNotNull(load.area, text).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                val a = Weather.advice(load.w)
                TidyText("${a.outing.emoji} ${a.tips.firstOrNull()?.todo ?: a.outing.text}", style = MaterialTheme.typography.labelMedium, color = outingColor(a.outing), maxLines = 2)
            }
            WeatherLoad.Loading -> Text("讀取中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is WeatherLoad.Failed -> TidyText(load.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            null -> TidyText(missing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun outingColor(o: Weather.Outing): Color = when (o) {
    Weather.Outing.GOOD -> Cute.colors.good
    Weather.Outing.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    Weather.Outing.CAREFUL, Weather.Outing.STAY -> Cute.colors.bad
}

// ================================================================ 天氣與外出建議

@Composable
fun WeatherScreen(s: AppState, vm: AppViewModel, back: () -> Unit, open: (String) -> Unit, source: WeatherSource = vm.container.weather) {
    LocateMeQuietly(s, vm)
    var reload by remember { mutableIntStateOf(0) }
    val (pSpot, pWhy) = partnerSpot(s)
    val mine = s.myLocation?.let { WeatherSpot(it.lat, it.lng) }
    val p = rememberWeather(source, pSpot, reload)
    val m = rememberWeather(source, mine, reload)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("天氣與外出建議", onBack = back) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "重新整理") }
        }
        SpotCard("${s.partnerName} 那邊", Cute.colors.partner, p, pSpot, pWhy) { w ->
            RemindPartner(s, vm, Weather.advice(w))
        }
        SpotCard("我這邊", Cute.colors.me, m, mine, myMissingText(s)) {}
        if (m == null && !s.demo && Locator.status(LocalContext.current) == Locator.Status.NO_PERMISSION) {
            SoftButton("打開定位權限", Modifier.fillMaxWidth()) { open("permissions") }
        }
        TidyText(
            "天氣和空氣品質來自 Open-Meteo.com（CC BY 4.0），只會送出大約的位置（約 1 公里），每 20 分鐘更新一次。降雨機率、紫外線是接下來 12 小時裡最高的",
            style = MaterialTheme.typography.labelSmall, color = Cute.colors.muted,
        )
    }
}

@Composable
private fun SpotCard(title: String, color: Color, load: WeatherLoad?, spot: WeatherSpot?, missing: String, extra: @Composable (WeatherNow) -> Unit) {
    CuteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
            spot?.at?.takeIf { Duration.between(it, Instant.now()).toMinutes() >= 60 }?.let {
                Text("位置是 ${Fmt.time(it)} 的", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (load) {
            null -> TidyText(missing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WeatherLoad.Loading -> Text("讀取天氣中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is WeatherLoad.Failed -> TidyText("${load.message} 😥", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is WeatherLoad.Ready -> {
                SpotWeather(load.w, load.area)
                extra(load.w)
            }
        }
    }
}

@Composable
private fun SpotWeather(w: WeatherNow, area: String?) {
    val (emoji, text) = Weather.describe(w.code, w.isDay)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Column(Modifier.weight(1f)) {
            Text(Weather.deg(w.temp), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            TidyText(listOfNotNull(area, text).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (w.high != null && w.low != null) {
                Text("今天 ${Weather.deg(w.low)} ～ ${Weather.deg(w.high)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val tiles = listOf(
        Triple("體感", w.feelsLike?.let(Weather::deg) ?: "—", null),
        Triple("濕度", w.humidity?.let { "$it%" } ?: "—", null),
        Triple("能見度", w.visibilityM?.let(Weather::visibilityText) ?: "—", null),
        Triple("降雨機率", w.rainChance?.let { "$it%" } ?: "—", "12 小時內"),
        Triple("紫外線", w.uvMax?.let { "%.0f".format(it) } ?: "—", w.uvMax?.let(Weather::uvLevel)),
        Triple("空氣品質", w.aqi?.let { "AQI $it" } ?: "—", w.aqi?.let { a -> Weather.aqiLevel(a).first + (w.pm25?.let { " · PM2.5 ${"%.0f".format(it)}" } ?: "") }),
    )
    // 字放大時一排放兩個，數字和分級才不會被擠成好幾行
    val perRow = if (LocalDensity.current.fontScale >= 1.2f) 2 else 3
    tiles.chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value, sub) -> StatTile(label, value, Modifier.weight(1f), sub = sub) }
        }
    }
    val a = Weather.advice(w)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(outingColor(a.outing).copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(a.outing.emoji, style = MaterialTheme.typography.titleLarge)
        TidyText("外出建議：${a.outing.text}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
    }
    if (a.tips.isEmpty()) {
        TidyText("天氣不錯，沒什麼要特別注意的 🌈", style = MaterialTheme.typography.bodyMedium)
    } else {
        a.tips.forEach { t ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.emoji)
                TidyText("${t.concern}：${t.todo}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 提醒對方時送的安撫：資料庫還存不了新的安撫種類時改送抱抱，提醒的話照樣送到 */
internal fun reminderKindFor(s: AppState, a: Weather.Advice): String =
    Weather.reminderKind(a).takeIf { k -> Catalog.comfort(k)?.let { s.canPick(it.since) } == true } ?: "hug"

/** 把最要緊的提醒當作安撫傳給對方（悄悄話寫著要注意什麼） */
@Composable
private fun RemindPartner(s: AppState, vm: AppViewModel, a: Weather.Advice) {
    val message = Weather.reminder(a)
    val kind = reminderKindFor(s, a)
    var sent by remember(message) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cute.colors.partnerSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TidyText("提醒 ${s.partnerName}：「$message」", style = MaterialTheme.typography.bodyMedium)
        ButtonRow {
            CuteButton(if (sent) "已經提醒了 💌" else "💌 提醒 ${s.partnerName}", small = true, enabled = !sent && !sending, onClick = {
                sending = true
                vm.act(success = "已經提醒 ${s.partnerName} 了 💌", hearts = true, onDone = { sent = true }) {
                    try { it.sendComfort(kind, message, null) } finally { sending = false }
                }
            })
        }
    }
}
