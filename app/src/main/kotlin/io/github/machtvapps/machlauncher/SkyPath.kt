package io.github.machtvapps.machlauncher

import kotlin.math.PI
import kotlin.math.sin

private const val DAY_MINUTES = 24 * 60

data class SkyPosition(val across: Float, val height: Float)

fun skyPosition(minutes: Int, lightFrom: Int, darkFrom: Int): SkyPosition {
    val light = isLightAt(minutes, lightFrom, darkFrom)
    val start = if (light) lightFrom else darkFrom
    val end = if (light) darkFrom else lightFrom
    val length = ((end - start + DAY_MINUTES) % DAY_MINUTES).let { if (it == 0) DAY_MINUTES else it }
    val across = ((minutes - start + DAY_MINUTES) % DAY_MINUTES) / length.toFloat()
    return SkyPosition(across, sin(PI * across).toFloat())
}

fun showsDayClouds(choice: BackgroundChoice, minutes: Int, lightFrom: Int, darkFrom: Int): Boolean = when (choice) {
    BackgroundChoice.light, BackgroundChoice.cloudsDay -> true
    BackgroundChoice.dark, BackgroundChoice.cloudsNight -> false
    BackgroundChoice.cloudsAuto, BackgroundChoice.picture -> isLightAt(minutes, lightFrom, darkFrom)
}
