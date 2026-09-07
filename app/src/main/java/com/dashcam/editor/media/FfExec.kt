package com.dashcam.editor.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * ffmpeg-kit 统一异步执行入口。
 * 同步 execute() 在部分线程上下文下不返回，统一使用 executeAsync + Deferred 等待。
 * 注意：该维护分支未打包 ffprobe 原生库，FFprobeKit 不可用（探测走系统 API）。
 */
object FfExec {

    suspend fun ffmpegArgs(args: Array<String>, timeoutMs: Long = 600_000): FFmpegSession? {
        val done = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            FFmpegSessionCompleteCallback { s -> done.complete(s) },
        )
        return try {
            withTimeout(timeoutMs) { done.await() }
        } catch (_: Exception) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            null
        }
    }

    suspend fun ffmpegText(cmd: String, timeoutMs: Long = 20_000): FFmpegSession? {
        val done = CompletableDeferred<FFmpegSession>()
        FFmpegKit.executeAsync(cmd, FFmpegSessionCompleteCallback { s -> done.complete(s) })
        return try {
            withTimeout(timeoutMs) { done.await() }
        } catch (_: Exception) {
            null
        }
    }
}

