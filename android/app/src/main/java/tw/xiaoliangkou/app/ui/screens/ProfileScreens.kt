package tw.xiaoliangkou.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.core.Horoscope
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.ProfileInfo
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.EmptyState
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SectionTitle
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate
import tw.xiaoliangkou.app.data.Note as PrivateNote

// ---------------------------------------------------------------- 欄位小工具

private val FIELD_SHAPE = RoundedCornerShape(18.dp)

/** 身高、體重合理的範圍：多打一個 0 時提醒一下 */
private val NUMBER_RANGES = mapOf(ProfileInfo.HEIGHT to 50..250, ProfileInfo.WEIGHT to 20..300)

private fun field(key: String) = ProfileInfo.fields.first { it.key == key }

/** 全形數字轉半形（注音輸入法常常打出全形） */
private fun halfWidth(s: String) = s.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")

/** 輸入時先過濾：數字欄位只留數字和小數點，電話只留號碼會用到的符號 */
private fun clean(f: ProfileInfo.Field, raw: String): String = when {
    f.numeric -> halfWidth(raw).replace('，', '.').replace(',', '.').replace('．', '.').filter { it in '0'..'9' || it == '.' }
    f.key == ProfileInfo.PHONE -> halfWidth(raw).filter { it in '0'..'9' || it in "+-#*() " }
    f.key == ProfileInfo.MBTI -> raw.uppercase()
    else -> raw
}.take(f.maxLength)

private fun numberError(f: ProfileInfo.Field, raw: String): String? {
    if (!f.numeric) return null
    val t = raw.trim()
    if (t.isEmpty()) return null
    val n = t.toDoubleOrNull() ?: return "只能填數字喔"
    val range = NUMBER_RANGES[f.key] ?: 1..999
    return if (n >= range.first && n <= range.last) null else "要在 ${range.first}–${range.last} 之間"
}

private fun placeholderOf(f: ProfileInfo.Field) = f.hint?.let { if (f.key == ProfileInfo.INTRO) it else "例如：$it" }

@Composable
private fun wellColor() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f).compositeOver(Cute.colors.card)

@Composable
private fun placeholderColor() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = modifier.padding(start = 4.dp))

/** 標籤在上面、沒填時顯示淡淡的範例文字的輸入框 */
@Composable
private fun ProfileField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String?,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    error: String? = null,
    unit: String? = null,
    maxLength: Int? = null,
) {
    val counter = maxLength?.takeIf { !singleLine && value.length >= it * 0.8 }?.let { "${value.length} / $it" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder?.let { { Text(it, color = placeholderColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 6,
            isError = error != null,
            supportingText = (error ?: counter)?.let { { Text(it) } },
            trailingIcon = unit?.let { { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
            shape = FIELD_SHAPE,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = wellColor(), focusedContainerColor = Cute.colors.card, errorContainerColor = wellColor(),
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 生日：點一下選日期，下面自動算出年齡和星座 */
@Composable
private fun BirthdayField(value: String, onPick: (String) -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val d = Fmt.parseDate(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("🎂 生日")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(FIELD_SHAPE).background(wellColor()).clickable { open = true }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                d?.let { "${it.year}/${it.monthValue}/${it.dayOfMonth}（${Fmt.weekday(it)}）" } ?: "還沒填，點一下選日期",
                style = MaterialTheme.typography.bodyLarge, color = if (d == null) placeholderColor() else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (d != null) IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "清除生日", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            else Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        }
        val hint = d?.let { "自動算好了：${ProfileInfo.age(it)} 歲 · ${Horoscope.signOf(it).let { s -> "${s.emoji} ${s.name}（${s.range}）" }}" }
            ?: "填了生日，就會自動算出年齡和星座 ✨"
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
    // 還沒填生日：日曆先停在 25 年前，不用從今年一路往回翻
    if (open) DatePickerPopup(value.ifEmpty { LocalDate.now().minusYears(25).withDayOfYear(1).toString() }, { open = false }) { onPick(it); open = false }
}

// ---------------------------------------------------------------- 我的資料

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val me = s.me
    if (me == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { ScreenHeader("我的資料", onBack = back) }
        return
    }
    val c = Cute.colors
    val original = remember(me.info) { ProfileInfo.values(me.info) }
    // 編輯中的內容：重組、轉螢幕、資料從別台手機更新都不會被洗掉，按「儲存」才送出
    var name by rememberSaveable(me.id) { mutableStateOf(me.displayName) }
    var birthday by rememberSaveable(me.id) { mutableStateOf(me.birthday.orEmpty()) }
    var values by rememberSaveable(me.id) { mutableStateOf(original) }
    var avatarSheet by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val errors = ProfileInfo.fields.mapNotNull { f -> numberError(f, values[f.key].orEmpty())?.let { f.key to it } }.toMap()
    val nameError = if (name.isBlank()) "暱稱不能是空白的" else null
    val valid = errors.isEmpty() && nameError == null
    val dirty = name.trim() != me.displayName || birthday != me.birthday.orEmpty() ||
        ProfileInfo.fields.any { values[it.key].orEmpty().trim() != original[it.key].orEmpty() }
    val bd = Fmt.parseDate(birthday)
    val total = ProfileInfo.fields.size + 2
    val filled = listOf(name.isNotBlank(), bd != null).count { it } + ProfileInfo.fields.count { values[it.key].orEmpty().isNotBlank() && it.key !in errors }
    val partner = if (s.paired) s.partnerName else "另一半"

    fun set(key: String, v: String) { values = values + (key to v) }
    fun save(then: () -> Unit = {}) {
        val info = ProfileInfo.build(values, me.info)
        val newName = name.trim()
        vm.act(if (s.paired) "已儲存，${s.partnerName} 也看得到了 ✨" else "已儲存 ✨", onDone = { name = newName; values = ProfileInfo.values(info); then() }) {
            it.updateProfileInfo(newName, birthday.ifEmpty { null }, info)
        }
    }
    val leave: () -> Unit = { if (dirty) { confirmLeave = true } else back() }
    BackHandler(dirty) { confirmLeave = true }

    @Composable
    fun Info(key: String, modifier: Modifier = Modifier) {
        val f = field(key)
        ProfileField(
            "${f.emoji} ${f.label}", values[key].orEmpty(), { set(key, clean(f, it)) }, placeholderOf(f), modifier,
            keyboard = when { f.numeric -> KeyboardType.Decimal; key == ProfileInfo.PHONE -> KeyboardType.Phone; else -> KeyboardType.Text },
            singleLine = f.maxLength <= 40, error = errors[key], unit = f.unit, maxLength = f.maxLength,
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("我的資料", onBack = leave)
            CuteCard(color = c.meSoft) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 點頭像換照片或表情
                    Box(Modifier.bouncyClick { avatarSheet = true }) {
                        Avatar(name.ifBlank { me.displayName }, c.me, 68.dp, image = me.avatarUrl)
                        Icon(
                            Icons.Rounded.PhotoCamera, "設定頭像", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(24.dp).clip(CircleShape).background(c.card).padding(4.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name.trim().ifEmpty { "還沒取暱稱" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (bd != null) {
                                val sign = Horoscope.signOf(bd)
                                Pill("🎂 ${ProfileInfo.age(bd)} 歲", c.card)
                                Pill("${sign.emoji} ${sign.name}", c.card)
                            } else Pill("🎂 還沒填生日", c.card, MaterialTheme.colorScheme.onSurfaceVariant)
                            values[ProfileInfo.BLOOD_TYPE]?.takeIf { it.isNotBlank() }?.let { Pill("🩸 ${it.uppercase()} 型", c.card) }
                        }
                    }
                }
                Completion(filled, total, partner)
            }
            Note(
                if (s.paired) "這些資料 ${s.partnerName} 都看得到 👀 不想說的留白就好" else "配對之後，另一半就看得到這些資料 👀 不想說的留白就好",
                "💞", c.partnerSoft,
            )

            SectionTitle("基本資料")
            CuteCard {
                ProfileField("😊 暱稱", name, { name = it.take(Limits.NAME) }, "大家都怎麼叫你", error = nameError)
                BirthdayField(birthday, onPick = { picked ->
                    if (LocalDate.parse(picked).isAfter(LocalDate.now())) vm.toast("生日不能是未來的日子喔 🤔") else birthday = picked
                }, onClear = { birthday = "" })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Info(ProfileInfo.HEIGHT, Modifier.weight(1f))
                    Info(ProfileInfo.WEIGHT, Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("${field(ProfileInfo.BLOOD_TYPE).emoji} ${field(ProfileInfo.BLOOD_TYPE).label}")
                    Segmented(
                        ProfileInfo.bloodTypes.map { it to "$it 型" } + ("" to "不確定"),
                        values[ProfileInfo.BLOOD_TYPE].orEmpty().trim().uppercase(),
                    ) { set(ProfileInfo.BLOOD_TYPE, it) }
                }
                Info(ProfileInfo.PHONE)
            }

            SectionTitle("個性與生活")
            CuteCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Info(ProfileInfo.MBTI, Modifier.weight(0.8f))
                    Info(ProfileInfo.JOB, Modifier.weight(1.2f))
                }
                Info(ProfileInfo.HOBBIES)
                Info(ProfileInfo.INTRO)
            }

            SectionTitle("喜好與小提醒", trailing = "點餐、送禮都用得到")
            CuteCard {
                Info(ProfileInfo.LIKES)
                Info(ProfileInfo.DISLIKES)
                Info(ProfileInfo.ALLERGIES)
            }

            SectionTitle("尺寸", trailing = "買衣服鞋子不怕買錯")
            CuteCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Info(ProfileInfo.CLOTHES_SIZE, Modifier.weight(1f))
                    Info(ProfileInfo.SHOE_SIZE, Modifier.weight(1f))
                }
            }

            // 之後新增的欄位：還沒排進上面的分類就先放這裡
            val placed = setOf(
                ProfileInfo.HEIGHT, ProfileInfo.WEIGHT, ProfileInfo.BLOOD_TYPE, ProfileInfo.PHONE, ProfileInfo.MBTI, ProfileInfo.JOB, ProfileInfo.HOBBIES,
                ProfileInfo.INTRO, ProfileInfo.LIKES, ProfileInfo.DISLIKES, ProfileInfo.ALLERGIES, ProfileInfo.CLOTHES_SIZE, ProfileInfo.SHOE_SIZE,
            )
            val others = ProfileInfo.fields.filter { it.key !in placed }
            if (others.isNotEmpty()) {
                SectionTitle("其他")
                CuteCard { others.forEach { Info(it.key) } }
            }
        }

        // 一直浮在下面的儲存列：長長的表單不用捲到最下面才能存
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(30.dp)).background(c.card).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (status, color) = when {
                !valid -> "有欄位要修正一下 ✋" to MaterialTheme.colorScheme.error
                dirty -> "有修改還沒儲存" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "都儲存好了 ✓" to c.good
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CuteButton("儲存", icon = Icons.Rounded.Check, enabled = dirty && valid && !vm.busy) { save() }
        }
    }

    if (avatarSheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { avatarSheet = false }, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                AvatarSheet(s, vm) { avatarSheet = false }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("還沒儲存喔") },
        text = { Text(if (valid) "剛剛改的資料還沒儲存，要先存起來嗎？" else "有欄位還要修正。現在離開的話，剛剛改的都不會儲存。") },
        confirmButton = {
            if (valid) TextButton(onClick = { confirmLeave = false; save(back) }) { Text("儲存") }
            else TextButton(onClick = { confirmLeave = false }) { Text("回去修改") }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false; back() }) { Text("不儲存", color = MaterialTheme.colorScheme.error) } },
    )
}

/** 資料完成度：填越多，對方越懂你 */
@Composable
private fun Completion(filled: Int, total: Int, partner: String) {
    val progress by animateFloatAsState(filled.toFloat() / total, label = "completion")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("資料完成度", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("$filled / $total", style = MaterialTheme.typography.labelLarge, color = Cute.colors.me)
        }
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Cute.colors.me, trackColor = Cute.colors.card, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
        Text(
            if (filled >= total) "全部填好了，$partner 一定很開心 🥰" else "還有 ${total - filled} 項沒填，多寫一點讓 $partner 更懂你 💡",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 對方的資料與備註

/** 生日、電話、星座以外，放在兩欄小格子裡的短欄位 */
private val TILE_KEYS = listOf(ProfileInfo.HEIGHT, ProfileInfo.WEIGHT, ProfileInfo.CLOTHES_SIZE, ProfileInfo.SHOE_SIZE)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartnerProfileScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) {
    val p = s.partner
    if (!s.paired || p == null) {
        UnpairedHint(back)
        return
    }
    val c = Cute.colors
    val context = LocalContext.current
    val name = p.displayName
    val info = p.info
    val bd = p.birthDate
    val today = LocalDate.now()
    val sign = bd?.let { Horoscope.signOf(it) }
    val phone = ProfileInfo.text(info, ProfileInfo.PHONE)
    val intro = ProfileInfo.text(info, ProfileInfo.INTRO)
    val chips = listOfNotNull(
        bd?.let { "🎂 ${ProfileInfo.age(it, today)} 歲" },
        sign?.let { "${it.emoji} ${it.name}" },
        ProfileInfo.text(info, ProfileInfo.BLOOD_TYPE)?.let { "🩸 ${it.uppercase()} 型" },
        ProfileInfo.text(info, ProfileInfo.MBTI)?.let { "🧠 ${it.uppercase()}" },
    )
    val tiles = TILE_KEYS.mapNotNull { k -> ProfileInfo.display(info, field(k))?.let { field(k) to it } }
    val shown = TILE_KEYS + listOf(ProfileInfo.PHONE, ProfileInfo.INTRO, ProfileInfo.BLOOD_TYPE, ProfileInfo.MBTI)
    val rows = ProfileInfo.filled(info).filter { it.first.key !in shown }
    val notes = s.notes.sortedByDescending { it.time }
    var deleting by remember { mutableStateOf<PrivateNote?>(null) }

    fun dial(number: String) {
        val tel = number.filter { it.isDigit() || it in "+#*" }
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", tel, null)))
        } catch (e: ActivityNotFoundException) {
            vm.toast("這台裝置不能撥電話，號碼先幫你複製好了")
            copyText(context, "電話", number)
        }
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("$name 的資料", onBack = back) }
        item {
            CuteCard(color = c.partnerSoft) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(name, c.partner, 84.dp, ring = true, image = p.avatarUrl)
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    if (chips.isNotEmpty()) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { chips.forEach { Pill(it, c.card) } }
                    if (sign != null) Text(
                        "${sign.name}的$name：${sign.trait}，帶給你滿滿的${sign.gift}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    if (intro != null) Text(
                        "「$intro」", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card.copy(alpha = 0.75f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (bd != null) item { BirthdayCountdown(name, bd, today) }
        if (phone != null) item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).clickable { dial(phone) }.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EmojiBubble("📞", MaterialTheme.colorScheme.secondaryContainer)
                Column(Modifier.weight(1f)) {
                    Text("電話", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(phone, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { copyText(context, "電話", phone); vm.toast("已複製電話號碼") }) {
                    Icon(Icons.Rounded.ContentCopy, "複製電話", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                CuteButton("撥打", icon = Icons.Rounded.Call, small = true, color = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) { dial(phone) }
            }
        }
        if (tiles.isNotEmpty()) {
            item { SectionTitle("身材與尺寸") }
            items(tiles.chunked(2), key = { "t" + it.first().first.key }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (f, v) -> InfoTile(f.emoji, f.label, v, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (rows.isNotEmpty()) {
            item { SectionTitle("喜好與生活") }
            item {
                val tints = listOf(c.partnerSoft, c.butter.copy(alpha = 0.3f), c.mint.copy(alpha = 0.25f), c.lavender.copy(alpha = 0.25f), c.sky.copy(alpha = 0.25f), c.peach.copy(alpha = 0.25f))
                CuteCard {
                    rows.forEachIndexed { i, (f, v) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EmojiBubble(f.emoji, if (f.key == ProfileInfo.ALLERGIES) c.periodSoft else tints[i % tints.size], 38.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (f.key == ProfileInfo.ALLERGIES) "${f.label} · 要記得喔" else f.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (f.key == ProfileInfo.ALLERGIES) c.period else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(v, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        if (chips.isEmpty() && tiles.isEmpty() && rows.isEmpty() && phone == null && intro == null) item {
            CuteCard { EmptyState("$name 還沒填個人資料\n可以提醒一下：「我的 › 我的資料」就能填 ✍️", "🐣") }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("只有我看得到的備註 🔒", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CuteButton("新增", icon = Icons.Rounded.Add, small = true) { sheet(SheetSpec.NoteEdit(null)) }
            }
        }
        item { Note("$name 看不到這些備註，也不會收到通知。只有你自己知道 🤫", "🔒", c.butter.copy(alpha = 0.3f)) }
        if (notes.isEmpty()) item {
            CuteCard {
                EmptyState("記下 $name 的小喜好、想要的禮物、\n不能踩的地雷，只有你看得到", "📝")
                SoftButton("寫第一則備註", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) { sheet(SheetSpec.NoteEdit(null)) }
            }
        }
        items(notes, key = { it.id }) { n -> NoteCard(n, onOpen = { sheet(SheetSpec.NoteEdit(n.id)) }, onDelete = { deleting = n }) }
    }

    deleting?.let { n ->
        DeleteNoteDialog(n.body, onDismiss = { deleting = null }) {
            deleting = null
            vm.act("已刪除備註") { it.deleteNote(n.id) }
        }
    }
}

@Composable
private fun UnpairedHint(back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("另一半的資料", onBack = back)
        CuteCard(color = Cute.colors.partnerSoft) {
            CoupleMascots(height = 100.dp, partnerSad = true)
            Text("還沒有配對喔", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "配對之後，就能看到另一半填的生日、喜好和尺寸，也能寫只有你看得到的小備註 🔒",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Note("邀請另一半配對後，這一頁就會出現 TA 的資料 💌", "💡")
    }
}

@Composable
private fun BirthdayCountdown(name: String, birthday: LocalDate, today: LocalDate) {
    val occ = Dates.nextOccurrence(birthday, today, yearly = true)
    val soon = occ.daysLeft <= 30
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBubble(if (occ.daysLeft == 0) "🥳" else "🎂", Cute.colors.partnerSoft)
        Column(Modifier.weight(1f)) {
            Text("生日 ${Fmt.md(occ.date)}（${Fmt.weekday(occ.date)}）", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    occ.daysLeft == 0 -> "今天滿 ${occ.years} 歲，快去跟 $name 說生日快樂！"
                    soon -> "滿 ${occ.years} 歲 · 可以開始準備驚喜了 🎁"
                    else -> "${birthday.year} 年出生 · 下次生日滿 ${occ.years} 歲"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (occ.daysLeft == 0) HeartBeat { Pill("就是今天 🎉", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
        else Pill(if (occ.daysLeft == 1) "明天" else "還有 ${occ.daysLeft} 天", if (soon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun InfoTile(emoji: String, label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Cute.colors.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EmojiBubble(emoji, MaterialTheme.colorScheme.surfaceVariant, 38.dp)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 便利貼一樣的私人備註：點一下編輯 */
@Composable
private fun NoteCard(n: PrivateNote, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cute.colors.butter.copy(alpha = 0.18f).compositeOver(Cute.colors.card))
            .clickable(onClick = onOpen).padding(start = 16.dp, top = 14.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(n.body, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Text("🔒 ${Fmt.rel(n.time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete, modifier = Modifier.offset(y = (-8).dp)) {
            Icon(Icons.Rounded.DeleteOutline, "刪除備註", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DeleteNoteDialog(body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("刪除這則備註？") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("「${body.trim()}」", maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("刪掉就找不回來了喔。")
        }
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("刪除", color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
)

// ---------------------------------------------------------------- 備註面板

/** 新增備註時可以點的開頭 */
private val NOTE_IDEAS = listOf("🧋" to "飲料", "🎁" to "想要的禮物", "🍽️" to "愛吃的", "🙅" to "地雷", "📏" to "尺寸", "💡" to "小提醒")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteSheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) {
    val note = id?.let { i -> s.notes.firstOrNull { it.id == i } }
    var confirmDelete by remember { mutableStateOf(false) }
    if (id != null && note == null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState("找不到這則備註，可能已經刪掉了", "🍃")
            SoftButton("關閉", Modifier.fillMaxWidth(), onClick = close)
        }
        return
    }
    var body by rememberSaveable(id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(note?.body.orEmpty())) }
    val text = body.text.trim()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (note == null) "寫一則備註 📝" else "編輯備註 📝", style = MaterialTheme.typography.titleLarge)
        Note("只有你看得到，${s.partnerName} 看不到，也不會收到通知 🤫", "🔒", Cute.colors.butter.copy(alpha = 0.3f))
        OutlinedTextField(
            value = body,
            onValueChange = { body = if (it.text.length <= Limits.NOTE) it else it.copy(text = it.text.take(Limits.NOTE)) },
            placeholder = { Text("例如：珍奶半糖少冰、生日想要的耳環款式…", color = placeholderColor()) },
            minLines = 5, maxLines = 12,
            supportingText = { Text("${body.text.length} / ${Limits.NOTE}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            shape = FIELD_SHAPE,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Cute.colors.card, focusedContainerColor = Cute.colors.card,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (note == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NOTE_IDEAS.forEach { (emoji, label) ->
                ChoiceChip(label, false, emoji) {
                    val t = body.text.trimEnd().let { if (it.isEmpty()) "$label：" else "$it\n$label：" }.take(Limits.NOTE)
                    body = TextFieldValue(t, TextRange(t.length))
                }
            }
        }
        CuteButton("儲存", Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, enabled = text.isNotEmpty() && text != note?.body && !vm.busy) {
            vm.act(if (note == null) "記下來了，只有你看得到 🔒" else "已更新備註 🔒", onDone = close) { it.saveNote(id, text) }
        }
        if (note != null) SoftButton("刪除這則備註", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy) { confirmDelete = true }
    }
    if (confirmDelete && note != null) DeleteNoteDialog(note.body, onDismiss = { confirmDelete = false }) {
        confirmDelete = false
        vm.act("已刪除備註", onDone = close) { it.deleteNote(note.id) }
    }
}
