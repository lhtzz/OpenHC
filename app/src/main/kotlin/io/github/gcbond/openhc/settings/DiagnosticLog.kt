package io.github.gcbond.openhc.settings

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接诊断日志：把协议层的关键事件同时写进 logcat 和一个本地文件。
 *
 * 为什么需要它：车机上的普通应用读不到 logcat（安卓 4.1 起 `READ_LOGS` 是系统签名权限），
 * 有些车机的 adb shell 也被限制了，`adb logcat` 拿不到东西。要让用户能把日志发出来，只能应用自己落盘。
 *
 * 日志固定写在应用私有目录（`/data/data/io.github.gcbond.openhc/files/openhc-diagnostic.log`），
 * 这个目录别的应用和车机的文件管理器都进不去，所以界面上另有「导出 / 分享」把它复制出来（见 LogExport）。
 * 文件固定一个，超过上限就丢掉前面一半，避免占满车机存储。
 *
 * 只记协议和音频的关键节点（连上、协商结果、建播放器、放不了、断开原因），不记画面内容和密钥。
 */
object DiagnosticLog {
    /** logcat 里用的标签；文件里每行也带上它，方便对照。 */
    const val TAG = "OpenHC-Diag"

    private const val FILE_NAME = "openhc-diagnostic.log"

    /** 超过这么多就裁剪。 */
    private const val MAX_BYTES = 512 * 1024L

    /** 裁剪时只留最后这么多字节：前面的老日志丢掉，最近的现场留着。 */
    private const val KEEP_BYTES = 256 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private var file: File? = null
    private val lock = Any()

    /** 应用启动时调一次。拿不到私有目录就只往 logcat 写。 */
    fun init(context: Context) {
        synchronized(lock) {
            file = try {
                File(context.applicationContext.filesDir, FILE_NAME)
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    /** 当前日志文件；没有初始化或不可写时为 null。 */
    fun file(): File? = synchronized(lock) { file }

    /** 日志文件的绝对路径，界面上显示给用户看；没有可写目录时返回一句说明。 */
    fun location(): String = synchronized(lock) { file?.absolutePath } ?: "没有可写的日志目录"

    /** 导出时用的文件名：带导出时间，多次导出不会互相覆盖。 */
    fun exportName(): String = "openhc-diagnostic-${nameFormat.format(Date())}.log"

    /**
     * 记一条。`tag` 用 `OpenHC-Audio`、`OpenHC-Wireless` 这类原有标签，文件里能看出是哪一层的。
     * 写文件失败只往 logcat 记一次，不抛异常——诊断日志不能影响投屏。
     */
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val target = synchronized(lock) { file } ?: return
        val line = "${timeFormat.format(Date())} $tag/$message\n"
        try {
            synchronized(lock) {
                if (target.length() > MAX_BYTES) trim(target)
                target.appendText(line, Charsets.UTF_8)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "写诊断日志失败：${e.javaClass.simpleName}")
        } catch (e: IOException) {
            Log.w(TAG, "写诊断日志失败：${e.javaClass.simpleName}")
        }
    }

    /** 清空日志（设置页「清空日志」用）。 */
    fun clear() {
        val target = synchronized(lock) { file } ?: return
        try {
            target.delete()
        } catch (e: RuntimeException) {
            Log.w(TAG, "清空诊断日志失败：${e.javaClass.simpleName}")
        }
    }

    /** 读出全部内容；没有日志时返回提示文字。 */
    fun read(): String {
        val target = synchronized(lock) { file } ?: return "没有可用的日志文件"
        return try {
            if (!target.exists()) "日志还是空的（这次启动后还没有连接过）" else target.readText(Charsets.UTF_8)
        } catch (e: RuntimeException) {
            "读日志失败：${e.javaClass.simpleName}"
        } catch (e: IOException) {
            "读日志失败：${e.javaClass.simpleName}"
        }
    }

    /** 日志全文；没有日志时是空串。导出、分享都从这里取，复制而不是移动，导完日志继续写。 */
    fun snapshot(): String {
        val target = synchronized(lock) { file } ?: return ""
        return try {
            if (target.exists()) target.readText(Charsets.UTF_8) else ""
        } catch (e: IOException) {
            ""
        } catch (e: RuntimeException) {
            ""
        }
    }

    /**
     * 超过上限时只留最后 [KEEP_BYTES] 字节，从下一行开头算起（避免第一行只剩半截时间戳）。
     * 以前是整个文件删掉重来，一次会丢光历史日志。
     */
    private fun trim(target: File) {
        val all = target.readBytes()
        if (all.size <= KEEP_BYTES) return
        var start = all.size - KEEP_BYTES.toInt()
        // 往后找到第一个换行，从下一行开头留下。
        while (start < all.size && all[start] != '\n'.code.toByte()) start++
        if (start < all.size) start++
        target.writeBytes(all.copyOfRange(start, all.size))
    }
}
