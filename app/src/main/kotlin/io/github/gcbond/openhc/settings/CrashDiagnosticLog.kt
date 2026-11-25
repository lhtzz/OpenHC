package io.github.gcbond.openhc.settings

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 只记录最近一次未捕获的 Java/Kotlin 异常；在没有 ADB 的车机上也能看。 */
internal object CrashDiagnosticLog {
    private const val FILE_NAME = "openhc-last-crash.log"
    private const val MAX_BYTES = 24_576L

    fun install(context: Context, version: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        if (previous is RecordingCrashHandler) return
        try {
            val target = crashFile(context) ?: return
            Thread.setDefaultUncaughtExceptionHandler(RecordingCrashHandler(previous) { thread, error ->
                // 不进入普通连接日志的锁。同步写一个小文件后，照常交给系统处理崩溃。
                val now = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val report = "$now OpenHC-Crash：$version / API ${Build.VERSION.SDK_INT} / ${thread.name.take(80)}\n" +
                    diagnosticStack(error) + "\n"
                FileOutputStream(target).use { stream ->
                    stream.write(report.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            })
        } catch (_: RuntimeException) {
            // 拿不到可用的应用目录或默认处理器不可修改时，不影响启动。
        }
    }

    private fun crashFile(context: Context): File? = context.filesDir?.let { File(it, FILE_NAME) }

    fun read(context: Context): String? = try {
        val target = crashFile(context)
        if (target != null && target.length() in 1..MAX_BYTES) target.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

/** 不保留异常原文，避免异常消息里含手机发来的报文、连接码或凭据。 */
internal fun diagnosticStack(error: Throwable): String = buildString {
    var cause: Throwable? = error
    repeat(3) { index ->
        val current = cause ?: return@buildString
        if (index != 0) append("\nCaused by: ")
        append(current.javaClass.name)
        for (frame in current.stackTrace.take(12)) {
            append("\n    at ").append(frame.toString().take(180))
        }
        cause = current.cause?.takeUnless { it === current }
    }
}.take(6_500)

internal class RecordingCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler,
    private val record: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val started = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (started.compareAndSet(false, true)) record(thread, error)
        } catch (_: Throwable) {
            // 连诊断本身也可能在低内存或存储不可写时失败；不能挡住系统原有的处理。
        } finally {
            previous.uncaughtException(thread, error)
        }
    }
}
