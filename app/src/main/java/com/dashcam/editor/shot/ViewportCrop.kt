package com.dashcam.editor.shot

import kotlin.math.roundToInt

/** Translation is expressed as a fraction of the unscaled video viewport. */
data class ViewportCrop(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    data class Pixels(val x: Int, val y: Int, val width: Int, val height: Int)

    fun pixels(width: Int, height: Int): Pixels {
        val zoom = scale.coerceAtLeast(1f)
        val w = ((width / zoom).roundToInt() / 2 * 2).coerceIn(2, width)
        val h = ((height / zoom).roundToInt() / 2 * 2).coerceIn(2, height)
        val x = (((0.5f - 0.5f / zoom - panX / zoom) * width).roundToInt() / 2 * 2).coerceIn(0, width - w)
        val y = (((0.5f - 0.5f / zoom - panY / zoom) * height).roundToInt() / 2 * 2).coerceIn(0, height - h)
        return Pixels(x, y, w, h)
    }
}
