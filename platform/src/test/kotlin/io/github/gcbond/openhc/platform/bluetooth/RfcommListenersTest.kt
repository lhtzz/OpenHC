package io.github.gcbond.openhc.platform.bluetooth

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RfcommListeners] 里不依赖安卓运行时的部分：几种开法失败后拼出来的说明，以及重试节奏。
 *
 * 安卓 4.4 上系统只回一句 `Error: -1`，四种失败（服务没绑上、跨进程失败、系统拒绝、读通道号失败）
 * 共用一个返回值。所以「都失败」时要把每种开法的报错都留下来，车机日志里才能判断是哪一类。
 *
 * 重试次数和间隔对齐这台车机蓝牙协议栈自己的做法（反编译 `com.android.bluetooth` 4.4.2：
 * `BluetoothOppRfcommListener` / `BluetoothPbapService` 都是 10 次 × 300ms）。
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

    @Test
    fun retryConstantsMatchTheCarBluetoothStack() {
        // 和这台车机 com.android.bluetooth 里的 10 次 × 300ms 保持一致。
        assertEquals(10, RfcommListeners.ROUNDS)
        assertEquals(300L, RfcommListeners.INTERVAL_MS)
    }

    @Test
    fun allOpenersFailKeepsEveryError() {
        val sleeps = mutableListOf<Long>()
        val opened = RfcommListeners.openWithRetry(
            openers = listOf(
                "不加密监听" to { throw IOException("Error: -1") },
                "只加密监听" to { throw IOException("Error: -1") },
                "加密+认证监听" to { throw IOException("Error: -1") },
            ),
            rounds = 2,
            intervalMillis = 300L,
            sleep = { sleeps += it },
        )
        assertNull(opened.socket)
        assertEquals(2, opened.rounds)
        // 两轮之间只等一次。
        assertEquals(listOf(300L), sleeps)
        // 三种开法都要出现在说明里，日志才能判断是哪一类被拒。
        assertTrue(opened.how.contains("不加密监听"))
        assertTrue(opened.how.contains("只加密监听"))
        assertTrue(opened.how.contains("加密+认证监听"))
        assertEquals("IOException：Error: -1", opened.error)
    }

    @Test
    fun retriesTenRoundsWithThreeSecondTotalWait() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val opened = RfcommListeners.openWithRetry(
            openers = listOf(
                "不加密监听" to {
                    calls++
                    throw IOException("Error: -1")
                },
            ),
            sleep = { sleeps += it },
        )
        assertEquals(RfcommListeners.ROUNDS, calls)
        assertEquals(RfcommListeners.ROUNDS - 1, sleeps.size)
        assertEquals(2_700L, sleeps.sum())
        assertEquals(RfcommListeners.ROUNDS, opened.rounds)
    }

    @Test
    fun cancelStopsRetryingEarly() {
        var rounds = 0
        val opened = RfcommListeners.openWithRetry(
            openers = listOf(
                "不加密监听" to {
                    rounds++
                    throw IOException("Error: -1")
                },
            ),
            intervalMillis = 300L,
            sleep = { },
            cancelled = { rounds >= 2 },
        )
        // 第二轮试完就被取消，不会试满 10 轮。
        assertEquals(2, rounds)
        assertNull(opened.socket)
        assertEquals(2, opened.rounds)
    }

    @Test
    fun roundsMustBePositive() {
        val failure = runCatching {
            RfcommListeners.openWithRetry(openers = emptyList(), rounds = 0, sleep = { })
        }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
