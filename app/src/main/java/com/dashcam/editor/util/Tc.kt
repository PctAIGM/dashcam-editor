package com.dashcam.editor.util

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Tc {

    /** 解析 ffprobe 的帧率字符串，如 "30/1"、"30000/1001" */
    fun parseFps(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        return try {
            if (s.contains('/')) {
                val parts = s.split('/')
                if (parts.size == 2) {
                    val n = parts[0].trim().toDouble()
                    val d = parts[1].trim().toDouble()
                    if (d == 0.0) 0.0 else n / d
                } else 0.0
            } else s.trim().toDouble()
        } catch (_: Exception) {
            0.0
        }
    }

    fun msToFrame(ms: Long, fps: Double): Long {
        if (fps <= 0.0) return 0
        return (ms * fps / 1000.0).roundToLong()
    }

    fun frameToMs(frame: Long, fps: Double): Long {
        if (fps <= 0.0) return 0
        return (frame * 1000.0 / fps).roundToLong()
    }

    /** 时:分:秒.帧（两位帧号，按所在秒内位置计算） */
    fun format(ms: Long, fps: Double): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val fpsI = if (fps > 0) fps.toInt() else 25
        val frame = ((t % 1000) * fps / 1000.0).roundToInt().coerceIn(0, fpsI - 1)
        return String.format(Locale.US, "%02d:%02d:%02d.%02d", h, m, s, frame)
    }

    /** 简短时长 mm:ss */
    fun formatShort(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val m = t / 60_000
        val s = t / 1000 % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 紧凑时间码：分:秒.帧（超过1小时仍用完整格式），用于空间紧张的条带 */
    fun formatCompact(ms: Long, fps: Double): String {
        val t = ms.coerceAtLeast(0)
        if (t >= 3_600_000) return format(t, fps)
        val m = t / 60_000
        val s = t / 1000 % 60
        val fpsI = if (fps > 0) fps.toInt() else 25
        val frame = ((t % 1000) * fps / 1000.0).roundToInt().coerceIn(0, fpsI - 1)
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, frame)
    }

    /** 秒字符串（小数 3 位），用于 ffmpeg 参数 */
    fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
