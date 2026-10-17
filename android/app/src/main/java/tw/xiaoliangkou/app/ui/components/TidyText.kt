package tw.xiaoliangkou.app.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import kotlin.math.ceil

/**
 * 會挑地方換行的 Text，給標題、說明、按鈕、小卡這類短字用。
 *
 * 一般的 Text 換行時第一行塞滿就換，中文常被切在詞中間（「可以放 / 在桌面」「小 / 雨 的狀態」），
 * 或最後一行只剩一個字（「禮物模式除 / 外）」）。這裡改成盡量在標點、空白後面換行
 * （「文字、照片、語音，/ 可以放在桌面」），不把數字和單位拆開（「30 / Mbps」），也不留孤字。
 *
 * 行數一定和一般的 Text 一樣（高度不變，只是換的位置不同）；放得下一行、字多到要「…」、
 * 或找不到更好的換法時，就和一般的 Text 一模一樣。無障礙與測試看到的是原本的字。
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
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val look = if (textAlign != null) style.copy(textAlign = textAlign) else style
    val shown = remember { mutableStateOf<TextLayoutResult?>(null) }
    val policy = remember(text, look, maxLines, overflow, measurer) {
        TidyMeasurePolicy(TidyLayout(measurer, text, look, maxLines, overflow)) { shown.value = it }
    }
    Layout(
        modifier
            .semantics {
                this.text = AnnotatedString(text)
                getTextLayoutResult { out -> shown.value?.let { out += it; true } ?: false }
            }
            .drawBehind { shown.value?.let { drawText(it, color = ink) } },
        policy,
    )
}

private class TidyMeasurePolicy(val tidy: TidyLayout, val onLayout: (TextLayoutResult) -> Unit) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val r = tidy.layout(constraints, this, layoutDirection)
        onLayout(r)
        val size = constraints.constrain(r.size)
        return layout(size.width, size.height) {}
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        ceil(tidy.oneLine(this, layoutDirection).multiParagraph.intrinsics.minIntrinsicWidth).toInt()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        ceil(tidy.oneLine(this, layoutDirection).multiParagraph.intrinsics.maxIntrinsicWidth).toInt()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        tidy.layout(Constraints(maxWidth = width), this, layoutDirection).size.height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        tidy.layout(Constraints(maxWidth = width), this, layoutDirection).size.height
}

/** 量字、決定在哪裡換行 */
internal class TidyLayout(
    private val measurer: TextMeasurer,
    private val text: String,
    private val style: TextStyle,
    private val maxLines: Int,
    private val overflow: TextOverflow,
) {
    /** 用「\n」分開的每一段：從哪裡開始、可以換行的地方 */
    private class Paragraph(val start: Int, val text: String) {
        val end get() = start + text.length
        val spots by lazy { breakSpots(text) }
    }

    private val paragraphs: List<Paragraph> by lazy {
        var from = 0
        text.split('\n').map { Paragraph(from, it).also { p -> from = p.end + 1 } }
    }

    /** 不換行時的樣子（最寬、最窄的寬度用） */
    fun oneLine(density: Density, direction: LayoutDirection): TextLayoutResult =
        measurer.measure(text, style, softWrap = false, layoutDirection = direction, density = density)

    fun layout(constraints: Constraints, density: Density, direction: LayoutDirection): TextLayoutResult {
        fun measure(s: String) = measurer.measure(
            s, style, overflow, softWrap = true, maxLines = maxLines, constraints = constraints,
            layoutDirection = direction, density = density,
        )
        val plain = measure(text)
        val lines = plain.lineCount
        // 一行放得下、字多到被截掉：照一般的 Text
        if (lines < 2 || plain.hasVisualOverflow || !constraints.hasBoundedWidth) return plain
        val breaks = ArrayList<Int>()
        for (para in paragraphs) {
            val first = plain.getLineForOffset(para.start)
            val n = plain.getLineForOffset(maxOf(para.start, para.end - 1)) - first + 1
            if (n < 2 || para.spots.at.isEmpty()) continue
            val usual = IntArray(n - 1) { plain.getLineStart(first + 1 + it) - para.start }
            // 四行以上是段落：中文段落本來就每行塞滿、詞可以跨行，只有最後一行只剩一個字時才調整
            if (n >= 4 && !isOrphan(para.text.substring(usual.last()))) continue
            val one = measurer.measure(para.text, style.copy(textAlign = TextAlign.Start), softWrap = false, maxLines = 1, layoutDirection = direction, density = density)
            val x0 = one.getHorizontalPosition(0, true)
            val starts = planLines(para.text, para.spots, { kotlin.math.abs(one.getHorizontalPosition(it, true) - x0) }, constraints.maxWidth - 0.5f, n, usual)
            if (starts == null || starts.contentEquals(usual)) continue
            starts.forEach { breaks += para.start + it }
        }
        if (breaks.isEmpty()) return plain
        val shown = buildString {
            var from = 0
            for (b in breaks) {
                append(text, from, b)
                while (isNotEmpty() && last().isWhitespace() && last() != '\n') setLength(length - 1)
                append('\n')
                from = b
            }
            append(text, from, text.length)
        }
        val tidy = measure(shown)
        // 量出來跟算的不一樣（例如字距誤差讓某行又折一次）就不冒險
        return if (tidy.lineCount == lines && !tidy.hasVisualOverflow) tidy else plain
    }
}

/** 一段字裡可以換行的位置（換行後下一行從哪個字開始），和在那裡換行的代價（0 最好） */
internal class BreakSpots(val at: IntArray, val cost: FloatArray)

internal fun breakSpots(text: String): BreakSpots {
    val at = ArrayList<Int>()
    val cost = ArrayList<Float>()
    var depth = 0 // 在幾層括號、引號裡面
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isOpening(cp)) depth++ else if (isClosing(cp)) depth = (depth - 1).coerceAtLeast(0)
        i += Character.charCount(cp)
        // 括號、引號裡面的東西是一起的（「（最長 / 3 分鐘）」）：能在外面換就在外面換
        if (i < text.length) breakCost(text, i)?.let { at += i; cost += it + if (depth > 0) 0.8f else 0f }
    }
    return BreakSpots(at.toIntArray(), cost.toFloatArray())
}

/**
 * 在 [p] 前面換行的代價：空白、標點後面 0；「的」「了」後面、「和」「與」前面 0.6；中文字和英數字之間、「給 / 小雨」0.5；
 * 把兩個中文字拆開 1.5（可能是一個詞）；新的一行用「的」「了」開頭再加 0.8。
 * 不能換行（英文字、數字中間，數字和單位中間，標點前面，表情中間）時是 null。
 */
internal fun breakCost(s: String, p: Int): Float? {
    val a = s.codePointBefore(p)
    val b = s.codePointAt(p)
    if (Character.isWhitespace(b) || isGlue(a) || isGlue(b) || isJoining(a, b)) return null
    if (noLineStart(b)) return null
    val particleFirst = if (b.isOneOf(PARTICLES)) 0.8f else 0f // 「…小雨 / 的狀態」
    if (Character.isWhitespace(a)) {
        val before = lastNonSpaceBefore(s, p)
        if (before != null && Character.isDigit(before) && Character.isLetter(b)) return null // 數字和單位不拆開（「30 / Mbps」「514 / 天」）
        return particleFirst + when {
            before == '·'.code || before == '・'.code || before == '•'.code -> 0.5f // 行尾留一個「·」
            before != null && before.isOneOf(PREPOSITIONS) -> 0.5f // 「給 / 小雨」「和 / 小雨」
            else -> 0f
        }
    }
    if (noLineEnd(a)) return null
    val la = isLatinish(a)
    val lb = isLatinish(b)
    if (la && lb) return null
    if (isPunctuation(a) || isOpening(b)) return 0f
    val ca = isCjkLetter(a)
    val cb = isCjkLetter(b)
    return particleFirst + when {
        ca && cb && (a.isOneOf(WORD_END) || b.isOneOf(WORD_START)) -> 0.6f // 「對方的 / 電量」「抵達 / 與離開」
        ca && cb -> 1.5f
        (ca && lb) || (la && cb) -> 0.5f
        else -> 0.3f
    }
}

/** 後面通常是詞的分界：的、了、們 */
private const val WORD_END = "的了們"

/** 前面通常是詞的分界：連接詞 */
private const val WORD_START = "和與及或跟"

/** 和後面的名字是一起的：給、和、在… */
private const val PREPOSITIONS = "給和與跟對在向讓被把從"

/** 不適合放在行首的語助詞 */
private const val PARTICLES = "的了們嗎呢吧"

private fun Int.isOneOf(chars: String) = this < 0x10000 && chars.indexOf(toChar()) >= 0

/**
 * 從 [spots] 裡挑換行的地方，排成最多 [lines] 行、每行不超過 [maxWidth]（[x] 是每個位置在一行裡的橫向位置）。
 * 代價：每個換行點的代價、非最後一行的空白（越空越貴；行數多的段落更在意塞滿）、最後一行孤字或太短，
 * 還有不是一般 Text 會換的地方（[usual]）多一點代價：真的比較好才改，兩種都會拆詞時就照大家習慣的換法。
 * 回傳第 2 行起每行的開頭；排不下就是 null。
 */
internal fun planLines(text: String, spots: BreakSpots, x: (Int) -> Float, maxWidth: Float, lines: Int, usual: IntArray = IntArray(0)): IntArray? {
    if (lines < 2 || maxWidth <= 0f) return null
    val pos = IntArray(spots.at.size + 2)
    spots.at.copyInto(pos, 1)
    pos[pos.lastIndex] = text.length
    val m = pos.size
    val fill = when { lines <= 2 -> 4f; lines == 3 -> 8f; else -> 30f }
    fun width(i: Int, j: Int): Float {
        var e = pos[j]
        while (e > pos[i] && text[e - 1].isWhitespace()) e--
        return x(e) - x(pos[i])
    }
    fun lastLine(i: Int): Float {
        val rest = text.substring(pos[i])
        var c = when {
            isOrphan(rest) -> 2f
            wordsIn(rest) == 1 -> 0.8f // 只剩一個英文字
            else -> 0f
        }
        val frac = width(i, m - 1) / maxWidth
        if (frac < 1 / 3f) c += (1 / 3f - frac) * 3
        return c
    }
    val none = Float.POSITIVE_INFINITY
    val best = Array(lines + 1) { FloatArray(m) { none } }
    val from = Array(lines + 1) { IntArray(m) { -1 } }
    best[0][0] = 0f
    for (l in 1..lines) for (j in 1 until m) {
        val last = j == m - 1
        if (!last && l == lines) continue
        for (i in j - 1 downTo 0) {
            val w = width(i, j)
            if (w > maxWidth) break
            if (best[l - 1][i] == none) continue
            val slack = 1 - w / maxWidth
            val c = best[l - 1][i] + if (last) lastLine(i) else fill * slack * slack + spots.cost[j - 1] + if (pos[j] in usual) 0f else MOVED
            if (c < best[l][j]) { best[l][j] = c; from[l][j] = i }
        }
    }
    val l = (2..lines).filter { best[it][m - 1] < none }.minByOrNull { best[it][m - 1] } ?: return null
    val starts = ArrayList<Int>()
    var j = m - 1
    for (k in l downTo 1) {
        val i = from[k][j]
        if (i > 0) starts += pos[i]
        j = i
    }
    return starts.reversed().toIntArray()
}

/** 換到不是一般 Text 會換的地方的代價 */
private const val MOVED = 0.35f

/** 最後一行是孤字：只有一個中文字，或只有標點、表情 */
internal fun isOrphan(line: String): Boolean {
    val words = wordsIn(line)
    return words == 0 || (words == 1 && line.any { isCjkLetter(it.code) })
}

/** 幾個「字」：中日韓文字一字算一個，連在一起的英文字母、數字算一個，空白、標點、表情不算 */
internal fun wordsIn(s: String): Int {
    var n = 0
    var inWord = false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when {
            !Character.isLetterOrDigit(cp) -> inWord = false
            isCjkLetter(cp) -> { n++; inWord = false }
            !inWord -> { n++; inWord = true }
        }
        i += Character.charCount(cp)
    }
    return n
}

private fun isCjkLetter(cp: Int): Boolean {
    if (Character.isIdeographic(cp)) return true
    if (!Character.isLetter(cp)) return false
    val script = Character.UnicodeScript.of(cp)
    return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
        script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.BOPOMOFO
}

/** 英文字母、數字、半形符號這類：兩個連在一起時不能拆開（「Wi-Fi」「10/22」「2.4」「18:30」） */
private fun isLatinish(cp: Int) = cp < 0x2000 && !Character.isWhitespace(cp) && Character.getType(cp) != Character.OTHER_SYMBOL.toInt()

private fun isPunctuation(cp: Int) = when (Character.getType(cp).toByte()) {
    Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.DASH_PUNCTUATION -> true
    else -> false
}

private fun isOpening(cp: Int) = when (Character.getType(cp).toByte()) {
    Character.START_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION -> true
    else -> false
}

private fun isClosing(cp: Int) = when (Character.getType(cp).toByte()) {
    Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION -> true
    else -> false
}

/** 不能放在行首：結尾的括號、引號，逗號、句號這類標點，長音、小的假名 */
private fun noLineStart(cp: Int) = when (Character.getType(cp).toByte()) {
    Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
    else -> cp.isOneOf(NO_START)
}

private const val NO_START = "ー〜～%‰ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"

/** 不能放在行尾：開頭的括號、引號 */
private fun noLineEnd(cp: Int) = isOpening(cp)

/** 不換行空格、連字號這類：故意黏在一起的 */
private fun isGlue(cp: Int) = cp == 0x00A0 || cp == 0x202F || cp == 0x2060 || cp == 0xFEFF || cp == 0x2011

/** 同一個字（表情、組合符號）的中間：變體選擇符、膚色、零寬連接符、國旗的兩半 */
private fun isJoining(a: Int, b: Int): Boolean = when {
    a == 0x200D || b == 0x200D -> true
    b in 0x1F3FB..0x1F3FF || b in 0xE0020..0xE007F -> true
    a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF -> true
    else -> when (Character.getType(b).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.FORMAT -> true
        else -> false
    }
}

private fun lastNonSpaceBefore(s: String, p: Int): Int? {
    var i = p
    while (i > 0) {
        val cp = s.codePointBefore(i)
        if (!Character.isWhitespace(cp)) return cp
        i -= Character.charCount(cp)
    }
    return null
}
