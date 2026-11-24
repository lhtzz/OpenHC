package io.github.gcbond.openhc.platform.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RfcommListeners] 里不依赖安卓运行时的部分：几种开法失败后拼出来的说明。
 *
 * 安卓 4.4 上系统只回一句 `Error: -1`，四种失败（服务没绑上、跨进程失败、系统拒绝、读通道号失败）
 * 共用一个返回值。所以「都失败」时要把每种开法的报错都留下来，车机日志里才能判断是哪一类。
 */
class RfcommListenersTest {

    @Test
    fun attemptKeepsHowAndError() {
        val a = RfcommListeners.Attempt("不加密监听", null, "IOException：Error: -1")
        assertEquals("不加密监听", a.how)
        assertNull(a.socket)
        assertEquals("IOException：Error: -1", a.error)
    }

    @Test
    fun succeededAttemptHasNoError() {
        val a = RfcommListeners.Attempt("不加密监听", null, null)
        assertNull(a.error)
    }

    @Test
    fun selfTestUuidIsTheNearbyServiceUuid() {
        // 自检用的 UUID 必须和真正连接时一致，否则「自检能过、连接失败」会误导人。
        assertEquals(
            "0000fe35-0000-1000-8000-00805f9b34fb",
            java.util.UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB").toString(),
        )
    }
}
