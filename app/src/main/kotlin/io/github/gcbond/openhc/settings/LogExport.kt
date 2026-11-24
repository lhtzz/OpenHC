package io.github.gcbond.openhc.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.gcbond.openhc.R
import java.io.File
import java.io.IOException

/**
 * 把诊断日志弄出车机：分享给别人，或另存到车机上的指定目录。
 *
 * 日志本身写在应用私有目录里（见 [DiagnosticLog]），别的应用和车机的文件管理器都进不去，
 * 所以必须由应用自己复制一份出来：
 *
 * - 安卓 4.4（API 19）起用系统的「另存为」界面（`ACTION_CREATE_DOCUMENT`），用户在车机的文件选择器里
 *   自己挑目录（U 盘、内部存储都行），不用申请存储权限，也不用管分区存储的差异。
 * - 安卓 4.1～4.3 没有这个界面，退回直接写到外置存储的 `OpenHC/` 目录（那几版存储权限安装即授予）。
 * - 「分享」走 `ACTION_SEND`，用 FileProvider 把缓存里的副本授权给接收方，可以直接发微信、蓝牙、邮件。
 *
 * 都是复制、不动原文件：导出的同时日志继续写，方便导完接着复现问题。
 */
object LogExport {
    /** 另存为用的请求码，由 [Activity.onActivityResult] 收结果。 */
    const val REQ_SAVE = 1004

    /** 分享时缓存副本放的目录（相对 `cacheDir`），要和 `res/xml/file_paths.xml` 里的 `cache-path` 对上。 */
    private const val CACHE_SUBDIR = "logs"

    /** 安卓 4.1～4.3 没有「另存为」界面，直接写这里。 */
    private const val LEGACY_DIR = "OpenHC"

    /** 这个系统有没有系统的「另存为」界面。 */
    fun canSaveToChosenFolder(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.KITKAT

    /**
     * 拉起「另存为」界面，让用户挑目录。返回 false 表示这个系统没有这个界面（调用方改用 [saveToLegacyDir]）。
     */
    fun launchSave(activity: Activity): Boolean {
        if (!canSaveToChosenFolder()) return false
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, DiagnosticLog.exportName())
        }
        return try {
            activity.startActivityForResult(intent, REQ_SAVE)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** 收到「另存为」的结果后把日志写进去；返回给用户看的一句话。 */
    fun finishSave(context: Context, uri: Uri?): String {
        if (uri == null) return context.getString(R.string.log_export_cancelled)
        return try {
            val written = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                val text = DiagnosticLog.snapshot()
                if (text.isEmpty()) return@use 0L
                val bytes = text.toByteArray(Charsets.UTF_8)
                out.write(bytes)
                out.flush()
                bytes.size.toLong()
            } ?: 0L
            if (written == 0L) context.getString(R.string.log_export_empty) else context.getString(R.string.log_export_done, friendlyName(uri))
        } catch (e: IOException) {
            context.getString(R.string.log_export_failed, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            context.getString(R.string.log_export_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 安卓 4.1～4.3：直接写到外置存储的 `OpenHC/openhc-diagnostic-<时间>.log`。
     * 返回给用户看的一句话（含完整路径，方便他去找）。
     */
    @Suppress("DEPRECATION")
    fun saveToLegacyDir(context: Context): String {
        val text = DiagnosticLog.snapshot()
        if (text.isEmpty()) return context.getString(R.string.log_export_empty)
        return try {
            val root = Environment.getExternalStorageDirectory()
            if (root == null) return context.getString(R.string.log_export_failed, "读不到外置存储")
            val dir = File(root, LEGACY_DIR)
            if (!dir.exists() && !dir.mkdirs()) return context.getString(R.string.log_export_failed, "建不了目录 ${dir.absolutePath}")
            val target = File(dir, DiagnosticLog.exportName())
            target.writeText(text, Charsets.UTF_8)
            context.getString(R.string.log_export_done, target.absolutePath)
        } catch (e: IOException) {
            context.getString(R.string.log_export_failed, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            context.getString(R.string.log_export_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 分享：把日志复制一份到缓存目录，用 FileProvider 生成可授权的 uri，拉起系统分享面板。
     * 返回 false 表示没有可用的分享目标（或复制失败），调用方提示用户。
     */
    fun share(context: Context): Boolean {
        val text = DiagnosticLog.snapshot()
        if (text.isEmpty()) {
            Toast.makeText(context, R.string.log_export_empty, Toast.LENGTH_LONG).show()
            return true
        }
        val copy = try {
            val dir = File(context.cacheDir, CACHE_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            // 只留一份，避免缓存越积越多。
            dir.listFiles()?.forEach { it.delete() }
            File(dir, DiagnosticLog.exportName()).apply { writeText(text, Charsets.UTF_8) }
        } catch (e: IOException) {
            Toast.makeText(context, context.getString(R.string.log_export_failed, e.message ?: "写不了缓存文件"), Toast.LENGTH_LONG).show()
            return true
        }
        val uri = try {
            FileProvider.getUriForFile(context, authority(context), copy)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, context.getString(R.string.log_export_failed, e.message ?: "生成分享链接失败"), Toast.LENGTH_LONG).show()
            return true
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share)))
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_LONG).show()
            true
        }
    }

    /** FileProvider 的 authority，和 `AndroidManifest.xml` 里声明的一致。 */
    private fun authority(context: Context): String = "${context.packageName}.logfiles"

    /** 从 uri 里取个短名字给提示用。 */
    private fun friendlyName(uri: Uri): String {
        val path = uri.lastPathSegment ?: return uri.toString()
        val decoded = Uri.decode(path)
        return decoded.substringAfterLast('/').ifEmpty { decoded }
    }
}
