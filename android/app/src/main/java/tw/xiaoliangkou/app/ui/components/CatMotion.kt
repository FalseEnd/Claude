package tw.xiaoliangkou.app.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class CatMotion(val description: String) {
    REST("乖乖坐著"), EAT("低頭吃飯"), WAND("跳起來撲逗貓棒"), LASER("左右追光點"), YARN("低身撥毛線球"),
    NUZZLE("歪頭蹭蹭"), SHAKE("甩甩身上的水"), BRUSH("順著梳子抬頭"), GROOM("低頭舔前腳"), STRETCH("伸懶腰");

    companion object {
        fun forProp(prop: String?) = when (prop) {
            "kibble", "can", "fish" -> EAT
            "wand" -> WAND
            "laser" -> LASER
            "yarn" -> YARN
            "pet" -> NUZZLE
            "bath" -> SHAKE
            "brush" -> BRUSH
            else -> REST
        }
    }
}

// Distances are fractions of the illustration cell. Head and tail can move independently of the feet.
internal data class CatPose(
    val x: Float = 0f, val lift: Float = 0f, val squash: Float = 0f, val tilt: Float = 0f,
    val headX: Float = 0f, val headY: Float = 0f, val headRoll: Float = 0f,
    val tail: Float = 0f, val paw: Float = 0f, val lick: Float = 0f,
)

private val restingPose = CatPose()

internal fun CatMotion.pose(progress: Float): CatPose {
    if (this == CatMotion.REST || progress <= 0f || progress >= 1f) return restingPose
    val phase = progress * 2 * PI
    val wave = sin(phase * 2).toFloat()
    val beat = (1f - cos(phase * 3).toFloat()) / 2
    val ease = sin(progress.coerceIn(0f, 1f) * PI).toFloat().coerceAtLeast(0f)
    return when (this) {
        CatMotion.REST -> restingPose
        CatMotion.EAT -> CatPose(squash = (.20f + .12f * beat) * ease, headY = (.10f + .12f * beat) * ease, headRoll = -.08f * ease)
        CatMotion.WAND -> CatPose(x = .10f * beat, lift = .22f * beat, tilt = 12f * beat, paw = .11f * beat)
        CatMotion.LASER -> CatPose(x = .27f * wave, lift = .035f * wave * wave, tilt = 12f * wave, tail = -.07f * wave)
        CatMotion.YARN -> CatPose(x = -.10f * beat, squash = .13f * beat, headRoll = -.17f * beat, paw = .17f * beat)
        CatMotion.NUZZLE -> CatPose(headX = .055f * wave, headY = -.035f * ease, headRoll = .26f * wave, tail = .055f * wave)
        CatMotion.SHAKE -> {
            val shake = sin(phase * 7).toFloat() * ease
            CatPose(tilt = 5f * shake, headX = .045f * shake, headRoll = .23f * shake, tail = -.08f * shake)
        }
        CatMotion.BRUSH -> {
            val stroke = (1f - cos(phase * 2).toFloat()) / 2
            CatPose(headY = -.05f * stroke, headRoll = -.14f * stroke, tail = .08f * stroke)
        }
        CatMotion.GROOM -> CatPose(headX = -.07f * ease, headY = (.08f + .05f * beat) * ease,
            headRoll = -.26f * ease, paw = .23f * ease, lick = beat * ease)
        CatMotion.STRETCH -> CatPose(squash = .24f * ease, headX = -.06f * ease, headY = .08f * ease,
            headRoll = -.10f * ease, tail = .12f * ease)
    }
}
