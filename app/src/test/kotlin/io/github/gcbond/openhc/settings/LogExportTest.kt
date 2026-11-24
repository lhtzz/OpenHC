package io.github.gcbond.openhc.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 导出日志里不依赖安卓运行时的判断：哪个版本有系统的「另存为」界面。
 *
 * 安卓 4.4（API 19）起才有 `ACTION_CREATE_DOCUMENT`；4.1～4.3 只能直接写外置存储。
 * 这条分界线决定界面上写「挑目录」还是写死 `/sdcard/OpenHC/`，弄错了老车机上会点了没反应。
 */
class LogExportTest {

    @Test
    fun kitkatAndNewerCanPickAFolder() {
        for (sdk in 19..36) {
            assertTrue(LogExport.canSaveToChosenFolder(sdk), "安卓 API $sdk 应该有「另存为」界面")
        }
    }

    @Test
    fun olderSystemsWriteToExternalStorageInstead() {
        for (sdk in 16..18) {
            assertEquals(false, LogExport.canSaveToChosenFolder(sdk), "安卓 API $sdk 没有「另存为」界面")
        }
    }
}
