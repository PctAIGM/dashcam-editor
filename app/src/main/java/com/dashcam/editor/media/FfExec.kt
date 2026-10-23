package com.dashcam.editor.media

import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.SessionState
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * ffmpeg-kit 统一异步执行入口。
 * 同步 execute() 在部分线程上下文下不返回，统一使用 executeAsync + Deferred 等待。
 * 注意：该维护分支未打包 ffprobe 原生库，FFprobeKit 不可用（探测走系统 API）。
 */
object FfExec {

    /** No total time limit: a long software encode is allowed as long as it makes progress. */
    suspend fun encode(
        args: Array<String>,
        output: File,
        onStatistics: (Statistics) -> Unit,
        idleTimeoutMs: Long = 120_000,
    ): FFmpegSession? {
        val done = CompletableDeferred<FFmpegSession>()
        val latest = AtomicReference<Statistics?>(null)
        val watchdog = EncodingWatchdog(SystemClock.elapsedRealtime(), idleTimeoutMs)
        val (session, nativeOperation) = startFileSession(args, done, StatisticsCallback { latest.set(it) })
        var stopped = false
        try {
            while (true) {
                val completed = withTimeoutOrNull(1_000) { done.await() }
                if (completed != null || session.hasEnded()) {
                    stopped = true
                    return completed ?: session
                }
                val stats = latest.get()
                if (stats != null) onStatistics(stats)
                if (watchdog.isStalled(
                        SystemClock.elapsedRealtime(), stats?.time?.toLong() ?: 0L,
                        stats?.videoFrameNumber ?: 0, maxOf(stats?.size ?: 0L, output.length()),
                    )) {
                    // cancel() only requests termination. Do not start a fallback while the
                    // old native encoder can still write to the same file or hold a codec.
                    stopped = cancelAndWait(session, done)
                    if (!stopped) throw IOException("编码器停止响应，取消后仍未退出，请重启应用后重试")
                    return null
                }
            }
        } finally {
            if (!stopped) cancelAndWait(session, done)
            if (session.hasEnded()) nativeOperation.close()
        }
    }

    private fun FFmpegSession.hasEnded(): Boolean =
        state == SessionState.COMPLETED || state == SessionState.FAILED

    private fun startFileSession(
        args: Array<String>,
        done: CompletableDeferred<FFmpegSession>,
        statistics: StatisticsCallback? = null,
    ): Pair<FFmpegSession, Closeable> {
        // Native work can outlive coroutine cancellation. Keep the cache busy until it exits.
        val operation = CacheMaintenance.storage.beginOperation()
        try {
            val session = FFmpegKit.executeWithArgumentsAsync(args, FFmpegSessionCompleteCallback {
                operation.close()
                done.complete(it)
            }, null, statistics)
            return session to operation
        } catch (e: Throwable) {
            operation.close()
            throw e
        }
    }

    private suspend fun cancelAndWait(session: FFmpegSession, done: CompletableDeferred<FFmpegSession>): Boolean =
        withContext(NonCancellable) {
            FFmpegKit.cancel(session.sessionId)
            withTimeoutOrNull(10_000) {
                while (!session.hasEnded()) {
                    if (withTimeoutOrNull(250) { done.await() } != null) break
                }
            }
            session.hasEnded() || done.isCompleted
        }

    suspend fun ffmpegArgs(args: Array<String>, timeoutMs: Long = 600_000): FFmpegSession? {
        val done = CompletableDeferred<FFmpegSession>()
        val (session, nativeOperation) = startFileSession(args, done)
        return try {
            withTimeoutOrNull(timeoutMs) { done.await() }.also {
                if (it == null) FFmpegKit.cancel(session.sessionId)
            }
        } catch (e: CancellationException) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            throw e
        } catch (_: Exception) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            null
        } finally {
            if (session.hasEnded()) nativeOperation.close()
        }
    }

    suspend fun ffmpegText(cmd: String, timeoutMs: Long = 20_000): FFmpegSession? {
        val done = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeAsync(cmd, FFmpegSessionCompleteCallback { s -> done.complete(s) })
        return try {
            withTimeoutOrNull(timeoutMs) { done.await() }.also {
                if (it == null) FFmpegKit.cancel(session.sessionId)
            }
        } catch (e: CancellationException) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            throw e
        } catch (_: Exception) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            null
        }
    }
}

