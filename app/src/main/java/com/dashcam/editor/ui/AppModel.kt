package com.dashcam.editor.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.dashcam.editor.media.ClipInfo

enum class Screen { Library, Edit }

data class ShotItem(
    val uri: Uri,
    val displayName: String,
    val clipName: String,
    val timeLabel: String,
    val frameNo: Long,
    val thumb: ImageBitmap?,
)

/** 全局应用状态。Activity 因 configChanges 不重建，remember 住即可在横竖屏切换时保留 */
class AppModel {
    var clips by mutableStateOf<List<ClipInfo>>(emptyList())
    var screen by mutableStateOf(Screen.Library)
    var inMs by mutableLongStateOf(0L)
    var outMs by mutableLongStateOf(0L)
    var shots by mutableStateOf<List<ShotItem>>(emptyList())

    val totalMs: Long get() = clips.sumOf { it.durationMs }

    /** Keep a trimmed range attached to the same content; a full selection stays full. */
    fun insertClips(index: Int, addedClips: List<ClipInfo>) {
        if (addedClips.isEmpty()) return
        val at = index.coerceIn(0, clips.size)
        val boundary = clips.take(at).sumOf { it.durationMs }
        val addedMs = addedClips.sumOf { it.durationMs }
        val fullRange = inMs == 0L && outMs == totalMs
        if (!fullRange) {
            if (inMs >= boundary) inMs += addedMs
            if (outMs > boundary) outMs += addedMs
        }
        clips = clips.take(at) + addedClips + clips.drop(at)
        if (fullRange) outMs = totalMs
        validateRange()
    }

    /** 片段列表变化（追加/移除）后校正入出点 */
    fun validateRange() {
        if (clips.isEmpty()) {
            inMs = 0
            outMs = 0
            return
        }
        inMs = inMs.coerceIn(0, totalMs)
        outMs = outMs.coerceIn(0, totalMs)
        if (outMs <= inMs) {
            inMs = 0
            outMs = totalMs
        }
    }
}
