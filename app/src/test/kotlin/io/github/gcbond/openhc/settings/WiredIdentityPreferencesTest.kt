package io.github.gcbond.openhc.settings

import android.content.SharedPreferences
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WiredIdentityPreferencesTest {
    private val manual = "02:14:23:34:45:56"
    private val system = "20:11:22:33:44:55"

    @Test
    fun missingAddressIsSavedOnceAndSurvivesOpeningThePreferencesAgain() {
        val file = PreferenceFile()
        val settings = AppSettings(file.open())
        assertNull(settings.wiredBluetoothChoice(null).address, "设置页预览不能生成地址")
        val initial = settings.wiredBluetoothChoice(null, createIfMissing = true)
        assertEquals(BluetoothSource.WIRED_LOCAL, initial.source)
        assertTrue(BluetoothAddressPolicy.isLocallyAdministered(assertNotNull(initial.address)))
        val restarted = AppSettings(file.open())
        assertEquals(initial, restarted.wiredBluetoothChoice(system, createIfMissing = true))
        assertNull(restarted.manualBluetoothAddress)
        assertNull(BluetoothAddressPolicy.chooseForWireless(null, restarted.manualBluetoothAddress).address)
        assertEquals(1, file.commits)
    }

    @Test
    fun upgradingPreservesTheManualAddressAndMovingItDoesNotChangeTheWiredIdentity() {
        val file = PreferenceFile()
        val previous = AppSettings(file.open()).apply { manualBluetoothAddress = manual; fullScreen = false }
        val before = previous.wiredBluetoothChoice(null, createIfMissing = true)
        assertEquals(manual, before.address)
        assertEquals(0, file.commits)

        assertTrue(previous.useManualBluetoothForWiredOnly())
        val restarted = AppSettings(file.open())
        assertEquals(before.address, restarted.wiredBluetoothChoice(system).address)
        assertNull(restarted.manualBluetoothAddress)
        assertFalse(restarted.fullScreen)
        restarted.manualBluetoothAddress = system
        assertEquals(system, BluetoothAddressPolicy.chooseForWireless(null, restarted.manualBluetoothAddress).address)
        assertEquals(manual, restarted.wiredBluetoothChoice(null).address)
        assertTrue(restarted.clearWiredLocalAddress())
        assertEquals(system, AppSettings(file.open()).wiredBluetoothChoice(null).address)
    }

    @Test
    fun failedPersistenceNeverUsesAnUnsavedIdentityOrDropsTheManualAddress() {
        val file = PreferenceFile()
        val settings = AppSettings(file.open())
        file.failNextCommit = true
        assertNull(settings.wiredBluetoothChoice(null, createIfMissing = true).address)
        assertNull(settings.wiredBluetoothChoice(null).address)
        assertNull(AppSettings(file.open()).wiredBluetoothChoice(null).address)

        settings.manualBluetoothAddress = manual
        file.failNextCommit = true
        assertFalse(settings.useManualBluetoothForWiredOnly())
        assertEquals(manual, settings.manualBluetoothAddress)
        val restarted = AppSettings(file.open())
        assertEquals(manual, restarted.manualBluetoothAddress)
        assertEquals(BluetoothSource.MANUAL, restarted.wiredBluetoothChoice(null).source)
    }

    /** 模拟写盘失败时先更新内存的 SharedPreferences 语义。每次 open 从磁盘副本重新读取。 */
    private class PreferenceFile {
        private val disk = mutableMapOf<String, Any?>()
        var commits = 0
        var failNextCommit = false

        fun open(): SharedPreferences {
            val live = disk.toMutableMap()
            return proxy(SharedPreferences::class.java) { _, method, args ->
                when (method) {
                    "getString", "getBoolean" -> live[args[0]] ?: args[1]
                    "edit" -> editor(live)
                    else -> error("Unexpected preferences call: $method")
                }
            }
        }

        private fun editor(live: MutableMap<String, Any?>): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            fun updateLive() {
                for ((key, value) in changes) if (value == null) live.remove(key) else live[key] = value
            }
            fun persist() {
                disk.clear()
                disk.putAll(live)
            }
            return proxy(SharedPreferences.Editor::class.java) { self, method, args ->
                when (method) {
                    "putString", "putBoolean" -> self.also { changes[args[0] as String] = args[1] }
                    "remove" -> self.also { changes[args[0] as String] = null }
                    "apply" -> { updateLive(); persist(); null }
                    "commit" -> {
                        commits++
                        updateLive()
                        val ok = !failNextCommit
                        failNextCommit = false
                        if (ok) persist()
                        ok
                    }
                    else -> error("Unexpected editor call: $method")
                }
            }
        }

        private fun <T> proxy(type: Class<T>, handler: (Any, String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                handler(self, method.name, args ?: emptyArray())
            })
    }
}
