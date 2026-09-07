package com.dashcam.editor.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.dashcam.editor.util.Tc
import java.io.File

/**
 * 多文件"虚拟时间轴"播放控制器：
 * 把多个视频按顺序拼接成一条总时间轴，seek/逐帧/跳转都在总时间轴坐标系进行。
 */
class PlayerController(context: Context, private val clips: List<ClipInfo>) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        setMediaItems(clips.map { MediaItem.fromUri(Uri.fromFile(File(it.filePath))) })
        prepare()
        playWhenReady = false
    }

    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }

    fun offsetOf(index: Int): Long = clips.subList(0, index.coerceIn(0, clips.size)).sumOf { it.durationMs }

    fun clipIndexForGlobal(ms: Long): Int {
        var acc = 0L
        clips.forEachIndexed { i, c ->
            if (ms < acc + c.durationMs) return i
            acc += c.durationMs
        }
        return clips.lastIndex
    }

    fun globalPositionMs(): Long {
        val idx = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val local = player.currentPosition.coerceIn(0, clips[idx].durationMs)
        return offsetOf(idx) + local
    }

    fun seekToGlobal(ms: Long) {
        val g = ms.coerceIn(0, totalDurationMs)
        val idx = clipIndexForGlobal(g)
        player.seekTo(idx, g - offsetOf(idx))
    }

    /** 拖动中的快速预览 seek（关键帧对齐，跟手不卡顿） */
    fun seekScrub(ms: Long) {
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        seekToGlobal(ms)
    }

    /** 拖动结束的精确 seek（回到帧精确模式并落在准确帧上） */
    fun seekSettle(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        seekToGlobal(ms)
    }

    fun clipAtGlobal(ms: Long): ClipInfo = clips[clipIndexForGlobal(ms)]

    fun currentClip(): ClipInfo = clips[player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)]

    /** 逐帧步进：按当前所在片段的帧率量化，避免舍入漂移 */
    fun stepFrames(frames: Int) {
        player.pause()
        val index = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val clip = clips[index]
        val fps = if (clip.fps > 1.0) clip.fps else 30.0
        // Frame stepping is local to the current file. Using the global timestamp
        // here makes the first frame after a clip boundary jump by the previous
        // clips' durations, especially when clips use different frame rates.
        val localFrame = Tc.msToFrame(player.currentPosition.coerceIn(0, clip.durationMs), fps)
        val targetLocal = Tc.frameToMs(localFrame + frames, fps)
        seekToGlobal(offsetOf(index) + targetLocal)
    }

    fun jumpSeconds(sec: Double) {
        seekToGlobal(globalPositionMs() + (sec * 1000).toLong())
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun release() {
        player.release()
    }
}
