package com.dashcam.editor.zoom

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 视频放大/平移状态。
 * 状态独立于视频内容，逐帧步进（TextureView 重绘）时缩放与平移完全保持不变。
 */
class ZoomState(
    val maxScale: Float = 8f,
) {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var viewW by mutableFloatStateOf(1f)
    var viewH by mutableFloatStateOf(1f)

    fun resize(width: Float, height: Float) {
        offsetX *= width / viewW
        offsetY *= height / viewH
        viewW = width
        viewH = height
        clamp()
    }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(1f, maxScale)
        clamp()
    }

    fun clamp() {
        val maxX = (scale - 1f) * viewW / 2f
        val maxY = (scale - 1f) * viewH / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
        if (scale <= 1.001f) {
            offsetX = 0f
            offsetY = 0f
        }
    }
}

/** 双指缩放（含单指平移）、单击（默认播放/暂停）、双击复位 */
fun Modifier.zoomGesture(state: ZoomState, onTap: (() -> Unit)? = null): Modifier = this
    .pointerInput(state) {
        detectTransformGestures { _, pan, zoom, _ ->
            state.scale = (state.scale * zoom).coerceIn(1f, state.maxScale)
            state.offsetX += pan.x
            state.offsetY += pan.y
            state.clamp()
        }
    }
    .pointerInput(state, onTap) {
        detectTapGestures(
            onTap = { onTap?.invoke() },
            onDoubleTap = { state.reset() },
        )
    }
