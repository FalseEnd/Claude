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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import tw.xiaoliangkou.app.data.Note as PrivateNote
import tw.xiaoliangkou.app.data.Profile
import tw.xiaoliangkou.app.data.ProfileInfo
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.components.Avatar
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CoupleCats
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
import tw.xiaoliangkou.app.ui.components.SimNumberButton
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

/**
 * 標籤在上面、沒填時顯示淡淡的範例文字的輸入框。
 * 標籤是另外一行字，[description]（不含表情符號）另外掛在輸入框上，TalkBack 才念得出是哪一格。
 */
@Composable
private fun ProfileField(
    label: String,
    description: String,
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
        FieldLabel(label, Modifier.clearAndSetSemantics {})
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder?.let { { Text(it, color = placeholderColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            singleLine = singleLine,
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
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        )
    }
}

/** 生日：點一下選日期，下面自動算出年齡和星座 */
@Composable
private fun BirthdayField(value: String, onPick: (LocalDate) -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val d = Fmt.parseDate(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("生日")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(FIELD_SHAPE).background(wellColor())
                .clickable(onClickLabel = "選擇生日", role = Role.Button) { open = true }
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
        val hint = d?.let { "自動算好了：${ProfileInfo.age(it)} 歲 · ${Horoscope.signOf(it).let { s -> "${s.name}（${s.range}）" }}" }
            ?: "填了生日，就會自動算出年齡和星座"
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
    if (open) BirthdayPicker(d, { open = false }) { onPick(it); open = false }
}

private fun utcMillis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * 選生日的日曆：還沒填的話先翻到 25 年前，不用從今年一路往回翻，
 * 但不會先幫忙選好日子（不然直接按「確定」就存了一個假生日）；也選不到未來的日子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPicker(value: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = value?.let(::utcMillis),
        initialDisplayedMonthMillis = utcMillis(value ?: today.minusYears(25).withDayOfYear(1)),
        yearRange = 1900..today.year,
        selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= utcMillis(today)
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )
    val picked = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picked?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }, enabled = picked != null) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state) }
}

// ---------------------------------------------------------------- 我的資料

/** 表單裡暱稱、生日的 key；其他是 [ProfileInfo] 的欄位。整份表單是 key → 文字，rememberSaveable 存得進去 */
private const val NAME_KEY = "_name"
private const val BIRTHDAY_KEY = "_birthday"

private fun formOf(me: Profile): Map<String, String> =
    ProfileInfo.values(me.info) + mapOf(NAME_KEY to me.displayName, BIRTHDAY_KEY to me.birthday.orEmpty())

/** 兩份表單的同一格一不一樣（前後空白不算） */
private fun same(a: Map<String, String>, b: Map<String, String>, key: String) = a[key].orEmpty().trim() == b[key].orEmpty().trim()

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val me = s.me
    if (me == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { ScreenHeader("我的資料", onBack = back) }
        return
    }
    val c = Cute.colors
    val latest = remember(me.displayName, me.birthday, me.info) { formOf(me) }
    // base：開始編輯時的資料；form：編輯中的內容。重組、轉螢幕都不會被洗掉，按「儲存」才送出
    var base by rememberSaveable(me.id) { mutableStateOf(latest) }
    var form by rememberSaveable(me.id) { mutableStateOf(latest) }
    var avatarSheet by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // 資料從別台手機更新了：沒動過的欄位換成新的，自己改過的留著
    LaunchedEffect(latest) {
        if (latest != base) {
            form = latest.mapValues { (k, v) -> if (same(form, base, k)) v else form[k].orEmpty() }
            base = latest
        }
    }

    val name = form[NAME_KEY].orEmpty()
    val birthday = form[BIRTHDAY_KEY].orEmpty()
    val errors = ProfileInfo.fields.mapNotNull { f -> numberError(f, form[f.key].orEmpty())?.let { f.key to it } }.toMap()
    val nameError = if (name.isBlank()) "暱稱不能是空白的" else null
    val valid = errors.isEmpty() && nameError == null
    // 自己改過、而且跟目前存的不一樣的欄位
    val dirty = form.keys.any { !same(form, base, it) && !same(form, latest, it) }
    val bd = Fmt.parseDate(birthday)
    val total = ProfileInfo.fields.size + 2
    val filled = listOf(name.isNotBlank(), bd != null).count { it } + ProfileInfo.fields.count { form[it.key].orEmpty().isNotBlank() && it.key !in errors }
    val partner = if (s.paired) s.partnerName else "另一半"

    fun set(key: String, v: String) { form = form + (key to v) }
    fun save(then: () -> Unit = {}) {
        // 只送自己改過的欄位，合併到最新的資料上：別台手機剛改的不會被蓋掉
        val sent = form
        val changed = sent.filterKeys { !same(sent, base, it) }
        val info = ProfileInfo.build(changed - NAME_KEY - BIRTHDAY_KEY, me.info)
        val newName = (changed[NAME_KEY] ?: me.displayName).trim()
        val newBirthday = (changed[BIRTHDAY_KEY] ?: me.birthday.orEmpty()).ifEmpty { null }
        vm.act(if (s.paired) "已儲存，${s.partnerName} 也看得到了" else "已儲存", onDone = {
            val saved = ProfileInfo.values(info) + mapOf(NAME_KEY to newName, BIRTHDAY_KEY to newBirthday.orEmpty())
            // 存的時候又改了的欄位留著
            form = saved + form.filter { (k, v) -> v != sent[k] }
            base = saved
            then()
        }) {
            it.updateProfileInfo(newName, newBirthday, info)
        }
    }
    val leave: () -> Unit = { if (dirty) { confirmLeave = true } else back() }
    BackHandler(dirty) { confirmLeave = true }

    @Composable
    fun Info(key: String, modifier: Modifier = Modifier) {
        val f = field(key)
        ProfileField(
            f.label, f.label, form[key].orEmpty(), { set(key, clean(f, it)) }, placeholderOf(f), modifier,
            keyboard = when { f.numeric -> KeyboardType.Decimal; key == ProfileInfo.PHONE -> KeyboardType.Phone; else -> KeyboardType.Text },
            singleLine = f.maxLength <= 40, error = errors[key], unit = f.unit, maxLength = f.maxLength,
        )
    }

    // 儲存列放在捲動區域下面（不是疊在上面）：鍵盤打開時，正在打字的欄位才不會被儲存列擋住。
    // 外層已經留了導覽列的高度，鍵盤只要再補超過導覽列的那一段
    Column(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars).imePadding()) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScreenHeader("我的資料", onBack = leave)
                if (s.schemaOutdated) SchemaNote("資料庫還沒更新，暱稱和生日以外的資料還存不進去")
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
                                    Pill("${ProfileInfo.age(bd)} 歲", c.card)
                                    Pill(sign.name, c.card)
                                } else Pill("還沒填生日", c.card, MaterialTheme.colorScheme.onSurfaceVariant)
                                form[ProfileInfo.BLOOD_TYPE]?.takeIf { it.isNotBlank() }?.let { Pill("${it.uppercase()} 型", c.card) }
                            }
                        }
                    }
                    Completion(filled, total, partner)
                }
                Note(
                    if (s.paired) "這些資料 ${s.partnerName} 都看得到 不想說的留白就好" else "配對之後，另一半就看得到這些資料 不想說的留白就好",
                    "💞", c.partnerSoft,
                )

                SectionTitle("基本資料")
                CuteCard {
                    ProfileField("暱稱", "暱稱", name, { set(NAME_KEY, it.take(Limits.NAME)) }, "大家都怎麼叫你", error = nameError)
                    BirthdayField(birthday, onPick = { picked ->
                        if (picked.isAfter(LocalDate.now())) vm.toast("生日不能是未來的日子喔") else set(BIRTHDAY_KEY, picked.toString())
                    }, onClear = { set(BIRTHDAY_KEY, "") })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Info(ProfileInfo.HEIGHT, Modifier.weight(1f))
                        Info(ProfileInfo.WEIGHT, Modifier.weight(1f))
                    }
                    BloodTypePicker(form[ProfileInfo.BLOOD_TYPE].orEmpty().trim().uppercase()) { set(ProfileInfo.BLOOD_TYPE, it) }
                    Info(ProfileInfo.PHONE)
                    // 自己的號碼直接從手機帶入，不用一個一個打
                    SimNumberButton(vm, onNumber = { set(ProfileInfo.PHONE, it) })
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
            // 下緣淡出：看得出來還能往下捲
            val bg = MaterialTheme.colorScheme.background
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
        }

        // 一直在下面的儲存列：長長的表單不用捲到最下面才能存
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(30.dp)).background(c.card).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (status, color) = when {
                !valid -> "有欄位要修正一下" to MaterialTheme.colorScheme.error
                dirty -> "有修改還沒儲存" to MaterialTheme.colorScheme.onSurfaceVariant
                // 綠色調深一點，白底上才看得清楚
                else -> "都儲存好了" to lerp(c.good, MaterialTheme.colorScheme.onSurface, 0.4f)
            }
            Text(
                status, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
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

/** 血型：平常是一排膠囊切換；字放很大時改成會換行的小膠囊，才不會被切掉 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BloodTypePicker(selected: String, onSelect: (String) -> Unit) {
    val f = field(ProfileInfo.BLOOD_TYPE)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(f.label)
        val options = ProfileInfo.bloodTypes.map { it to it } + ("" to "未知")
        if (LocalDensity.current.fontScale < 1.5f) Segmented(options, selected, onSelect = onSelect)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) -> ChoiceChip(if (key.isEmpty()) label else "$label 型", key == selected) { onSelect(key) } }
        }
    }
}

/** 資料庫還沒跑 upgrade-v2.sql：個人資料和備註都用不了，先說清楚，不要讓人以為是空的 */
@Composable
private fun SchemaNote(what: String) = Note(
    "$what：請到 Supabase 的 SQL Editor 執行 upgrade-v2.sql（說明在「我的 › Supabase 與帳號」）",
    "🛠️", MaterialTheme.colorScheme.errorContainer,
)

/** 資料完成度：填越多，對方越懂你 */
@Composable
private fun Completion(filled: Int, total: Int, partner: String) {
    val progress by animateFloatAsState(filled.toFloat() / total, label = "completion")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("資料完成度", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("$filled / $total", style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Cute.colors.me, trackColor = Cute.colors.card, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
        TidyText(
            if (filled >= total) "全部填好了，$partner 一定很開心" else "還有 ${total - filled} 項沒填，多寫一點讓 $partner 更懂你",
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
        bd?.let { "${ProfileInfo.age(it, today)} 歲" },
        sign?.name,
        ProfileInfo.text(info, ProfileInfo.BLOOD_TYPE)?.let { "${it.uppercase()} 型" },
        ProfileInfo.text(info, ProfileInfo.MBTI)?.let { "${it.uppercase()}" },
    )
    val tiles = TILE_KEYS.mapNotNull { k -> ProfileInfo.display(info, field(k))?.let { field(k) to it } }
    val shown = TILE_KEYS + listOf(ProfileInfo.PHONE, ProfileInfo.INTRO, ProfileInfo.BLOOD_TYPE, ProfileInfo.MBTI)
    val rows = ProfileInfo.filled(info).filter { it.first.key !in shown }
    val notes = s.notes.sortedByDescending { it.time }
    val outdated = s.schemaOutdated
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
        if (outdated) item { SchemaNote("資料庫還沒更新，看不到 $name 的個人資料，也還不能寫備註") }
        item {
            CuteCard(color = c.partnerSoft) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(name, c.partner, 84.dp, ring = true, image = p.avatarUrl)
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    if (chips.isNotEmpty()) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { chips.forEach { Pill(it, c.card) } }
                    if (sign != null) TidyText(
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card)
                    .clickable(onClickLabel = "撥打電話", role = Role.Button) { dial(phone) }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EmojiBubble("📞", MaterialTheme.colorScheme.secondaryContainer)
                Column(Modifier.weight(1f)) {
                    Text("電話", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 字放大時號碼換到第二行，不會被切成「0912-3…」：一般的換行不會拆開「912-345」這種數字，所以「-」後面加個可以換行的地方
                    TidyText(phone.replace("-", "-\u200B"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { copyText(context, "電話", phone); vm.toast("已複製電話號碼") }) {
                    Icon(Icons.Rounded.ContentCopy, "複製電話", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                // 圓形的撥打鍵（整列也可以點）：號碼才有地方完整顯示，字放大也不會變成「0912-3…」
                FilledIconButton(
                    onClick = { dial(phone) }, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
                ) { Icon(Icons.Rounded.Call, "撥打電話") }
            }
        }
        if (tiles.isNotEmpty()) {
            item { SectionTitle("身材與尺寸") }
            items(tiles.chunked(2), key = { "t" + it.first().first.key }) { pair ->
                // 一排的兩格一樣高（其中一格換行變高時）
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (f, v) -> InfoTile(f.emoji, f.label, v, Modifier.weight(1f).fillMaxHeight()) }
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
        // 資料庫還沒更新時資料本來就讀不到，不要說成「還沒填」
        if (!outdated && chips.isEmpty() && tiles.isEmpty() && rows.isEmpty() && phone == null && intro == null) item {
            CuteCard { EmptyState("$name 還沒填個人資料\n可以提醒一下：「我的 › 我的資料」就能填", "🐣") }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("只有我看得到的備註", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!outdated) CuteButton("新增", Modifier.minimumInteractiveComponentSize(), icon = Icons.Rounded.Add, small = true) { sheet(SheetSpec.NoteEdit(null)) }
            }
        }
        item { Note("$name 看不到這些備註，也不會收到通知", "🔒", noteTint(strong = true)) }
        if (notes.isEmpty() && !outdated) item {
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
            CoupleCats(height = 100.dp, partnerSad = true)
            Text("還沒有配對喔", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "配對之後，就能看到另一半填的生日、喜好和尺寸，也能寫只有你看得到的小備註",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Note("回到首頁就能配對：一人產生配對碼、另一人輸入", "💡")
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
            // 星期用短的「（一）」：字放大時標題才不會被右邊的「還有 37 天」擠成兩行
            TidyText("生日 ${Fmt.md(occ.date)}（${Fmt.weekday(occ.date).removePrefix("週")}）", style = MaterialTheme.typography.titleSmall)
            TidyText(
                when {
                    occ.daysLeft == 0 -> "今天滿 ${occ.years} 歲，快去跟 $name 說生日快樂！"
                    soon -> "滿 ${occ.years} 歲 · 可以開始準備驚喜了"
                    else -> "${birthday.year} 年出生 · 下次生日滿 ${occ.years} 歲"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 倒數放在說明下面：標題、說明用整個寬度，字放大也不會被擠成好幾行
            Box(Modifier.padding(top = 4.dp)) {
                if (occ.daysLeft == 0) HeartBeat { Pill("就是今天", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
                else Pill(if (occ.daysLeft == 1) "明天" else "還有 ${occ.daysLeft} 天", if (soon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * 身材與尺寸的一格。尺寸是自己填的（最多 10 個字，例如「L、XL希望寬鬆一點」）：放不下就換行，不會變成「L、XL希望…」。
 * 字放大時表情縮小、放到標題旁邊（像首頁的小卡），數值用整個寬度，「165.5 cm」才不會被拆成兩行
 */
@Composable
private fun InfoTile(emoji: String, label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    // 一格一起唸（「衣服尺寸 L、XL希望寬鬆一點」），不會把標題和數值拆開
    val tile = modifier.semantics(mergeDescendants = true) {}.clip(shape).background(Cute.colors.card).padding(12.dp)
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(tile, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EmojiBubble(emoji, MaterialTheme.colorScheme.surfaceVariant, 30.dp)
                TidyText(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TidyText(value, style = valueStyle)
        }
        return
    }
    Row(
        tile,
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EmojiBubble(emoji, MaterialTheme.colorScheme.surfaceVariant, 38.dp)
        Column(Modifier.weight(1f)) {
            TidyText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TidyText(value, style = valueStyle)
        }
    }
}

/** 私人備註的便利貼顏色（深色模式淡一點，才不會變成土黃色） */
@Composable
private fun noteTint(strong: Boolean = false): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val alpha = when {
        dark -> if (strong) 0.14f else 0.09f
        else -> if (strong) 0.32f else 0.2f
    }
    return Cute.colors.butter.copy(alpha = alpha).compositeOver(Cute.colors.card)
}

/** 便利貼一樣的私人備註：點一下編輯 */
@Composable
private fun NoteCard(n: PrivateNote, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(noteTint())
            .clickable(onClickLabel = "編輯備註", role = Role.Button, onClick = onOpen).padding(start = 16.dp, top = 14.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(n.body, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.rel(n.time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private val NOTE_IDEAS = listOf("🥤" to "飲料", "🎁" to "想要的禮物", "🍽️" to "愛吃的", "🙅" to "地雷", "📏" to "尺寸", "💡" to "小提醒")

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
    // 寫了還沒儲存：滑掉面板、按返回前先問
    tw.xiaoliangkou.app.ui.SheetDraftGuard(text != (note?.body ?: "").trim())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (note == null) "寫一則備註" else "編輯備註", style = MaterialTheme.typography.titleLarge)
        Note("只有你看得到，${s.partnerName} 看不到，也不會收到通知", "🔒", noteTint(strong = true))
        OutlinedTextField(
            value = body,
            onValueChange = { body = if (it.text.length <= Limits.NOTE) it else it.copy(text = it.text.take(Limits.NOTE)) },
            placeholder = { Text("例如：飲料要半糖少冰、生日想要的耳環款式", color = placeholderColor()) },
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
            vm.act(if (note == null) "記下來了，只有你看得到" else "已更新備註", onDone = close) { it.saveNote(id, text) }
        }
        if (note != null) SoftButton("刪除這則備註", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, enabled = !vm.busy) { confirmDelete = true }
    }
    if (confirmDelete && note != null) DeleteNoteDialog(note.body, onDismiss = { confirmDelete = false }) {
        confirmDelete = false
        vm.act("已刪除備註", onDone = close) { it.deleteNote(note.id) }
    }
}
