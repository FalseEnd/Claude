package tw.xiaoliangkou.app.ui.components

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original little illustrations, shared by the app and bitmap surfaces. The old emoji strings
 * remain storage identifiers only: drawing never relies on a device's emoji font.
 * All geometry is antialiased, with rounded cocoa outlines and a small, consistent palette.
 */
@Composable
fun IllustratedIcon(symbol: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Canvas(
        modifier.size(24.dp).then(
            if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription },
        ),
    ) {
        val side = minOf(size.width, size.height)
        drawIntoCanvas { drawIllustratedSymbol(it.nativeCanvas, symbol, (size.width - side) / 2f, (size.height - side) / 2f, side) }
    }
}

/** Draw into an existing bitmap, including avatars on maps, notifications and home widgets. */
fun drawIllustratedSymbol(canvas: AndroidCanvas, symbol: String, left: Float, top: Float, size: Float) {
    if (size <= 0f) return
    val save = canvas.save()
    canvas.translate(left, top)
    canvas.scale(size / 100f, size / 100f)
    LittleIllustration(canvas).draw(symbol.replace("\uFE0F", "").trim())
    canvas.restoreToCount(save)
}

private class LittleIllustration(private val c: AndroidCanvas) {
    private val ink = 0xFF70534D.toInt()
    private val cream = 0xFFFFF4DD.toInt()
    private val pink = 0xFFF2A7B5.toInt()
    private val rose = 0xFFD76B87.toInt()
    private val peach = 0xFFF5BE91.toInt()
    private val butter = 0xFFF4D27D.toInt()
    private val mint = 0xFFA8CDBA.toInt()
    private val blue = 0xFFAACEDD.toInt()
    private val lilac = 0xFFC5B6DF.toInt()
    private val paper = 0xFFFFFCF5.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private fun path(color: Int? = null, outline: Boolean = true, width: Float = 3.3f, block: Path.() -> Unit) {
        val shape = Path().apply(block)
        if (color != null) { p.color = color; p.style = Paint.Style.FILL; c.drawPath(shape, p) }
        if (outline) { p.color = ink; p.style = Paint.Style.STROKE; p.strokeWidth = width; c.drawPath(shape, p) }
    }

    private fun oval(x: Float, y: Float, w: Float, h: Float, color: Int, outline: Boolean = false) {
        p.style = Paint.Style.FILL; p.color = color; c.drawOval(x, y, x + w, y + h, p)
        if (outline) { p.style = Paint.Style.STROKE; p.color = ink; p.strokeWidth = 3.3f; c.drawOval(x, y, x + w, y + h, p) }
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 8f, outline: Boolean = true) {
        p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(x, y, x + w, y + h, radius, radius, p)
        if (outline) { p.style = Paint.Style.STROKE; p.color = ink; p.strokeWidth = 3.3f; c.drawRoundRect(x, y, x + w, y + h, radius, radius, p) }
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = ink, width: Float = 3.3f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width; c.drawLine(x1, y1, x2, y2, p)
    }

    private fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float, color: Int = ink, width: Float = 3.3f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width
        c.drawArc(RectF(x, y, x + w, y + h), start, sweep, false, p)
    }

    private fun eyes(y: Float = 48f, left: Float = 38f, right: Float = 62f) {
        oval(left - 2.4f, y - 3.3f, 4.8f, 6.6f, ink)
        oval(right - 2.4f, y - 3.3f, 4.8f, 6.6f, ink)
    }

    private fun blush(y: Float = 61f) { oval(23f, y - 4f, 14f, 8f, pink); oval(63f, y - 4f, 14f, 8f, pink) }
    private fun smile(y: Float = 57f) = arc(43f, y, 14f, 10f, 5f, 170f)
    private fun shadow() = oval(18f, 85f, 64f, 6f, 0x1470534D)

    private fun heart(x: Float = 14f, y: Float = 18f, size: Float = 72f, color: Int = pink, outline: Boolean = true) {
        path(color, outline) {
            moveTo(x + size * .5f, y + size * .9f)
            cubicTo(x + size * .4f, y + size * .78f, x, y + size * .52f, x, y + size * .27f)
            cubicTo(x, y - size * .01f, x + size * .35f, y - size * .08f, x + size * .5f, y + size * .18f)
            cubicTo(x + size * .66f, y - size * .08f, x + size, y - size * .01f, x + size, y + size * .27f)
            cubicTo(x + size, y + size * .52f, x + size * .6f, y + size * .78f, x + size * .5f, y + size * .9f)
            close()
        }
    }

    private fun star(x: Float = 50f, y: Float = 48f, r: Float = 34f, color: Int = butter) {
        path(color) {
            repeat(10) { i ->
                val angle = (-90.0 + i * 36.0) * Math.PI / 180
                val radius = if (i % 2 == 0) r else r * .49f
                val xx = x + cos(angle).toFloat() * radius
                val yy = y + sin(angle).toFloat() * radius
                if (i == 0) moveTo(xx, yy) else lineTo(xx, yy)
            }
            close()
        }
    }

    private fun sparkle(x: Float, y: Float, r: Float = 7f) {
        line(x - r, y, x + r, y, rose, 2.6f); line(x, y - r, x, y + r, rose, 2.6f)
    }

    fun draw(s: String) {
        when (s) {
            "😊", "🙂", "🥰", "🤩", "😂", "😌", "🥺", "🙈", "😴", "🤤", "😑", "😵", "😩", "😰", "😖", "😣", "😢", "😠", "😤", "😔", "🤒", "😥", "😆", "🤔", "😋", "😜", "😘", "🤡", "🥶", "🥵", "🤧", "😷", "🫥", "🙅", "🤫", "😻", "😺", "😿" -> face(s)
            "🐰", "🐻", "🐱", "🐶", "🦊", "🐼", "🐨", "🐯", "🐹", "🐷", "🐥", "🐧", "🦄", "🐸", "🐮", "🐵", "🐣", "🐈", "🧸" -> animal(s)
            "❤", "♥", "🤍", "💗", "💖", "💕", "💞", "💝", "💔", "💑", "👫", "heart", "love" -> {
                shadow(); heart(); if (s == "💔") path(width = 3.6f) { moveTo(52f, 27f); lineTo(43f, 45f); lineTo(57f, 54f); lineTo(48f, 73f) }
                else { line(28f, 33f, 32f, 29f, paper, 4f); if (s == "💝") { line(50f, 23f, 50f, 78f, paper, 6f); line(22f, 47f, 78f, 47f, paper, 6f) } }
            }
            "🤗", "💆", "🤝", "🤚", "✋", "👋", "🙏", "hug", "pat" -> hug(s)
            "✨", "🌟", "⭐" -> { shadow(); star(); eyes(47f, 41f, 59f); smile(53f); sparkle(84f, 22f, 5f) }
            "💡" -> lightbulb()
            "🌱", "🍀" -> sprout(s == "🍀")
            "🧠" -> brain()
            "🔮" -> crystalBall()
            "❔", "❓", "❕" -> question()
            "🧲" -> magnet()
            "👀" -> lookingEyes()
            "💬", "💭", "💌", "📭", "🔔", "🔕", "here" -> message(s)
            "📍", "📌", "pin", "📡" -> pin()
            "🔗" -> chain()
            "☀", "🌤", "⛅", "☁", "🌦", "🌧", "🌨", "⛈", "🌫", "weather" -> weather(s)
            "🌬" -> wind()
            "🌈" -> rainbow()
            "🌙", "🌃", "💤", "🛌", "night" -> moon()
            "💧", "💦", "🩸", "water" -> drop(if (s == "🩸") pink else blue)
            "☔", "🌂", "umbrella" -> umbrella()
            "🌡" -> thermometer(s)
            "🔥" -> flame()
            "🧊" -> ice()
            "☕", "🍵", "🥤", "🧋", "🥛", "🍼", "drink", "cafe" -> drink(s)
            "🍜", "🍲", "🍚", "🍱", "🥣", "🍽", "food", "meal" -> bowl(s)
            "🥫", "can" -> can()
            "🍰", "🎂", "🍓", "🥚", "🍗", "🥟", "dessert" -> cake(s)
            "🎁", "gift", "📦" -> gift()
            "💐", "🌸", "🌹", "🍃", "🍁", "flower" -> flower(s)
            "📔", "📝", "📜", "📅", "🗓", "🧾", "diary", "calendar", "memo" -> notebook(s)
            "📱", "📵", "📞", "📳", "phone" -> phone(s)
            "🔋", "🪫", "⚡", "battery" -> battery(s)
            "🔒", "🔐", "🔑", "lock" -> lock(s)
            "🛡" -> shield()
            "🏠", "🏫", "home", "school" -> home(s)
            "work", "💼" -> briefcase()
            "⛩" -> torii()
            "🛍", "🛒", "🎒", "shop", "shopping" -> shopping()
            "🚗", "🚇", "✈", "🚶", "transport" -> transport(s)
            "🧣", "🧥", "👕", "warm" -> scarf()
            "🐾", "👣", "👟", "steps" -> paws()
            "💰", "💳", "money" -> wallet()
            "⚖" -> scale()
            "📷", "📸", "🖼", "camera" -> camera(s)
            "🎙", "👂", "listen" -> microphone()
            "🧶", "yarn" -> yarn()
            "🎣", "🐟", "fish" -> fish()
            "🧪", "🧴", "🛁", "🫧", "🧻", "🧺" -> bottle(s)
            "🎀", "bow" -> bow()
            "💍" -> ring()
            "🎈" -> balloon()
            "👆" -> fingerprint()
            "👑", "🏆", "🏁", "🎉", "🥳", "💪", "👍", "🏋", "crown" -> crown(s)
            "🎬", "🎨", "🎭", "fun" -> play(s)
            "🎲" -> dice()
            "✓", "✅", "⬜", "🟢", "🟠", "⛔", "⚠", "🔴" -> check(s)
            "📶", "signal" -> signal()
            "⏳", "⏰" -> clock(s)
            "⏭", "⏩", "▶", "⏸", "⏹", "🔁" -> mediaControl(s)
            "✍", "✏", "🔍", "🔢" -> pencil(s)
            "🤖" -> robot()
            "🛠" -> tools()
            "📏" -> ruler()
            "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓" -> zodiac(s)
            else -> { shadow(); star(); eyes(47f, 41f, 59f); smile(53f) }
        }
    }

    private fun face(s: String) {
        shadow()
        val fill = when (s) { "🥶" -> blue; "😠", "😤", "🥵" -> peach; "😷", "🤒", "🤧" -> mint; else -> cream }
        path(fill) { moveTo(18f, 48f); cubicTo(16f, 25f, 31f, 16f, 50f, 16f); cubicTo(71f, 16f, 85f, 28f, 83f, 51f); cubicTo(86f, 77f, 69f, 85f, 50f, 84f); cubicTo(29f, 85f, 15f, 75f, 18f, 48f); close() }
        blush()
        when (s) {
            "🥰", "😻" -> { heart(29f, 39f, 17f, rose, false); heart(55f, 39f, 17f, rose, false); smile(61f); heart(79f, 12f, 14f, pink, false) }
            "🤩" -> { star(37f, 47f, 10f); star(63f, 47f, 10f); arc(41f, 58f, 18f, 15f, 0f, 180f) }
            "😊", "😌", "😂", "😆", "😺" -> {
                arc(30f, 40f, 14f, 13f, 190f, 160f); arc(56f, 40f, 14f, 13f, 190f, 160f)
                if (s in setOf("😂", "😆")) { path(rose) { moveTo(39f, 60f); lineTo(61f, 60f); cubicTo(61f, 78f, 39f, 78f, 39f, 60f); close() }; if (s == "😂") { tear(22f, 49f); tear(72f, 49f) } }
                else smile()
            }
            "🥺", "😢", "😿", "😥" -> {
                oval(29f, 39f, 13f, 17f, ink); oval(58f, 39f, 13f, 17f, ink)
                oval(31f, 41f, 4f, 5f, paper); oval(60f, 41f, 4f, 5f, paper)
                arc(43f, 63f, 14f, 10f, 190f, 160f)
                if (s != "🥺") tear(67f, 55f)
            }
            "😴", "😔", "😑", "😩", "🫥" -> {
                line(30f, 47f, 42f, 48f); line(58f, 48f, 70f, 47f)
                if (s == "😴") { oval(46f, 61f, 9f, 12f, ink); sparkle(84f, 18f, 6f) }
                else if (s == "😩") oval(43f, 61f, 14f, 9f, rose, true)
                else line(45f, 66f, 55f, 66f)
            }
            "😵" -> {
                for (x in listOf(37f, 63f)) { line(x - 5f, 43f, x + 5f, 53f); line(x + 5f, 43f, x - 5f, 53f) }
                oval(45f, 64f, 10f, 9f, ink); sparkle(85f, 22f, 6f)
            }
            "😠", "😤", "😖", "😣" -> {
                if (s in setOf("😖", "😣")) { path { moveTo(32f, 40f); lineTo(41f, 47f); lineTo(32f, 53f) }; path { moveTo(68f, 40f); lineTo(59f, 47f); lineTo(68f, 53f) } }
                else { eyes(); line(29f, 36f, 42f, 40f); line(58f, 40f, 71f, 36f) }
                arc(43f, 63f, 14f, 10f, 190f, 160f)
                if (s == "😤") { oval(13f, 57f, 12f, 8f, paper, true); oval(75f, 57f, 12f, 8f, paper, true) }
            }
            "😰", "🥵", "🥶" -> { eyes(); oval(45f, 62f, 10f, 11f, ink); tear(74f, 29f); line(30f, 35f, 40f, 32f); line(60f, 32f, 70f, 35f) }
            "🤒", "🤧", "😷" -> {
                line(31f, 47f, 42f, 47f); line(58f, 47f, 69f, 47f)
                if (s == "😷") { rect(31f, 57f, 38f, 21f, paper, 7f); line(20f, 58f, 31f, 63f); line(69f, 63f, 80f, 58f); line(38f, 64f, 62f, 64f, blue, 2f); line(38f, 70f, 62f, 70f, blue, 2f) }
                else { smile(61f); line(54f, 68f, 74f, 77f, paper, 7f); oval(69f, 73f, 8f, 8f, rose, true) }
            }
            "🙈", "🤫" -> { eyes(); smile(61f); oval(22f, 42f, 18f, 26f, peach, true); oval(60f, 42f, 18f, 26f, peach, true) }
            "😜", "😋", "🤤", "😘" -> {
                eyes(); if (s == "😘") { arc(32f, 41f, 12f, 12f, 190f, 160f); heart(72f, 54f, 18f, pink, false) }
                else { arc(40f, 57f, 20f, 15f, 0f, 180f); oval(49f, 64f, 9f, 11f, rose, true) }
                if (s == "🤤") tear(57f, 73f)
            }
            "🤡" -> { eyes(); oval(43f, 51f, 14f, 12f, rose, true); arc(33f, 58f, 34f, 20f, 10f, 160f); star(50f, 11f, 11f, lilac) }
            "🤔" -> { eyes(); line(30f, 35f, 42f, 32f); oval(52f, 65f, 20f, 13f, peach, true) }
            else -> { eyes(); smile() }
        }
    }

    private fun tear(x: Float, y: Float) = path(blue, false) {
        moveTo(x + 5f, y); cubicTo(x + 1f, y + 5f, x - 2f, y + 9f, x + 1f, y + 13f)
        cubicTo(x + 8f, y + 19f, x + 16f, y + 10f, x + 5f, y); close()
    }

    private fun animal(s: String) {
        shadow()
        val color = when (s) { "🐻", "🧸", "🐵" -> peach; "🦊", "🐯" -> peach; "🐨" -> blue; "🐷" -> pink; "🐸" -> mint; "🐥", "🐣" -> butter; else -> cream }
        when (s) {
            "🐰" -> { oval(24f, 5f, 20f, 48f, cream, true); oval(56f, 5f, 20f, 48f, cream, true); oval(30f, 12f, 8f, 29f, pink); oval(62f, 12f, 8f, 29f, pink) }
            "🐱", "🐈", "🦊", "🐯" -> { path(color) { moveTo(19f, 43f); lineTo(21f, 13f); quadTo(36f, 17f, 43f, 34f); close() }; path(color) { moveTo(57f, 34f); quadTo(64f, 17f, 79f, 13f); lineTo(81f, 43f); close() }; path(pink, false) { moveTo(25f, 35f); lineTo(26f, 22f); lineTo(36f, 36f); close() }; path(pink, false) { moveTo(64f, 36f); lineTo(74f, 22f); lineTo(75f, 35f); close() } }
            "🐶" -> { oval(11f, 23f, 25f, 49f, peach, true); oval(64f, 23f, 25f, 49f, peach, true) }
            "🐮" -> { path(butter) { moveTo(26f, 34f); quadTo(15f, 24f, 23f, 12f); quadTo(28f, 22f, 34f, 23f); close() }; path(butter) { moveTo(66f, 23f); quadTo(72f, 22f, 77f, 12f); quadTo(85f, 24f, 74f, 34f); close() } }
            "🦄" -> { path(butter) { moveTo(42f, 31f); lineTo(50f, 4f); lineTo(58f, 31f); close() }; oval(17f, 23f, 23f, 24f, lilac, true); oval(60f, 23f, 23f, 24f, lilac, true) }
            "🐥", "🐣", "🐧", "🐸" -> Unit
            else -> { val ears = if (s == "🐼") ink else color; oval(15f, 20f, 27f, 28f, ears, true); oval(58f, 20f, 27f, 28f, ears, true); oval(22f, 26f, 13f, 14f, pink); oval(65f, 26f, 13f, 14f, pink) }
        }
        path(if (s == "🐧") ink else color) { moveTo(17f, 51f); cubicTo(17f, 31f, 30f, 27f, 50f, 27f); cubicTo(70f, 27f, 83f, 31f, 83f, 51f); cubicTo(89f, 76f, 72f, 87f, 50f, 86f); cubicTo(28f, 87f, 11f, 76f, 17f, 51f); close() }
        when (s) {
            "🐼" -> { oval(26f, 42f, 20f, 23f, ink); oval(54f, 42f, 20f, 23f, ink); oval(33f, 47f, 6f, 8f, paper); oval(61f, 47f, 6f, 8f, paper) }
            "🐧" -> { oval(27f, 40f, 46f, 40f, paper); eyes(53f, 39f, 61f) }
            "🐵" -> { oval(25f, 38f, 50f, 43f, cream); eyes(53f) }
            "🐸" -> { oval(24f, 27f, 22f, 25f, mint, true); oval(54f, 27f, 22f, 25f, mint, true); eyes(38f, 35f, 65f) }
            "🐯" -> { eyes(53f); line(45f, 30f, 47f, 39f); line(55f, 30f, 53f, 39f); line(19f, 52f, 26f, 55f); line(19f, 64f, 26f, 63f); line(81f, 52f, 74f, 55f); line(81f, 64f, 74f, 63f) }
            "🐮" -> { oval(23f, 40f, 18f, 22f, ink); oval(59f, 40f, 18f, 22f, ink); oval(31f, 45f, 5f, 7f, paper); oval(64f, 45f, 5f, 7f, paper) }
            else -> eyes(53f)
        }
        blush(66f)
        when (s) {
            "🐷", "🐮" -> { oval(34f, 61f, 32f, 20f, pink, true); oval(41f, 68f, 4f, 6f, ink); oval(55f, 68f, 4f, 6f, ink) }
            "🐥", "🐣", "🐧" -> path(peach) { moveTo(43f, 61f); lineTo(57f, 61f); lineTo(50f, 69f); close() }
            "🐨" -> { oval(43f, 55f, 14f, 21f, ink); arc(37f, 68f, 26f, 11f, 15f, 150f) }
            "🐸" -> arc(36f, 56f, 28f, 15f, 10f, 160f)
            else -> { path(rose, false) { moveTo(45f, 63f); quadTo(50f, 59f, 55f, 63f); quadTo(51f, 70f, 50f, 68f); quadTo(49f, 70f, 45f, 63f); close() }; arc(39f, 65f, 11f, 11f, 0f, 165f); arc(50f, 65f, 11f, 11f, 15f, 165f) }
        }
        if (s in setOf("🐱", "🐈", "🦊", "🐯")) { line(13f, 62f, 27f, 65f, ink, 2f); line(13f, 71f, 27f, 69f, ink, 2f); line(73f, 65f, 87f, 62f, ink, 2f); line(73f, 69f, 87f, 71f, ink, 2f) }
    }

    private fun hug(s: String) {
        shadow(); heart(24f, 27f, 52f)
        path(cream) { moveTo(30f, 35f); cubicTo(18f, 24f, 9f, 33f, 15f, 44f); lineTo(32f, 68f); quadTo(44f, 80f, 54f, 69f); quadTo(55f, 64f, 47f, 60f); lineTo(35f, 52f); quadTo(42f, 48f, 37f, 42f); close() }
        path(cream) { moveTo(70f, 35f); cubicTo(82f, 24f, 91f, 33f, 85f, 44f); lineTo(68f, 68f); quadTo(56f, 80f, 46f, 69f); quadTo(45f, 64f, 53f, 60f); lineTo(65f, 52f); quadTo(58f, 48f, 63f, 42f); close() }
        if (s == "👋") sparkle(81f, 16f)
    }

    private fun message(s: String) {
        shadow()
        if (s in setOf("💌", "📭")) {
            rect(13f, 27f, 74f, 51f, cream, 9f); path { moveTo(15f, 32f); lineTo(50f, 59f); lineTo(85f, 32f) }; heart(39f, 42f, 22f, pink, false)
        } else if (s in setOf("🔔", "🔕")) {
            path(butter) { moveTo(24f, 66f); lineTo(29f, 58f); lineTo(29f, 41f); cubicTo(29f, 15f, 71f, 15f, 71f, 41f); lineTo(71f, 58f); lineTo(76f, 66f); close() }; oval(43f, 72f, 14f, 12f, peach, true); if (s == "🔕") line(17f, 18f, 83f, 82f, rose, 5f)
        } else {
            path(cream) { moveTo(31f, 76f); lineTo(15f, 83f); lineTo(20f, 63f); cubicTo(-4f, 26f, 24f, 13f, 50f, 15f); cubicTo(91f, 12f, 103f, 61f, 75f, 73f); quadTo(54f, 83f, 31f, 76f); close() }; heart(34f, 32f, 32f, pink, false)
        }
    }

    private fun pin() {
        shadow(); path(pink) { moveTo(50f, 85f); cubicTo(36f, 68f, 20f, 53f, 22f, 36f); cubicTo(24f, 6f, 76f, 6f, 78f, 36f); cubicTo(80f, 53f, 64f, 68f, 50f, 85f); close() }; oval(36f, 25f, 28f, 28f, cream, true); heart(42f, 33f, 16f, rose, false)
    }

    private fun weather(s: String) {
        val cloud = s !in setOf("☀", "🌈")
        if (s != "☁" && s !in setOf("🌧", "🌨", "⛈", "🌫", "🌬")) {
            for (i in 0..7) { val a = i * Math.PI / 4; line(39f + cos(a).toFloat() * 23f, 36f + sin(a).toFloat() * 23f, 39f + cos(a).toFloat() * 30f, 36f + sin(a).toFloat() * 30f, ink, 3f) }
            oval(21f, 18f, 36f, 36f, butter, true)
        }
        if (!cloud) { eyes(36f, 33f, 45f); smile(40f); return }
        path(paper) { moveTo(25f, 72f); cubicTo(5f, 72f, 3f, 46f, 25f, 44f); cubicTo(27f, 19f, 61f, 18f, 66f, 43f); cubicTo(90f, 37f, 99f, 71f, 77f, 72f); close() }
        oval(39f, 52f, 4f, 6f, ink); oval(58f, 52f, 4f, 6f, ink); arc(46f, 58f, 10f, 7f, 0f, 180f, ink, 2.6f)
        if (s in setOf("🌦", "🌧", "⛈")) { for (x in listOf(28f, 49f, 70f)) line(x, 80f, x - 4f, 88f, blue, 4.5f) }
        if (s == "🌨") {
            for (x in listOf(27f, 50f, 73f)) {
                line(x - 4f, 84f, x + 4f, 84f, blue, 2.5f)
                line(x - 2f, 80f, x + 2f, 88f, blue, 2.5f)
                line(x + 2f, 80f, x - 2f, 88f, blue, 2.5f)
            }
        }
        if (s == "⛈") path(butter) { moveTo(49f, 68f); lineTo(41f, 82f); lineTo(50f, 82f); lineTo(45f, 94f); lineTo(61f, 76f); lineTo(52f, 76f); close() }
        if (s == "🌫") { line(18f, 81f, 80f, 81f, blue); line(28f, 89f, 72f, 89f, blue) }
    }

    private fun moon() {
        shadow(); path(butter) { moveTo(72f, 16f); cubicTo(29f, 1f, 7f, 44f, 26f, 71f); cubicTo(43f, 95f, 79f, 84f, 88f, 60f); cubicTo(64f, 70f, 45f, 55f, 47f, 35f); quadTo(50f, 20f, 72f, 16f); close() }; arc(27f, 49f, 10f, 10f, 0f, 180f, ink, 2.5f); sparkle(78f, 26f); sparkle(65f, 42f, 4f)
    }

    private fun drop(color: Int) {
        shadow(); path(color) { moveTo(50f, 12f); cubicTo(40f, 28f, 18f, 50f, 22f, 66f); cubicTo(27f, 97f, 73f, 97f, 78f, 66f); cubicTo(82f, 50f, 60f, 28f, 50f, 12f); close() }; line(33f, 52f, 29f, 61f, paper, 5f); eyes(62f, 40f, 60f); smile(68f)
    }

    private fun umbrella() {
        shadow(); path(pink) { moveTo(12f, 49f); cubicTo(18f, 5f, 82f, 5f, 88f, 49f); quadTo(75f, 38f, 63f, 49f); quadTo(50f, 37f, 37f, 49f); quadTo(25f, 38f, 12f, 49f); close() }; path { moveTo(50f, 49f); lineTo(50f, 76f); cubicTo(50f, 92f, 72f, 89f, 71f, 75f) }; path(width = 2f) { moveTo(50f, 18f); quadTo(36f, 29f, 37f, 48f) }; path(width = 2f) { moveTo(50f, 18f); quadTo(64f, 29f, 63f, 48f) }; line(50f, 10f, 50f, 16f)
    }

    private fun thermometer(s: String) {
        shadow(); rect(39f, 12f, 22f, 58f, cream, 11f); oval(31f, 57f, 38f, 33f, cream, true); line(50f, 36f, 50f, 71f, if (s == "🧊") blue else rose, 9f); oval(40f, 64f, 20f, 17f, if (s == "🧊") blue else rose); line(64f, 27f, 72f, 27f); line(64f, 40f, 70f, 40f); sparkle(21f, 26f, 5f)
    }

    private fun drink(s: String) {
        shadow()
        if (s in setOf("☕", "🍵", "cafe")) {
            path(cream) { moveTo(20f, 37f); lineTo(72f, 37f); lineTo(68f, 68f); cubicTo(65f, 84f, 27f, 84f, 24f, 68f); close() }; arc(62f, 40f, 26f, 25f, 270f, 180f); line(18f, 85f, 77f, 85f); path(width = 2.5f) { moveTo(37f, 27f); cubicTo(28f, 21f, 43f, 17f, 36f, 11f) }; path(width = 2.5f) { moveTo(53f, 27f); cubicTo(44f, 21f, 59f, 17f, 52f, 11f) }; heart(36f, 49f, 20f, pink, false)
        } else {
            path(cream) { moveTo(25f, 28f); lineTo(75f, 28f); lineTo(68f, 83f); quadTo(50f, 88f, 32f, 83f); close() }; path(peach, false) { moveTo(29f, 49f); lineTo(71f, 49f); lineTo(66f, 80f); lineTo(34f, 80f); close() }; rect(21f, 25f, 58f, 10f, pink, 4f); path { moveTo(52f, 27f); lineTo(62f, 10f); lineTo(76f, 10f) }; for (x in listOf(39f, 50f, 61f)) oval(x - 3f, 71f, 6f, 6f, ink); eyes(51f, 40f, 60f); smile(57f)
        }
    }

    private fun bowl(s: String) {
        shadow(); path(cream) { moveTo(13f, 45f); lineTo(87f, 45f); cubicTo(82f, 89f, 18f, 89f, 13f, 45f); close() }; oval(13f, 36f, 74f, 19f, butter, true); path(paper) { moveTo(28f, 40f); cubicTo(18f, 24f, 35f, 21f, 41f, 30f); cubicTo(45f, 9f, 65f, 16f, 66f, 29f); cubicTo(85f, 25f, 86f, 42f, 71f, 44f); close() }; line(63f, 30f, 82f, 13f); line(69f, 34f, 88f, 17f); eyes(61f, 40f, 60f); smile(66f)
    }

    private fun cake(s: String) {
        shadow(); rect(18f, 42f, 64f, 39f, peach, 7f); rect(18f, 59f, 64f, 10f, pink, 1f, false); path(paper) { moveTo(18f, 42f); lineTo(82f, 42f); lineTo(82f, 52f); quadTo(74f, 61f, 66f, 52f); quadTo(58f, 61f, 50f, 52f); quadTo(42f, 61f, 34f, 52f); quadTo(26f, 61f, 18f, 52f); close() }; oval(40f, 26f, 20f, 17f, rose, true); path(mint) { moveTo(49f, 29f); quadTo(43f, 18f, 36f, 23f); quadTo(40f, 33f, 49f, 29f); close() }; line(50f, 28f, 53f, 18f); if (s == "🎂") { line(29f, 37f, 29f, 20f, lilac, 5f); path(butter) { moveTo(28f, 18f); quadTo(20f, 10f, 29f, 5f); quadTo(36f, 14f, 28f, 18f); close() } }
    }

    private fun gift() {
        shadow(); rect(20f, 42f, 60f, 42f, pink, 6f); rect(15f, 32f, 70f, 17f, pink, 5f); rect(44f, 33f, 12f, 51f, cream, 1f, false); path(cream) { moveTo(49f, 31f); cubicTo(18f, 29f, 21f, 7f, 35f, 14f); quadTo(45f, 18f, 49f, 31f); close() }; path(cream) { moveTo(51f, 31f); cubicTo(82f, 29f, 79f, 7f, 65f, 14f); quadTo(55f, 18f, 51f, 31f); close() }; line(50f, 34f, 50f, 82f, ink, 2f)
    }

    private fun flower(s: String) {
        shadow(); path(mint) { moveTo(50f, 86f); lineTo(50f, 53f); quadTo(25f, 51f, 25f, 66f); quadTo(41f, 75f, 50f, 65f); quadTo(61f, 47f, 77f, 58f); quadTo(71f, 73f, 50f, 74f); close() }
        for (i in 0..4) { val a = (-90 + i * 72) * Math.PI / 180; oval(38f + cos(a).toFloat() * 18f, 22f + sin(a).toFloat() * 18f, 24f, 24f, pink, true) }; oval(40f, 24f, 20f, 20f, butter, true)
    }

    private fun notebook(s: String) {
        shadow(); val calendar = s in setOf("📅", "🗓", "calendar"); rect(18f, 20f, 64f, 65f, cream, 9f)
        if (calendar) { rect(18f, 20f, 64f, 21f, pink, 8f, false); line(19f, 41f, 81f, 41f); line(34f, 13f, 34f, 27f, ink, 5f); line(66f, 13f, 66f, 27f, ink, 5f); heart(37f, 50f, 27f, pink, false) }
        else { rect(18f, 20f, 14f, 65f, lilac, 5f, false); line(32f, 22f, 32f, 83f); heart(43f, 36f, 26f, pink, false); line(43f, 71f, 68f, 71f, ink, 2.6f) }
    }

    private fun phone(s: String) {
        shadow(); rect(29f, 10f, 42f, 78f, lilac, 10f); rect(34f, 21f, 32f, 51f, paper, 4f, false); line(44f, 16f, 56f, 16f, ink, 2f); oval(47f, 78f, 6f, 5f, paper); heart(39f, 34f, 23f, pink, false); if (s == "📵") line(18f, 23f, 82f, 76f, rose, 5f); if (s == "📳") { line(19f, 37f, 15f, 48f); line(81f, 37f, 85f, 48f) }
    }

    private fun battery(s: String) {
        shadow(); rect(12f, 29f, 69f, 43f, cream, 9f); rect(82f, 42f, 7f, 17f, peach, 3f); rect(19f, 36f, if (s == "🪫") 13f else 54f, 29f, if (s == "🪫") pink else mint, 5f, false); path(butter) { moveTo(48f, 32f); lineTo(35f, 51f); lineTo(46f, 51f); lineTo(41f, 69f); lineTo(61f, 45f); lineTo(49f, 45f); close() }
    }

    private fun lock(s: String) {
        shadow()
        if (s == "🔑") { oval(13f, 21f, 38f, 38f, butter, true); oval(24f, 32f, 15f, 15f, paper, true); path(butter) { moveTo(46f, 48f); lineTo(82f, 75f); lineTo(74f, 85f); lineTo(65f, 78f); lineTo(70f, 71f); lineTo(59f, 64f); lineTo(54f, 71f); lineTo(40f, 59f); close() } }
        else { arc(31f, 13f, 38f, 48f, 180f, 180f, ink, 6f); rect(20f, 40f, 60f, 44f, butter, 10f); heart(38f, 51f, 25f, pink, false) }
    }

    private fun home(s: String) {
        shadow(); path(cream) { moveTo(22f, 44f); lineTo(22f, 83f); lineTo(78f, 83f); lineTo(78f, 44f); close() }; path(pink) { moveTo(12f, 46f); lineTo(50f, 14f); lineTo(88f, 46f); lineTo(79f, 54f); lineTo(50f, 30f); lineTo(21f, 54f); close() }; rect(42f, 58f, 19f, 25f, peach, 6f); heart(29f, 47f, 13f, rose, false); rect(64f, 53f, 8f, 12f, blue, 2f)
    }

    private fun shopping() {
        shadow(); path(cream) { moveTo(22f, 34f); lineTo(78f, 34f); lineTo(84f, 84f); lineTo(16f, 84f); close() }; arc(34f, 14f, 32f, 43f, 180f, 180f); heart(34f, 48f, 32f, pink, false)
    }

    private fun transport(s: String) {
        shadow(); rect(13f, 40f, 74f, 33f, blue, 10f); path(cream) { moveTo(23f, 40f); lineTo(33f, 22f); lineTo(64f, 22f); lineTo(77f, 40f); close() }; line(50f, 25f, 50f, 39f); oval(25f, 65f, 17f, 18f, ink); oval(59f, 65f, 17f, 18f, ink); oval(30f, 70f, 7f, 8f, paper); oval(64f, 70f, 7f, 8f, paper); heart(42f, 46f, 16f, pink, false); line(18f, 49f, 24f, 49f, butter, 4f); line(76f, 49f, 82f, 49f, butter, 4f)
    }

    private fun scarf() {
        shadow(); path(pink) { moveTo(22f, 25f); quadTo(49f, 9f, 77f, 25f); lineTo(71f, 51f); quadTo(49f, 64f, 27f, 49f); close() }; rect(28f, 47f, 21f, 40f, pink, 4f); path(peach) { moveTo(55f, 48f); lineTo(72f, 45f); lineTo(83f, 77f); lineTo(64f, 84f); close() }; line(26f, 35f, 74f, 35f, paper, 3f); line(32f, 77f, 45f, 77f, paper, 3f); line(68f, 73f, 78f, 70f, paper, 3f)
    }

    private fun paws() {
        shadow(); oval(30f, 47f, 40f, 34f, peach, true); oval(13f, 28f, 17f, 24f, cream, true); oval(32f, 15f, 16f, 24f, cream, true); oval(54f, 15f, 16f, 24f, cream, true); oval(73f, 28f, 17f, 24f, cream, true); heart(39f, 54f, 22f, pink, false)
    }

    private fun wallet() {
        shadow(); rect(15f, 28f, 70f, 53f, peach, 10f); path(cream) { moveTo(21f, 28f); lineTo(67f, 14f); lineTo(73f, 28f); close() }; rect(59f, 46f, 29f, 22f, cream, 6f); oval(66f, 53f, 7f, 7f, rose); heart(27f, 43f, 22f, pink, false)
    }

    private fun camera(s: String) {
        shadow(); rect(12f, 32f, 76f, 49f, cream, 10f); rect(25f, 21f, 25f, 16f, peach, 5f); oval(34f, 39f, 35f, 35f, blue, true); oval(43f, 48f, 17f, 17f, paper, true); rect(73f, 40f, 7f, 7f, pink, 2f, false); line(48f, 47f, 53f, 47f, paper, 3f)
    }

    private fun microphone() {
        shadow(); rect(36f, 12f, 28f, 50f, pink, 14f); arc(25f, 31f, 50f, 42f, 0f, 180f); line(50f, 74f, 50f, 86f); line(33f, 87f, 67f, 87f); line(39f, 29f, 48f, 29f, paper, 3f); line(39f, 40f, 48f, 40f, paper, 3f)
    }

    private fun yarn() {
        shadow(); oval(19f, 21f, 61f, 61f, pink, true); path(width = 2.4f) { moveTo(27f, 33f); quadTo(69f, 28f, 72f, 68f); moveTo(22f, 45f); quadTo(53f, 39f, 66f, 77f); moveTo(23f, 58f); quadTo(42f, 51f, 52f, 80f); moveTo(43f, 23f); quadTo(31f, 32f, 26f, 67f); moveTo(63f, 25f); quadTo(36f, 34f, 36f, 77f) }; path { moveTo(70f, 74f); cubicTo(93f, 74f, 92f, 90f, 77f, 87f) }
    }

    private fun fish() {
        shadow(); path(blue) { moveTo(23f, 52f); cubicTo(37f, 20f, 71f, 26f, 80f, 49f); cubicTo(74f, 76f, 42f, 84f, 23f, 52f); close() }; path(peach) { moveTo(27f, 51f); lineTo(9f, 33f); lineTo(10f, 72f); close() }; oval(64f, 44f, 5f, 7f, ink); arc(55f, 40f, 12f, 24f, 90f, 170f); path(pink) { moveTo(42f, 36f); lineTo(43f, 21f); lineTo(59f, 32f); close() }
    }

    private fun bottle(s: String) {
        shadow(); rect(34f, 14f, 32f, 13f, peach, 4f); path(cream) { moveTo(36f, 27f); lineTo(64f, 27f); lineTo(64f, 37f); quadTo(79f, 42f, 77f, 55f); lineTo(77f, 82f); quadTo(50f, 90f, 23f, 82f); lineTo(23f, 55f); quadTo(21f, 42f, 36f, 37f); close() }; rect(28f, 52f, 44f, 24f, mint, 5f, false); heart(40f, 53f, 21f, paper, false); oval(76f, 20f, 10f, 10f, blue, true); oval(84f, 37f, 7f, 7f, blue, true)
    }

    private fun bow() {
        shadow(); path(pink) { moveTo(45f, 46f); cubicTo(18f, 16f, 9f, 22f, 13f, 55f); cubicTo(14f, 74f, 30f, 69f, 45f, 55f); close() }; path(pink) { moveTo(55f, 46f); cubicTo(82f, 16f, 91f, 22f, 87f, 55f); cubicTo(86f, 74f, 70f, 69f, 55f, 55f); close() }; path(peach) { moveTo(43f, 54f); lineTo(28f, 83f); lineTo(42f, 80f); lineTo(49f, 88f); lineTo(55f, 56f); close() }; path(peach) { moveTo(53f, 55f); lineTo(58f, 88f); lineTo(65f, 80f); lineTo(78f, 83f); lineTo(59f, 54f); close() }; rect(42f, 41f, 17f, 19f, cream, 6f)
    }

    private fun crown(s: String) {
        shadow(); path(butter) { moveTo(20f, 73f); lineTo(12f, 27f); lineTo(34f, 45f); lineTo(50f, 17f); lineTo(66f, 45f); lineTo(88f, 27f); lineTo(80f, 73f); close() }; rect(19f, 68f, 62f, 13f, peach, 4f); heart(42f, 47f, 16f, pink, false); oval(8f, 20f, 9f, 9f, pink, true); oval(46f, 10f, 9f, 9f, pink, true); oval(83f, 20f, 9f, 9f, pink, true)
    }

    private fun play(s: String) {
        shadow(); rect(15f, 23f, 70f, 58f, lilac, 12f); path(cream) { moveTo(42f, 39f); lineTo(65f, 52f); lineTo(42f, 66f); close() }; oval(23f, 31f, 6f, 6f, paper); sparkle(83f, 14f, 5f)
    }

    private fun check(s: String) {
        shadow(); val warning = s in setOf("⚠", "⛔", "🟠"); oval(16f, 16f, 68f, 68f, if (warning) butter else if (s == "🔴") pink else if (s == "⬜") cream else mint, true)
        if (s == "⬜") return
        if (s == "🔴") { oval(31f, 28f, 15f, 11f, paper); return }
        if (warning) { line(50f, 33f, 50f, 56f, ink, 5f); oval(47f, 65f, 6f, 6f, ink) }
        else path(width = 5f) { moveTo(32f, 49f); lineTo(45f, 62f); lineTo(70f, 36f) }
    }

    private fun signal() { shadow(); rect(16f, 63f, 12f, 19f, blue, 4f); rect(34f, 49f, 12f, 33f, blue, 4f); rect(52f, 34f, 12f, 48f, mint, 4f); rect(70f, 18f, 12f, 64f, mint, 4f) }

    private fun clock(s: String) {
        shadow(); oval(17f, 18f, 66f, 66f, cream, true); line(50f, 33f, 50f, 53f); line(50f, 53f, 66f, 61f); oval(46f, 49f, 8f, 8f, pink); path(peach) { moveTo(22f, 27f); quadTo(10f, 10f, 26f, 10f); lineTo(34f, 17f); close() }; path(peach) { moveTo(66f, 17f); lineTo(74f, 10f); quadTo(90f, 10f, 78f, 27f); close() }; line(28f, 83f, 23f, 89f); line(72f, 83f, 77f, 89f)
    }

    private fun pencil(s: String) {
        shadow()
        if (s == "🔍") { oval(13f, 14f, 52f, 52f, blue, true); oval(21f, 22f, 36f, 36f, paper); line(60f, 61f, 83f, 84f, ink, 14f); line(60f, 61f, 83f, 84f, peach, 8f); heart(29f, 29f, 20f, pink, false) }
        else { path(butter) { moveTo(19f, 66f); lineTo(68f, 17f); lineTo(84f, 33f); lineTo(35f, 82f); close() }; path(pink) { moveTo(68f, 17f); lineTo(75f, 10f); quadTo(90f, 12f, 91f, 26f); lineTo(84f, 33f); close() }; path(cream) { moveTo(19f, 66f); lineTo(12f, 89f); lineTo(35f, 82f); close() }; line(16f, 79f, 12f, 89f, ink, 4f); line(29f, 65f, 70f, 25f, paper, 3f) }
    }

    private fun can() {
        shadow(); rect(23f, 28f, 54f, 52f, blue, 4f)
        oval(23f, 18f, 54f, 20f, paper, true); oval(23f, 70f, 54f, 14f, blue, true)
        oval(43f, 23f, 16f, 8f, cream, true)
        rect(24f, 41f, 52f, 29f, cream, 2f, false)
        path(pink) { moveTo(39f, 56f); cubicTo(47f, 43f, 63f, 46f, 66f, 56f); cubicTo(61f, 67f, 47f, 68f, 39f, 56f); close() }
        path(peach) { moveTo(41f, 56f); lineTo(31f, 47f); lineTo(31f, 65f); close() }
        oval(58f, 52f, 3f, 4f, ink)
    }

    private fun lightbulb() {
        shadow()
        path(butter) { moveTo(39f, 72f); cubicTo(38f, 61f, 21f, 54f, 24f, 36f); cubicTo(29f, 6f, 71f, 6f, 76f, 36f); cubicTo(79f, 54f, 62f, 61f, 61f, 72f); close() }
        rect(38f, 70f, 24f, 14f, peach, 4f); line(43f, 89f, 57f, 89f)
        heart(39f, 33f, 22f, pink, false)
        line(14f, 40f, 7f, 40f, ink, 2.6f); line(86f, 40f, 93f, 40f, ink, 2.6f)
        line(20f, 18f, 15f, 13f, ink, 2.6f); line(80f, 18f, 85f, 13f, ink, 2.6f)
    }

    private fun sprout(clover: Boolean) {
        shadow()
        path(width = 4f) { moveTo(49f, 82f); quadTo(60f, 63f, 49f, 45f) }
        if (clover) {
            for (i in 0..3) {
                val save = c.save(); c.rotate(i * 90f, 50f, 45f)
                heart(35f, 15f, 30f, mint)
                c.restoreToCount(save)
            }
            oval(46f, 41f, 8f, 8f, butter, true)
        } else {
            path(mint) { moveTo(50f, 57f); cubicTo(17f, 59f, 18f, 31f, 18f, 26f); cubicTo(41f, 22f, 53f, 38f, 50f, 57f); close() }
            path(mint) { moveTo(51f, 47f); cubicTo(46f, 26f, 63f, 14f, 84f, 17f); cubicTo(85f, 39f, 71f, 51f, 51f, 47f); close() }
            path(width = 2.2f) { moveTo(49f, 58f); quadTo(38f, 40f, 28f, 35f); moveTo(51f, 48f); quadTo(61f, 35f, 73f, 28f) }
            rect(30f, 79f, 40f, 8f, peach, 4f, false)
        }
    }

    private fun crystalBall() {
        shadow(); oval(19f, 12f, 62f, 62f, lilac, true)
        path(peach) { moveTo(31f, 68f); lineTo(69f, 68f); lineTo(78f, 85f); lineTo(22f, 85f); close() }
        star(51f, 42f, 18f, cream); oval(29f, 23f, 10f, 7f, paper)
        sparkle(84f, 19f, 5f); sparkle(15f, 60f, 4f)
    }

    private fun brain() {
        shadow()
        path(pink) { moveTo(49f, 24f); cubicTo(39f, 8f, 21f, 19f, 23f, 31f); cubicTo(5f, 36f, 7f, 55f, 18f, 59f); cubicTo(12f, 77f, 32f, 89f, 49f, 76f); cubicTo(66f, 89f, 86f, 77f, 80f, 59f); cubicTo(91f, 55f, 93f, 36f, 75f, 31f); cubicTo(77f, 19f, 59f, 8f, 49f, 24f); close() }
        path(width = 2.6f) { moveTo(49f, 26f); lineTo(49f, 75f); moveTo(25f, 33f); quadTo(36f, 29f, 38f, 41f); moveTo(17f, 50f); quadTo(30f, 46f, 33f, 60f); moveTo(30f, 75f); quadTo(25f, 62f, 40f, 59f); moveTo(73f, 33f); quadTo(62f, 29f, 60f, 41f); moveTo(81f, 50f); quadTo(68f, 46f, 65f, 60f); moveTo(68f, 75f); quadTo(73f, 62f, 58f, 59f) }
    }

    private fun question() {
        shadow(); oval(16f, 14f, 68f, 68f, cream, true)
        path(width = 5f) { moveTo(37f, 36f); cubicTo(35f, 20f, 67f, 20f, 65f, 38f); cubicTo(65f, 48f, 50f, 47f, 50f, 58f) }
        oval(47f, 67f, 6f, 6f, rose)
    }

    private fun magnet() {
        shadow()
        path(pink) { moveTo(19f, 22f); lineTo(39f, 22f); lineTo(39f, 54f); cubicTo(39f, 72f, 61f, 72f, 61f, 54f); lineTo(61f, 22f); lineTo(81f, 22f); lineTo(81f, 54f); cubicTo(81f, 96f, 19f, 96f, 19f, 54f); close() }
        rect(19f, 22f, 20f, 17f, cream, 2f); rect(61f, 22f, 20f, 17f, blue, 2f)
        sparkle(49f, 13f, 5f)
    }

    private fun lookingEyes() {
        shadow(); oval(14f, 24f, 35f, 49f, paper, true); oval(51f, 24f, 35f, 49f, paper, true)
        oval(31f, 38f, 13f, 24f, ink); oval(67f, 38f, 13f, 24f, ink)
        oval(33f, 40f, 4f, 6f, paper); oval(69f, 40f, 4f, 6f, paper)
    }

    private fun chain() {
        shadow(); val save = c.save(); c.rotate(-35f, 50f, 50f)
        rect(18f, 26f, 37f, 31f, pink, 14f); rect(45f, 43f, 37f, 31f, blue, 14f)
        rect(27f, 34f, 18f, 14f, paper, 6f, false); rect(54f, 52f, 18f, 14f, paper, 6f, false)
        line(43f, 44f, 57f, 56f, ink, 5f); c.restoreToCount(save)
    }

    private fun wind() {
        shadow()
        path(width = 4f) { moveTo(14f, 37f); lineTo(64f, 37f); cubicTo(84f, 37f, 79f, 13f, 64f, 20f) }
        path(width = 4f) { moveTo(23f, 52f); lineTo(79f, 52f); cubicTo(96f, 52f, 94f, 32f, 81f, 34f) }
        path(width = 4f) { moveTo(14f, 66f); lineTo(56f, 66f); cubicTo(75f, 66f, 69f, 89f, 55f, 81f) }
        oval(12f, 47f, 5f, 7f, mint); oval(70f, 73f, 8f, 5f, blue)
    }

    private fun rainbow() {
        shadow()
        arc(13f, 22f, 74f, 104f, 180f, 180f, pink, 10f)
        arc(24f, 34f, 52f, 79f, 180f, 180f, butter, 9f)
        arc(35f, 46f, 30f, 55f, 180f, 180f, mint, 9f)
        oval(7f, 64f, 31f, 20f, paper, true); oval(62f, 64f, 31f, 20f, paper, true)
        sparkle(86f, 18f, 5f)
    }

    private fun flame() {
        shadow()
        path(peach) { moveTo(48f, 10f); cubicTo(55f, 27f, 71f, 30f, 64f, 48f); quadTo(76f, 45f, 78f, 33f); cubicTo(107f, 78f, 67f, 96f, 46f, 87f); cubicTo(15f, 92f, 4f, 58f, 28f, 39f); quadTo(23f, 55f, 35f, 58f); cubicTo(23f, 32f, 44f, 29f, 48f, 10f); close() }
        path(butter, false) { moveTo(51f, 51f); cubicTo(67f, 67f, 67f, 85f, 49f, 85f); cubicTo(28f, 82f, 40f, 63f, 51f, 51f); close() }
    }

    private fun ice() {
        shadow()
        path(blue) { moveTo(20f, 30f); lineTo(60f, 17f); lineTo(83f, 36f); lineTo(80f, 75f); lineTo(39f, 87f); lineTo(17f, 66f); close() }
        path(paper) { moveTo(20f, 30f); lineTo(60f, 17f); lineTo(83f, 36f); lineTo(42f, 48f); close() }
        path { moveTo(42f, 48f); lineTo(39f, 87f) }; line(54f, 52f, 70f, 47f, paper, 4f)
        line(53f, 63f, 64f, 60f, paper, 3f); sparkle(13f, 16f, 5f)
    }

    private fun shield() {
        shadow(); path(mint) { moveTo(50f, 12f); quadTo(67f, 22f, 83f, 22f); lineTo(80f, 55f); quadTo(76f, 75f, 50f, 88f); quadTo(24f, 75f, 20f, 55f); lineTo(17f, 22f); quadTo(33f, 22f, 50f, 12f); close() }
        heart(34f, 36f, 32f, cream)
    }

    private fun briefcase() {
        shadow(); rect(35f, 13f, 30f, 21f, cream, 7f); rect(13f, 30f, 74f, 53f, peach, 9f)
        path(cream) { moveTo(14f, 31f); lineTo(86f, 31f); lineTo(85f, 48f); quadTo(51f, 65f, 15f, 48f); close() }
        rect(43f, 49f, 14f, 13f, pink, 4f)
    }

    private fun torii() {
        shadow(); path(pink) { moveTo(9f, 17f); quadTo(50f, 27f, 91f, 17f); lineTo(88f, 28f); quadTo(50f, 37f, 12f, 28f); close() }
        path(peach) { moveTo(27f, 31f); lineTo(38f, 32f); lineTo(35f, 86f); lineTo(22f, 86f); close() }
        path(peach) { moveTo(62f, 32f); lineTo(73f, 31f); lineTo(78f, 86f); lineTo(65f, 86f); close() }
        rect(15f, 45f, 70f, 10f, pink, 3f); line(50f, 33f, 50f, 45f, ink, 5f)
        heart(43f, 64f, 14f, rose, false)
    }

    private fun scale() {
        shadow(); rect(24f, 79f, 52f, 8f, peach, 4f); line(50f, 19f, 50f, 79f, ink, 5f); line(19f, 32f, 81f, 32f)
        oval(45f, 20f, 10f, 10f, pink, true)
        path { moveTo(15f, 61f); lineTo(28f, 32f); lineTo(41f, 61f); moveTo(59f, 61f); lineTo(72f, 32f); lineTo(85f, 61f) }
        path(cream) { moveTo(12f, 61f); lineTo(44f, 61f); quadTo(28f, 85f, 12f, 61f); close() }
        path(cream) { moveTo(56f, 61f); lineTo(88f, 61f); quadTo(72f, 85f, 56f, 61f); close() }
    }

    private fun dice() {
        shadow(); rect(20f, 18f, 62f, 62f, cream, 13f)
        for ((x, y) in listOf(35f to 33f, 65f to 33f, 50f to 49f, 35f to 65f, 65f to 65f)) oval(x - 4f, y - 4f, 8f, 8f, rose)
        sparkle(14f, 15f, 4f)
    }

    private fun mediaControl(s: String) {
        shadow(); oval(16f, 14f, 68f, 68f, cream, true)
        when (s) {
            "⏸" -> { rect(34f, 31f, 11f, 34f, pink, 3f); rect(55f, 31f, 11f, 34f, pink, 3f) }
            "⏹" -> rect(34f, 32f, 32f, 32f, pink, 5f)
            "🔁" -> { arc(29f, 27f, 42f, 42f, 205f, 295f, ink, 4f); path(pink) { moveTo(26f, 28f); lineTo(44f, 28f); lineTo(29f, 43f); close() } }
            else -> {
                path(pink) { moveTo(38f, 29f); lineTo(66f, 48f); lineTo(38f, 67f); close() }
                if (s in setOf("⏭", "⏩")) line(70f, 31f, 70f, 65f, ink, 4f)
            }
        }
    }

    private fun robot() {
        shadow(); line(50f, 16f, 50f, 27f); oval(44f, 8f, 12f, 12f, pink, true)
        rect(10f, 44f, 12f, 20f, peach, 4f); rect(78f, 44f, 12f, 20f, peach, 4f)
        rect(19f, 28f, 62f, 54f, cream, 13f); rect(28f, 40f, 44f, 22f, blue, 7f, false)
        eyes(50f, 38f, 62f); smile(65f); oval(26f, 67f, 7f, 6f, pink); oval(67f, 67f, 7f, 6f, pink)
    }

    private fun ruler() {
        shadow(); val save = c.save(); c.rotate(-35f, 50f, 50f)
        rect(11f, 34f, 78f, 32f, butter, 5f)
        for (i in 0..6) line(22f + i * 9f, 35f, 22f + i * 9f, if (i % 2 == 0) 51f else 45f, ink, 2.5f)
        oval(77f, 54f, 5f, 5f, cream, true); c.restoreToCount(save)
    }

    private fun tools() {
        shadow()
        path(blue) { moveTo(23f, 13f); lineTo(32f, 29f); lineTo(46f, 22f); lineTo(38f, 9f); cubicTo(65f, 8f, 66f, 33f, 50f, 43f); lineTo(83f, 75f); quadTo(91f, 86f, 78f, 89f); lineTo(43f, 51f); cubicTo(19f, 62f, 8f, 32f, 23f, 13f); close() }
        oval(77f, 78f, 5f, 5f, paper)
        path(peach) { moveTo(65f, 18f); lineTo(82f, 34f); lineTo(29f, 86f); lineTo(15f, 71f); close() }
        path(cream) { moveTo(56f, 15f); lineTo(66f, 5f); lineTo(91f, 30f); lineTo(81f, 40f); close() }
    }

    private fun ring() {
        shadow(); oval(24f, 33f, 52f, 52f, butter, true); oval(34f, 43f, 32f, 32f, paper, true)
        path(pink) { moveTo(34f, 14f); lineTo(66f, 14f); lineTo(77f, 29f); lineTo(50f, 49f); lineTo(23f, 29f); close() }
        path(width = 2f) { moveTo(23f, 29f); lineTo(77f, 29f); moveTo(39f, 14f); lineTo(36f, 29f); lineTo(50f, 49f); lineTo(64f, 29f); lineTo(61f, 14f) }
        sparkle(85f, 17f, 5f)
    }

    private fun balloon() {
        shadow(); oval(24f, 9f, 52f, 62f, pink, true)
        path(pink) { moveTo(49f, 68f); lineTo(43f, 77f); lineTo(57f, 77f); close() }
        path { moveTo(50f, 77f); cubicTo(34f, 79f, 63f, 85f, 47f, 93f) }
        oval(34f, 21f, 9f, 15f, paper); heart(39f, 40f, 22f, cream, false)
    }

    private fun fingerprint() {
        shadow(); oval(15f, 13f, 70f, 70f, mint, true)
        path(width = 2.8f) { moveTo(29f, 55f); lineTo(29f, 42f); cubicTo(29f, 14f, 71f, 14f, 71f, 42f); lineTo(71f, 58f); moveTo(37f, 65f); lineTo(37f, 42f); cubicTo(37f, 25f, 63f, 25f, 63f, 42f); lineTo(63f, 58f); quadTo(63f, 66f, 67f, 71f); moveTo(43f, 73f); quadTo(46f, 63f, 45f, 48f); lineTo(45f, 42f); quadTo(50f, 33f, 55f, 42f); lineTo(55f, 59f); quadTo(55f, 70f, 59f, 75f) }
    }

    private fun zodiac(s: String) {
        shadow(); oval(14f, 14f, 72f, 72f, lilac, true); sparkle(83f, 17f, 5f)
        when (s) {
            "♈" -> path(width = 4f) { moveTo(50f, 73f); lineTo(50f, 45f); cubicTo(50f, 20f, 23f, 25f, 28f, 43f); moveTo(50f, 45f); cubicTo(50f, 20f, 77f, 25f, 72f, 43f) }
            "♉" -> { oval(34f, 42f, 32f, 32f, cream, true); arc(29f, 17f, 42f, 36f, 0f, 180f, ink, 4f) }
            "♊" -> { arc(26f, 19f, 48f, 16f, 0f, 180f); arc(26f, 66f, 48f, 16f, 180f, 180f); line(38f, 33f, 38f, 66f); line(62f, 33f, 62f, 66f) }
            "♋" -> { oval(26f, 36f, 16f, 16f, cream, true); oval(58f, 48f, 16f, 16f, cream, true); arc(26f, 26f, 48f, 30f, 190f, 150f); arc(26f, 44f, 48f, 30f, 10f, 150f) }
            "♌" -> { oval(26f, 49f, 19f, 19f, cream, true); path(width = 4f) { moveTo(44f, 58f); cubicTo(67f, 29f, 28f, 22f, 40f, 38f); cubicTo(63f, 26f, 69f, 47f, 61f, 59f); cubicTo(53f, 72f, 70f, 78f, 75f, 64f) } }
            "♍", "♏" -> { path(width = 4f) { moveTo(25f, 67f); lineTo(25f, 39f); quadTo(33f, 27f, 41f, 39f); lineTo(41f, 67f); moveTo(41f, 39f); quadTo(49f, 27f, 57f, 39f); lineTo(57f, 67f); moveTo(57f, 39f); quadTo(65f, 27f, 70f, 39f); lineTo(70f, 66f); quadTo(67f, 77f, 59f, 74f) }; if (s == "♏") { line(70f, 66f, 78f, 60f); line(73f, 59f, 78f, 60f); line(78f, 60f, 78f, 66f) } }
            "♎" -> { line(27f, 70f, 73f, 70f); path(width = 4f) { moveTo(27f, 59f); lineTo(37f, 59f); cubicTo(19f, 28f, 81f, 28f, 63f, 59f); lineTo(73f, 59f) } }
            "♐" -> { line(29f, 71f, 70f, 30f, ink, 4f); line(31f, 40f, 60f, 69f, ink, 4f); path(width = 4f) { moveTo(49f, 30f); lineTo(70f, 30f); lineTo(70f, 51f) } }
            "♑" -> path(width = 4f) { moveTo(24f, 36f); quadTo(35f, 24f, 40f, 38f); lineTo(46f, 68f); quadTo(48f, 34f, 57f, 34f); lineTo(65f, 60f); cubicTo(90f, 40f, 77f, 26f, 63f, 42f); cubicTo(46f, 60f, 51f, 78f, 62f, 74f) }
            "♒" -> { path(width = 4f) { moveTo(24f, 43f); lineTo(37f, 35f); lineTo(50f, 43f); lineTo(63f, 35f); lineTo(76f, 43f) }; path(width = 4f) { moveTo(24f, 63f); lineTo(37f, 55f); lineTo(50f, 63f); lineTo(63f, 55f); lineTo(76f, 63f) } }
            "♓" -> { arc(18f, 27f, 31f, 46f, 270f, 180f, ink, 4f); arc(51f, 27f, 31f, 46f, 90f, 180f, ink, 4f); line(28f, 50f, 72f, 50f, ink, 4f) }
        }
    }
}
