package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.IOException
import java.lang.reflect.Method
import java.util.UUID

/**
 * 开 RFCOMM 服务端监听的几种方式，按「手机还能不能按 UUID 找到我们」的优先级依次试。
 *
 * 为什么需要多试：安卓 4.4 的 `BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord` 里，系统调用失败只抛
 * `IOException("Error: -1")`（源码 `bindListen()` 的四种失败共用一个返回值），光看这句话分不出是
 * 「系统不许第三方应用开监听」「服务记录（SDP）登记被拒」还是「RFCOMM 层整个不可用」。
 * 有的车机（实测安卓 4.4.3 车机）只让系统自带的音频应用用蓝牙，第三方应用的「不加密监听」直接被拒，
 * 但换一种开法可能就通了。
 *
 * 三种带服务记录的开法（手机能按 UUID 查到我们，是真正可用的）：
 * 1. [Adapter.listenUsingInsecureRfcommWithServiceRecord]：不加密、不要求认证（默认用这个）；
 * 2. `listenUsingRfcommWithServiceRecord`：要求认证 + 加密（手机上可能要按一次配对确认）；
 * 3. `listenUsingEncryptedRfcommWithServiceRecord`：只要加密、不要求认证（隐藏接口，反射调）。
 *
 * 都不成时再试「指定通道号开监听」（`listenUsingInsecureRfcommOn` / `listenUsingRfcommOn`，都是隐藏接口）：
 * 这种开法不登记服务记录，手机找不到我们，**不能真用**；只是为了分辨「RFCOMM 层本身能不能开」和
 * 「只是服务记录登记被拒」。试完立刻关掉。
 */
object RfcommListeners {

    /** 一次尝试的结果。[socket] 不为 null 表示开成了（调用方负责关）。 */
    class Attempt(val how: String, val socket: BluetoothServerSocket?, val error: String?)

    /** 带服务记录的开法，手机能按 UUID 找到我们。返回第一个开成的；都失败时 socket 为 null。 */
    fun openWithServiceRecord(adapter: BluetoothAdapter, serviceName: String, uuid: UUID): Attempt {
        val attempts = mutableListOf<Attempt>()
        attempts += attempt("不加密监听") {
            @Suppress("DEPRECATION")
            adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
        }
        // 只加密、不要求认证：手机不用先配对就能连，行为和不加密最接近，排在第二。
        attempts += attempt("只加密监听") {
            invokeListenWithRecord(adapter, "listenUsingEncryptedRfcommWithServiceRecord", serviceName, uuid)
        }
        // 要求认证 + 加密：手机上可能会弹配对确认，只在前面都不成时才用。
        attempts += attempt("加密+认证监听") {
            @Suppress("DEPRECATION")
            adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
        }
        attempts.firstOrNull { it.socket != null }?.let { opened ->
            // 退到了后面的开法：记一笔，方便知道这台车机拒的是哪一种（下次可以直接从能用的那种开始）。
            if (opened !== attempts.first()) {
                Log.i(TAG, "「${attempts.first().how}」被拒（${attempts.first().error}），改用「${opened.how}」开成了")
            }
            return opened
        }
        // 都失败：把三种的报错拼起来交回去（车机日志里看得到，才能判断是哪一类被拒）。
        return Attempt(
            how = attempts.joinToString("、") { "${it.how} 被拒（${it.error}）" },
            socket = null,
            error = attempts.firstOrNull()?.error,
        )
    }

    /**
     * 不带服务记录的「指定通道号开监听」探针：能开说明 RFCOMM 层本身没问题，问题只在服务记录登记。
     * 试完立刻关掉。返回一句结论（给日志和界面用）。
     */
    fun probeFixedChannels(adapter: BluetoothAdapter): String {
        val insecure = probeChannels(adapter, "listenUsingInsecureRfcommOn")
        val secure = probeChannels(adapter, "listenUsingRfcommOn")
        return when {
            insecure != null -> "按通道号开监听可以（不加密，通道 $insecure），说明只是服务记录登记被拒"
            secure != null -> "按通道号开监听可以（加密，通道 $secure），说明只是服务记录登记被拒"
            else -> "按通道号开监听也不行，这台车机的蓝牙协议栈不支持给第三方应用开 RFCOMM 服务端"
        }
    }

    /**
     * 车机蓝牙的现状，失败时一起记下来：判断「蓝牙没开」「多用户」「已配对设备数」这类原因。
     * 只读公开接口和本进程信息，不改任何东西。
     */
    fun environment(adapter: BluetoothAdapter): String {
        val parts = mutableListOf<String>()
        parts += try {
            "蓝牙${if (adapter.isEnabled) "开" else "关"}"
        } catch (e: RuntimeException) {
            "读不到蓝牙开关"
        }
        parts += "本进程用户 ${Process.myUid() / PER_USER_RANGE}"
        foregroundUser()?.let { parts += "前台用户 $it" }
        parts += "已配对 ${bondedCount(adapter)} 台"
        // 系统蓝牙服务有没有绑上（拿不到就是「蓝牙没开」那一类失败，和「被拒」是两回事）。
        parts += "蓝牙服务${if (serviceBound()) "在" else "没绑上"}"
        return parts.joinToString("，")
    }

    /**
     * 蓝牙能力自检（设置页「蓝牙自检」用）：不连手机，直接把三种带服务记录的开法各试一遍，
     * 再试「按通道号开监听」探针，最后附上环境。返回一段给用户看的中文说明。
     *
     * 只用来判断这台车机给不给本应用开 RFCOMM 服务端；试出来的监听立刻关掉。
     */
    fun selfTest(context: android.content.Context): String {
        val adapter = BluetoothStatus.adapter(context) ?: return "这台车机没有蓝牙适配器。"
        val state = BluetoothStatus.state(adapter)
        if (state != BluetoothStatus.State.ON) {
            return "车机蓝牙现在是关着的（${state.name}）。这台车机没有单独的蓝牙开关时，" +
                "先在车机自带的「蓝牙音乐」里连一次手机，把蓝牙唤醒，再回来点一次「自检」。"
        }
        val sb = StringBuilder()
        sb.append("车机蓝牙：").append(environment(adapter)).append(LINE_BREAK)
        sb.append(LINE_BREAK)
        val uuid = java.util.UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB")
        val attempts = mutableListOf<Attempt>()
        attempts += attempt("不加密监听（默认用这个）") {
            @Suppress("DEPRECATION")
            adapter.listenUsingInsecureRfcommWithServiceRecord(TEST_SERVICE_NAME, uuid)
        }
        attempts += attempt("只加密监听") {
            invokeListenWithRecord(adapter, "listenUsingEncryptedRfcommWithServiceRecord", TEST_SERVICE_NAME, uuid)
        }
        attempts += attempt("加密+认证监听") {
            @Suppress("DEPRECATION")
            adapter.listenUsingRfcommWithServiceRecord(TEST_SERVICE_NAME, uuid)
        }
        var anyOk = false
        for (a in attempts) {
            val ok = a.socket != null
            if (ok) {
                anyOk = true
                val channel = RfcommChannel.read(a.socket!!)
                sb.append("可以：").append(a.how).append("（通道 ").append(if (channel == 0) "读不到" else channel).append("）").append(LINE_BREAK)
                try {
                    a.socket.close()
                } catch (e: IOException) {
                    // 关不掉不影响结论。
                }
            } else {
                sb.append("不行：").append(a.how).append(" → ").append(a.error ?: "未知原因").append(LINE_BREAK)
            }
        }
        sb.append(LINE_BREAK)
        if (anyOk) {
            sb.append("结论：这台车机能用无线连接。如果开始连接时还是失败，把诊断日志发出来。")
            return sb.toString()
        }
        // 一种都不成：再试不带服务记录的探针，区分「RFCOMM 整个不可用」和「只是服务记录被拒」。
        sb.append("补充检查：").append(probeFixedChannels(adapter)).append(LINE_BREAK)
        sb.append(LINE_BREAK)
        sb.append(
            "结论：这台车机的蓝牙不给第三方应用开监听通道（系统只回了「Error: -1」）。" +
                "系统自带的「蓝牙音乐」能用，是因为它有系统权限。可以试试：" + LINE_BREAK +
                "1. 在车机设置里把本应用加进「后台运行白名单」或「自启动允许」，有的车机只拦没授权的应用；" + LINE_BREAK +
                "2. 车机如果有多个用户（主用户 / 访客），把 OpenHC 装到主用户下；" + LINE_BREAK +
                "3. 这台车机只能改用数据线连接，无线连接用不了。",
        )
        return sb.toString()
    }

    // ---------------------------------------------------------------- 工具

    private inline fun attempt(how: String, body: () -> BluetoothServerSocket?): Attempt = try {
        val socket = body()
        Attempt(how, socket, if (socket == null) "这台系统没有这种开法" else null)
    } catch (e: IOException) {
        Attempt(how, null, describe(e))
    } catch (e: SecurityException) {
        Attempt(how, null, "没有蓝牙权限（${describe(e)}）")
    } catch (e: RuntimeException) {
        Attempt(how, null, describe(e))
    }

    /** 依次试 1～[MAX_CHANNEL] 号通道，开成第一个就立刻关掉并返回通道号；都不行返回 null。 */
    @SuppressLint("MissingPermission")
    private fun probeChannels(adapter: BluetoothAdapter, method: String): Int? {
        for (channel in 1..MAX_CHANNEL) {
            val socket = try {
                invokeListenOnChannel(adapter, method, channel)
            } catch (e: IOException) {
                continue
            } catch (e: SecurityException) {
                return null
            } catch (e: RuntimeException) {
                continue
            } ?: continue
            try {
                socket.close()
            } catch (e: IOException) {
                // 关不掉也没关系，接着报结论。
            }
            return channel
        }
        return null
    }

    /** 反射调 `listenUsingRfcommOn(int)` / `listenUsingInsecureRfcommOn(int)`（都是隐藏接口）。 */
    private fun invokeListenOnChannel(adapter: BluetoothAdapter, name: String, channel: Int): BluetoothServerSocket? {
        val method = method(name, Int::class.javaPrimitiveType ?: return null) ?: return null
        return method.invoke(adapter, channel) as? BluetoothServerSocket
    }

    /** 反射调 `listenUsingEncryptedRfcommWithServiceRecord(String, UUID)`（隐藏接口）。 */
    private fun invokeListenWithRecord(
        adapter: BluetoothAdapter,
        name: String,
        serviceName: String,
        uuid: UUID,
    ): BluetoothServerSocket? {
        val method = method(name, String::class.java, UUID::class.java) ?: return null
        return method.invoke(adapter, serviceName, uuid) as? BluetoothServerSocket
    }

    private fun method(name: String, vararg params: Class<*>): Method? = try {
        BluetoothAdapter::class.java.getMethod(name, *params)
    } catch (e: NoSuchMethodException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /**
     * 系统蓝牙服务（`IBluetooth`）有没有绑上。`BluetoothSocket.bindListen()` 在服务为空时也返回 -1
     * （日志里是「bindListen fail, reason: bluetooth is off」），所以这一项要和「被拒」区分开。
     */
    private fun serviceBound(): Boolean = try {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val field = BluetoothAdapter::class.java.getDeclaredField("mService").apply { isAccessible = true }
        field.get(adapter) != null
    } catch (e: Exception) {
        false
    } catch (e: RuntimeException) {
        false
    } catch (e: LinkageError) {
        false
    }

    /** 前台用户号（`ActivityManager.getCurrentUser()`，隐藏接口）；读不到返回 null。 */
    private fun foregroundUser(): Int? = try {
        val activityManager = Class.forName("android.app.ActivityManager")
        val getCurrentUser = activityManager.getMethod("getCurrentUser")
        (getCurrentUser.invoke(null) as? Int)
    } catch (e: Exception) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    }

    /** 已配对设备数（读不到就返回「?」，不因为没有蓝牙权限就整条诊断失败）。 */
    @SuppressLint("MissingPermission")
    private fun bondedCount(adapter: BluetoothAdapter): String = try {
        adapter.bondedDevices?.size?.toString() ?: "?"
    } catch (e: SecurityException) {
        "?"
    } catch (e: RuntimeException) {
        "?"
    }

    private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")

    /** 每个用户占的 uid 区间，用来从 uid 反推用户号。 */
    private const val PER_USER_RANGE = 100000

    /** RFCOMM 通道号上限（`BluetoothSocket.MAX_RFCOMM_CHANNEL`）。 */
    private const val MAX_CHANNEL = 30

    private const val TAG = "OpenHC-Rfcomm"

    /** 自检时用的服务名（手机不看服务名）。 */
    private const val TEST_SERVICE_NAME = "OpenHC-SelfTest"

    /** 自检结果里的换行。 */
    private const val LINE_BREAK = "\n"
}
