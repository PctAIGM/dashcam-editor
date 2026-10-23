package com.dashcam.editor.media

/** Only advancing media or output counts as work; repeated statistics callbacks do not. */
internal class EncodingWatchdog(startMs: Long, private val idleTimeoutMs: Long) {
    private var lastWorkMs = startMs
    private var mediaTimeMs = 0L
    private var frames = 0
    private var bytes = 0L

    fun isStalled(nowMs: Long, timeMs: Long, frameCount: Int, outputBytes: Long): Boolean {
        if (timeMs > mediaTimeMs || frameCount > frames || outputBytes > bytes) {
            lastWorkMs = nowMs
        }
        mediaTimeMs = maxOf(mediaTimeMs, timeMs)
        frames = maxOf(frames, frameCount)
        bytes = maxOf(bytes, outputBytes)
        return nowMs - lastWorkMs >= idleTimeoutMs
    }
}
