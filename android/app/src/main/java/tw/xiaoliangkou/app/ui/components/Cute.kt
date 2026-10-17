package tw.xiaoliangkou.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.ui.theme.Cute

/**
 * 換行時讓每一行差不多長：最後一行不會只剩一個字（「…離開標 / 點」→「…抵 / 達與離開標點」）。
 * 放得下一行的字不受影響；WordBreak.Phrase 在 Android 13 以上的日文、韓文會照詞換行，其他語言沒有作用。
 */
val BalancedBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)

/**
 * 一般的 Text，但換行後最後一行只剩一個字（孤字，例如「…選擇分享的項 / 目」）時，改成每行差不多長（[BalancedBreak]）。
 * 沒有孤字就照一般的換法：第一行放滿，常常剛好斷在標點後面（「文字、照片、語音，可以 / 放在桌面」）。
 * 改換法不會改變行數，所以高度不變。
 */
@Composable
fun TidyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var balance by remember(text) { mutableStateOf(false) }
    Text(
        text, modifier, color = color, textAlign = textAlign, maxLines = maxLines, overflow = overflow,
        style = if (balance) style.copy(lineBreak = BalancedBreak) else style,
        onTextLayout = { if (!balance && it.endsWithOrphan()) balance = true },
    )
}

/** 換行後最後一行只剩一個字：一個中日韓文字、一個英文字或數字，或只有表情、標點 */
internal fun TextLayoutResult.endsWithOrphan(): Boolean {
    if (lineCount < 2 || hasVisualOverflow) return false
    val last = lineCount - 1
    return wordsIn(layoutInput.text.text.substring(getLineStart(last), getLineEnd(last))) <= 1
}

/** 幾個「字」：中日韓文字一字算一個，連在一起的英文字母、數字算一個，空白、標點、表情不算 */
internal fun wordsIn(s: String): Int {
    var n = 0
    var inWord = false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val script = Character.UnicodeScript.of(cp)
        when {
            !Character.isLetterOrDigit(cp) -> inWord = false
            Character.isIdeographic(cp) || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
                script == Character.UnicodeScript.HANGUL -> { n++; inWord = false }
            !inWord -> { n++; inWord = true }
        }
        i += Character.charCount(cp)
    }
    return n
}

/** 不跟著系統字體放大的字級：給表情這類裝飾用，字放大時把寬度留給真正的字。一般字級時大小不變。 */
@Composable
fun TextStyle.unscaled(): TextStyle = with(LocalDensity.current) {
    copy(
        fontSize = if (fontSize.isSp) fontSize.value.dp.toSp() else fontSize,
        lineHeight = if (lineHeight.isSp) lineHeight.value.dp.toSp() else lineHeight,
    )
}

/** 按下去會「啵」一下縮小再彈回來的點擊效果 */
fun Modifier.bouncyClick(enabled: Boolean = true, haptic: Boolean = false, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "bounce")
    val feedback = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

@Composable
fun CuteCard(
    modifier: Modifier = Modifier,
    color: Color = Cute.colors.card,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.bouncyClick(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 圓滾滾的彩色圖示底 */
@Composable
fun IconBubble(icon: ImageVector, tint: Color, background: Color, size: Dp = 40.dp, iconSize: Dp = 22.dp, contentDescription: String? = null) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.38f)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun EmojiBubble(emoji: String, background: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.38f)).background(background), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = MaterialTheme.typography.titleLarge.fontSize * (size / 40.dp))
    }
}

/** 頭像：照片、表情，或暱稱的最後一個字（[image] 是 profiles.avatar_url，見 [Avatars]） */
@Composable
fun Avatar(name: String, color: Color, size: Dp = 36.dp, ring: Boolean = false, image: String? = null) {
    val photo = remember(image) { Avatars.photoOf(image)?.asImageBitmap() }
    val emoji = remember(image) { Avatars.emojiOf(image) }
    val scale = size / 36.dp
    Box(
        Modifier.size(size).then(if (ring) Modifier.border(3.dp, MaterialTheme.colorScheme.background, CircleShape) else Modifier)
            .clip(CircleShape).background(if (emoji != null) color.copy(alpha = 0.28f).compositeOver(Cute.colors.card) else color),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> Image(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            emoji != null -> Text(emoji, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * scale * 1.15f))
            else -> Text(Avatars.initial(name), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * scale))
        }
    }
}

@Composable
fun CuteButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (enabled) color else color.copy(alpha = 0.4f), label = "btn")
    // 高度是最小值：字放大、按鈕又窄的時候，字換到第二行、按鈕變高，不會被切掉（並排的按鈕請用 ButtonRow）
    Row(
        modifier
            .heightIn(min = if (small) 38.dp else 52.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bouncyClick(enabled = enabled, haptic = !small, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = contentColor, modifier = Modifier.size(if (small) 18.dp else 20.dp))
        TidyText(
            text, color = contentColor, style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    CuteButton(text, modifier, icon, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, enabled, small, onClick)

/**
 * 一排並排的按鈕（取代 Row + Modifier.weight）：每顆按鈕的字都放得下一行就並排，寬度照 [weights] 分（沒給就平分）；
 * 放不下的時候（字放大、窄手機）從放不下的那顆開始換到下一排，自己佔滿一排，字不會被切掉。
 * [maxPerRow]：一排最多幾顆。按鈕不要再加 weight / fillMaxWidth，寬度由這裡決定。
 */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, weights: List<Float> = emptyList(), spacing: Dp = 8.dp, maxPerRow: Int = Int.MAX_VALUE, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        fun weight(i: Int) = weights.getOrNull(i)?.takeIf { it > 0f } ?: 1f
        /** [from, to) 這幾顆放在同一排時各自的寬度 */
        fun widths(from: Int, to: Int): IntArray {
            val free = (width - gap * (to - from - 1)).coerceAtLeast(0)
            val total = (from until to).sumOf { weight(it).toDouble() }
            val out = IntArray(to - from) { (free * weight(from + it) / total).toInt() }
            out[out.lastIndex] += free - out.sum()
            return out
        }
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        fun fits(from: Int, to: Int) = widths(from, to).withIndex().all { (k, w) -> natural[from + k] <= w }
        val rows = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < measurables.size) {
            var end = start + 1
            while (end < measurables.size && end - start < maxPerRow && fits(start, end + 1)) end++
            rows += start to end
            start = end
        }
        val placed = rows.map { (from, to) ->
            val ws = widths(from, to)
            (from until to).map { measurables[it].measure(Constraints.fixedWidth(ws[it - from])) }
        }
        val heights = placed.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + gap * (heights.size - 1).coerceAtLeast(0)
        layout(width, total.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            placed.forEachIndexed { r, row ->
                var x = 0
                row.forEach { p ->
                    p.placeRelative(x, y + (heights[r] - p.height) / 2)
                    x += p.width + gap
                }
                y += heights[r] + gap
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).then(if (onTrailing != null) Modifier.bouncyClick(onClick = onTrailing) else Modifier).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onTrailing != null) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 小膠囊標籤。放不下時：[maxLines] 行以內換行，超過的話結尾一定看得到「…」，不會無聲無息被切掉 */
@Composable
fun Pill(
    text: String,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = contentColor, modifier = Modifier.size(14.dp))
        TidyText(text, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, emoji: String? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "chip")
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val scale by animateFloatAsState(if (selected) 1.05f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "chipScale")
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50)).background(bg)
            .bouncyClick(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (emoji != null) Text(emoji)
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** 膠囊型的切換按鈕組 */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            val bg by animateColorAsState(if (on) Cute.colors.card else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(bg)
                    .bouncyClick(role = Role.Tab) { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, emoji: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 表情不跟著字放大，寬度留給說明文字
        if (emoji != null) Text(emoji, style = MaterialTheme.typography.titleLarge.unscaled())
        Column(Modifier.weight(1f)) {
            TidyText(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) TidyText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedBorderColor = Color.Transparent, uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
fun MenuRow(icon: ImageVector, tint: Color, bg: Color, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).bouncyClick(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBubble(icon, tint, bg)
        Column(Modifier.weight(1f)) {
            TidyText(title, style = MaterialTheme.typography.titleSmall)
            // 說明是這一頁有什麼，字放大時換行，不要只剩一半
            if (subtitle != null) TidyText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CuteField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    password: Boolean = false,
    supporting: String? = null,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        trailingIcon = trailing,
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) } },
        singleLine = singleLine,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Cute.colors.card, focusedContainerColor = Cute.colors.card,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** App 在前景時從上方滑下來的通知卡片 */
@Composable
fun BannerCard(visible: Boolean, emoji: String, title: String, body: String?, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = 400f)) { -it * 2 } + fadeIn(),
        exit = slideOutVertically { -it * 2 } + fadeOut(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Cute.colors.card)
                .bouncyClick(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmojiBubble(emoji, MaterialTheme.colorScheme.primaryContainer, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (!body.isNullOrBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
fun EmptyState(text: String, emoji: String = "🐣", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Wiggle { Text(emoji, style = MaterialTheme.typography.displayMedium) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun Note(text: String, emoji: String = "💡", color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(emoji)
        TidyText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 小數字卡。[icon] 放在數值旁邊（例如幸運色的色塊）；[labelIcon] 放在標題旁邊，數值可以用整個寬度。
 * 窄的卡片裡數值、說明最多換成兩行，不會被縮成「行…」。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: @Composable (() -> Unit)? = null,
    labelIcon: @Composable (() -> Unit)? = null,
    extra: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labelIcon?.invoke()
            TidyText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.invoke()
            TidyText(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        extra?.invoke()
        if (sub != null) TidyText(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun cuteSlider() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
    activeTickColor = MaterialTheme.colorScheme.onPrimary,
    inactiveTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
)

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
