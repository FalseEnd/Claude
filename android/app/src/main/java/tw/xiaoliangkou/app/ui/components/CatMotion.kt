package tw.xiaoliangkou.app.ui.components

import kotlin.math.PI
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

/**
 * 偏移量以插圖邊長為單位，paw、lift 為向上，其他 Y 值為向下。
 * tilt 為角度，headRoll 為弧度；eyesClosed、jaw、lick、toolStroke 為 0 至 1。
 * ears 的正值讓耳尖立起，負值讓耳朵往後收。toyX 是相對畫面中央的道具位置。
 */
internal data class CatPose(
    val x: Float = 0f,
    val lift: Float = 0f,
    val squash: Float = 0f,
    val tilt: Float = 0f,
    val headX: Float = 0f,
    val headY: Float = 0f,
    val headRoll: Float = 0f,
    val tail: Float = 0f,
    val tailY: Float = 0f,
    val paw: Float = 0f,
    val pawX: Float = 0f,
    val lick: Float = 0f,
    val eyesClosed: Float = 0f,
    val jaw: Float = 0f,
    val ears: Float = 0f,
    val toolStroke: Float = 0f,
    val toyX: Float = 0f,
)

private val restingPose = CatPose()

internal fun CatMotion.pose(progress: Float): CatPose {
    val time = progress.coerceIn(0f, 1f)
    return when (this) {
        CatMotion.REST -> restingPose
        CatMotion.EAT -> eatingPose(time)
        CatMotion.WAND -> pouncingPose(time)
        CatMotion.LASER -> chasingPose(time)
        CatMotion.YARN -> battingPose(time)
        CatMotion.NUZZLE -> nuzzlingPose(time)
        CatMotion.SHAKE -> shakingPose(time)
        CatMotion.BRUSH -> brushingPose(time)
        CatMotion.GROOM -> groomingPose(time)
        CatMotion.STRETCH -> stretchingPose(time)
    }
}

// 柔和地轉移重量，讓前後動作銜接，腳掌著地時仍然有短暫的停頓。
private fun Float.ramp(from: Float, to: Float): Float {
    val fraction = ((this - from) / (to - from)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

private fun Float.hold(start: Float, ready: Float, release: Float, finish: Float) =
    ramp(start, ready) * (1f - ramp(release, finish))

private fun Float.hop(start: Float, land: Float): Float {
    val airborne = ((this - start) / (land - start)).coerceIn(0f, 1f)
    return 4f * airborne * (1f - airborne)
}

private fun eatingPose(time: Float): CatPose {
    val bend = time.hold(.06f, .26f, .70f, .88f)
    val bite = time.hold(.26f, .30f, .32f, .37f) +
        time.hold(.40f, .45f, .46f, .51f) +
        time.hold(.55f, .61f, .62f, .68f)
    val finish = time.hold(.84f, .90f, .92f, .97f)
    return CatPose(
        squash = .24f * bend,
        headX = -.018f * bend,
        headY = .158f * bend + .035f * bite,
        headRoll = -.055f * bend,
        eyesClosed = .60f * bite + .45f * finish,
        jaw = .72f * bite,
        lick = .58f * finish,
        ears = .38f * time.hold(.02f, .09f, .15f, .24f),
    )
}

private fun pouncingPose(time: Float): CatPose {
    val windUp = time.hold(.03f, .15f, .20f, .27f)
    val jump = time.hop(.25f, .55f)
    val reach = time.hold(.28f, .36f, .43f, .54f)
    val landing = time.hold(.52f, .59f, .62f, .73f)
    val lean = time.hold(.18f, .47f, .63f, .95f)
    return CatPose(
        x = .13f * lean,
        lift = .21f * jump,
        squash = .16f * windUp - .046f * jump + .13f * landing,
        tilt = (9f * jump) - (6f * landing),
        headX = .049f * reach,
        headY = -.04f * reach + .037f * landing,
        headRoll = .11f * reach,
        paw = .21f * reach,
        pawX = .12f * reach,
        tail = -.044f * lean,
        tailY = -.088f * jump,
        ears = .50f * windUp,
        toyX = .21f - .04f * time.ramp(.04f, .36f) + .10f * time.ramp(.44f, .62f) -
            .06f * time.ramp(.67f, .99f),
    )
}

private fun laserPosition(time: Float) =
    .29f * time.ramp(.05f, .20f) - .60f * time.ramp(.29f, .47f) +
        .55f * time.ramp(.57f, .74f) - .24f * time.ramp(.84f, 1f)

private fun chasingPose(time: Float): CatPose {
    val target = laserPosition(time)
    val chase = laserPosition(time - .05f) * (1f - time.ramp(.86f, .99f))
    val going = (target - chase).coerceIn(-.20f, .20f)
    val steps = time.hop(.18f, .29f) + time.hop(.38f, .51f) + time.hop(.67f, .79f)
    val crouch = time.hold(.08f, .20f, .80f, .99f)
    return CatPose(
        x = .79f * chase,
        lift = .052f * steps,
        squash = .12f * crouch - .05f * steps,
        tilt = 36f * going,
        headX = .30f * going,
        headY = .052f * crouch,
        headRoll = .9f * going,
        paw = .095f * steps,
        pawX = .24f * going,
        tail = -.20f * going,
        tailY = -.052f * crouch,
        ears = .45f * crouch,
        toyX = target,
    )
}

private fun battingPose(time: Float): CatPose {
    val crouch = time.hold(.06f, .22f, .65f, .90f)
    val liftPaw = time.hold(.23f, .37f, .45f, .56f)
    val bat = time.hold(.35f, .43f, .48f, .57f)
    val followThrough = time.hold(.47f, .57f, .65f, .87f)
    return CatPose(
        x = -.081f * crouch,
        squash = .16f * crouch,
        tilt = -4f * bat,
        headX = -.034f * crouch,
        headY = .082f * crouch,
        headRoll = -.18f * crouch,
        paw = .15f * liftPaw - .05f * bat,
        pawX = -.16f * bat,
        tail = .053f * followThrough,
        tailY = -.065f * crouch,
        toyX = -.27f - .15f * time.ramp(.43f, .71f),
    )
}

private fun nuzzlingPose(time: Float): CatPose {
    val leanIn = time.hold(.08f, .36f, .70f, .99f)
    val rub = time.hold(.23f, .49f, .60f, .87f)
    val slowBlink = time.hold(.13f, .30f, .74f, .95f)
    return CatPose(
        x = .027f * leanIn,
        headX = .070f * rub,
        headY = -.043f * leanIn,
        headRoll = .25f * rub,
        tail = .032f * leanIn,
        tailY = -.095f * rub,
        eyesClosed = slowBlink,
        ears = -.25f * rub,
    )
}

private fun shakingPose(time: Float): CatPose {
    val huddle = time.hold(.08f, .21f, .65f, .84f)
    val shake = sin((time - .31f) * 16f * PI).toFloat() * time.hold(.29f, .36f, .64f, .79f)
    val tailShake = sin((time - .35f) * 16f * PI).toFloat() * time.hold(.34f, .41f, .68f, .86f)
    return CatPose(
        squash = .08f * huddle,
        tilt = 4.4f * tailShake,
        headX = .038f * shake,
        headY = .04f * huddle,
        headRoll = .18f * shake,
        tail = -.046f * tailShake,
        tailY = -.024f * huddle,
        eyesClosed = time.hold(.22f, .30f, .72f, .91f),
        ears = -.75f * huddle,
    )
}

private fun brushingPose(time: Float): CatPose {
    val firstStroke = time.hold(.07f, .37f, .40f, .50f)
    val secondStroke = time.hold(.52f, .79f, .83f, .94f)
    val stroke = firstStroke + secondStroke
    val relax = time.hold(.10f, .30f, .83f, .99f)
    return CatPose(
        headX = -.022f * stroke,
        headY = -.063f * relax + .017f * stroke,
        headRoll = -.12f * stroke,
        tail = .028f * stroke,
        tailY = -.058f * relax,
        eyesClosed = .86f * relax,
        ears = .20f * relax,
        toolStroke = stroke,
    )
}

private fun groomingPose(time: Float): CatPose {
    val raisedPaw = time.hold(.09f, .28f, .78f, .97f)
    val bend = time.hold(.19f, .33f, .74f, .90f)
    val lick = time.hold(.31f, .36f, .39f, .45f) +
        time.hold(.47f, .53f, .55f, .61f) +
        time.hold(.64f, .69f, .71f, .76f)
    return CatPose(
        squash = .071f * bend,
        headX = -.073f * bend,
        headY = .101f * bend + .028f * lick,
        headRoll = -.20f * bend,
        tailY = -.030f * raisedPaw,
        paw = .216f * raisedPaw + .023f * lick,
        pawX = .047f * raisedPaw,
        eyesClosed = .71f * bend + .22f * lick,
        lick = lick,
    )
}

private fun stretchingPose(time: Float): CatPose {
    val stretch = time.hold(.07f, .36f, .63f, .98f)
    val reach = time.hold(.14f, .39f, .57f, .86f)
    return CatPose(
        x = -.043f * stretch,
        squash = .22f * stretch,
        headX = -.043f * stretch,
        headY = .110f * stretch,
        headRoll = -.09f * stretch,
        pawX = -.11f * reach,
        tail = .047f * stretch,
        tailY = -.13f * stretch,
        eyesClosed = .94f * stretch,
        ears = -.32f * reach,
    )
}
