package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

/**
 * 开 RFCOMM 服务端监听：几种开法轮着试，失败了隔一会儿再试，最多 [ROUNDS] 轮。
 *
 * **为什么要重试**：这台车机的「蓝牙共享」就是它的蓝牙协议栈（`com.android.bluetooth`，平台签名，
 * `sharedUserId=android.uid.bluetooth`，注册 `android.bluetooth.IBluetooth` 的 `AdapterService`）。
 * 反编译它的 `BluetoothOppRfcommListener` / `BluetoothPbapService` 可以看到，AOSP 自己开监听也是
 * **最多试 10 次、每次隔 300ms**——系统自己都预期这个调用会失败一阵子（蓝牙刚被唤醒、SDP 还没就绪）。
 * 我们以前只试一次，30ms 内失败就报错，太快了。
 *
 * **为什么几种开法都要试**：安卓 4.4 的 `BluetoothSocket.bindListen()` 里，四种失败
 * （服务没绑上 / 跨进程失败 / 系统拒绝 / 读通道号失败）共用 `-1`，`BluetoothAdapter` 只把它拼成
 * `IOException("Error: -1")`，光看这句话分不出原因。带服务记录的开法有三种：
 * 1. [BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord]：不加密、不要求认证（AOSP 的 OPP 就用这个）；
 * 2. `listenUsingEncryptedRfcommWithServiceRecord`：只要加密、不要求认证（隐藏接口，反射调，AOSP 的 PBAP 用这个）；
 * 3. `listenUsingRfcommWithServiceRecord`：要求认证 + 加密（手机上可能要按一次配对确认）。
 *
 * 都不成时再试「指定通道号开监听」（`listenUsingInsecureRfcommOn` / `listenUsingRfcommOn`，都是隐藏接口）：
 * 这种开法不登记服务记录，手机找不到我们，**不能真用**；只是为了分辨「RFCOMM 层本身能不能开」和
 * 「只是服务记录登记被拒」。试完立刻关掉。
 *
 * 真的全失败时，用 [diagnose] 给出归因（不在当前用户下 / 蓝牙服务没绑上 / 协议栈拒绝），
 * 而不是笼统地说「车机不让开」。
 */
object RfcommListeners {

    /** 一次尝试的结果。[socket] 不为 null 表示开成了（调用方负责关）。 */
    class Attempt(
        val how: String,
        val socket: BluetoothServerSocket?,
        val error: String?,
        /** 试了几轮才成（失败时是总共试的轮数）。 */
        val rounds: Int = 1,
    )

    /** 带服务记录的开法，手机能按 UUID 找到我们。返回第一个开成的；都失败时 socket 为 null。 */
    fun openWithServiceRecord(
        adapter: BluetoothAdapter,
        serviceName: String,
        uuid: UUID,
        rounds: Int = ROUNDS,
        intervalMillis: Long = INTERVAL_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        cancelled: () -> Boolean = { false },
    ): Attempt = openWithRetry(
        openers = listOf(
            // 不加密、不要求认证：手机不用先配对就能连（AOSP 的 OPP 也用这个）。
            "不加密监听" to {
                @Suppress("DEPRECATION")
                adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
            },
            // 只加密、不要求认证：手机不用先配对就能连，行为和不加密最接近，排在第二。
            "只加密监听" to {
                invokeListenWithRecord(adapter, "listenUsingEncryptedRfcommWithServiceRecord", serviceName, uuid)
            },
            // 要求认证 + 加密：手机上可能会弹配对确认，只在前面都不成时才用。
            "加密+认证监听" to {
                @Suppress("DEPRECATION")
                adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
            },
        ),
        rounds = rounds,
        intervalMillis = intervalMillis,
        sleep = sleep,
        cancelled = cancelled,
    ).also { opened ->
        if (opened.socket != null) {
            Log.i(TAG, "开监听成了：${opened.how}（第 ${opened.rounds} 轮）")
        }
    }

    /**
     * 按 [openers] 的顺序试，每种试一次算一轮；一轮全失败就等 [intervalMillis] 再试下一轮，最多 [rounds] 轮。
     * 某一轮里第一个开成就立刻返回。全失败时把最后一轮的报错拼起来交回去。
     *
     * [sleep] 只为测试能替换掉，正常运行就是 [Thread.sleep]。
     */
    internal fun openWithRetry(
        openers: List<Pair<String, () -> BluetoothServerSocket?>>,
        rounds: Int = ROUNDS,
        intervalMillis: Long = INTERVAL_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        cancelled: () -> Boolean = { false },
    ): Attempt {
        require(rounds >= 1) { "至少试一轮" }
        var last: List<Attempt> = emptyList()
        var done = 0
        for (round in 1..rounds) {
            val attempts = openers.map { (how, body) -> attempt(how, body) }
            done = round
            attempts.firstOrNull { it.socket != null }?.let { opened ->
                return Attempt(opened.how, opened.socket, null, rounds = round)
            }
            last = attempts
            // 最后一轮不用再等。
            if (round < rounds) {
                // 用户中途取消（关监听）就别再耗下去了，最多拖 3 秒。
                if (cancelled()) break
                sleep(intervalMillis)
                if (cancelled()) break
            }
        }
        return Attempt(
            how = last.joinToString("、") { "${it.how} 被拒（${it.error}）" },
            socket = null,
            error = last.firstOrNull()?.error,
            rounds = done,
        )
    }

    /**
     * 开监听全失败时给出归因。三种可能，按证据强弱排：
     *
     * 1. **不在当前用户下**：`AdapterService$AdapterServiceBinder.createSocketChannel` 第一步就是
     *    `Utils.checkCallerAllowManagedProfiles()`，`callingUser != ActivityManager.getCurrentUser()`
     *    时直接 `return null`，binder 返回 null 后 `bindListen()` 抛 `Error: -1`。
     *    多用户车机（主用户 / 访客 / 演示用户）把应用装在副用户下就是这样。
     * 2. **蓝牙服务没绑上**：`bindListen()` 在 `bluetoothProxy == null` 时也返回 -1（日志「bluetooth is off」）。
     * 3. **协议栈拒绝**：前面两条都排除，就是 `createSocketChannelNative` 返回负值（SDP 登记不上、RFCOMM 起不来）。
     *
     * [probe] 是 [probeFixedChannels] 的结论，用来进一步区分第 3 类里「只是服务记录被拒」还是「整个 RFCOMM 不行」。
     * [probeDetail] 是 [probeFixedChannels] 的明细（哪些隐藏接口不存在、哪些调用失败），写进日志用。
     *
     * 措辞要诚实：读不到前台用户号时不写「用户没问题」，只写「读不到」。
     */
    fun diagnose(adapter: BluetoothAdapter, probe: String? = null, probeDetail: String? = null): String {
        val mine = Process.myUid() / PER_USER_RANGE
        val user = foregroundUser()
        // 只有拿到真正的前台用户号才能判断「装错用户」；拿到的是本进程用户就说明判断不了。
        if (user != null && user.exact && user.id != mine) {
            return "本应用装在用户 $mine 下，而车机当前是用户 ${user.id}。" +
                "这台车机的蓝牙只给当前用户的应用开监听通道，请把 OpenHC 装到当前用户（主用户）下再试。"
        }
        if (!serviceBound()) {
            return "系统蓝牙服务没绑上（BluetoothAdapter.mService 为空）。" +
                "请先在车机自带的「蓝牙音乐」里连一次手机把蓝牙唤醒，再回来重试。"
        }
        val userText = when {
            user == null -> "本进程用户 $mine（读不到前台用户号，装错用户这一项排除不了）"
            user.exact -> "本进程用户 $mine，就是车机当前用户"
            else -> "本进程用户 $mine（读不到前台用户号，装错用户这一项排除不了）"
        }
        val tail = if (probe != null) "；$probe" else ""
        val detail = if (!probeDetail.isNullOrBlank()) "（$probeDetail）" else ""
        return "蓝牙服务在、$userText，剩下唯一可能是这台车机的蓝牙协议栈拒绝了监听请求" +
            "（`createSocketChannelNative` 返回负值，安卓 4.4 只报 Error: -1）$tail$detail。" +
            "带服务记录和不带服务记录两种开法都不通，说明整个 RFCOMM 服务端都开不了，不是服务记录（SDP）登记的问题。" +
            "这台车机的无线投屏走不通，请改用数据线连接。"
    }

    /**
     * 不带服务记录的「指定通道号开监听」探针：能开说明 RFCOMM 层本身没问题，问题只在服务记录登记。
     * 试完立刻关掉。返回一句结论（给日志和界面用）。
     *
     * 失败时「反射找不到方法」和「方法找到了但调用失败」是两件完全不同的事：前者是这台 ROM 把隐藏接口裁了，
     * 后者才是 native 层拒绝。只看「不行」两个字会把两种原因混在一起，所以明细放在 [Probe.detail] 里。
     */
    fun probeFixedChannels(adapter: BluetoothAdapter): Probe {
        val insecure = probeChannels(adapter, "listenUsingInsecureRfcommOn")
        val secure = probeChannels(adapter, "listenUsingRfcommOn")
        val detail = "不加密通道开法：${insecure.describe()}；加密通道开法：${secure.describe()}"
        val conclusion = when {
            insecure.channel != null -> "按通道号开监听可以（不加密，通道 ${insecure.channel}），说明只是服务记录登记被拒"
            secure.channel != null -> "按通道号开监听可以（加密，通道 ${secure.channel}），说明只是服务记录登记被拒"
            // 两种方法都找不到：ROM 裁了隐藏接口，和「协议栈拒绝」是两回事。
            insecure.missing && secure.missing -> "这台系统里没有「按通道号开监听」的隐藏接口（ROM 裁掉了），探针用不上"
            else -> "按通道号开监听也不行，这台车机的蓝牙协议栈不支持给第三方应用开 RFCOMM 服务端"
        }
        return Probe(conclusion, detail)
    }

    /** 探针结果：[conclusion] 给用户看，[detail] 写日志（区分方法不存在 / 调用失败）。 */
    class Probe(val conclusion: String, val detail: String)

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
        // 前台用户号尽量读；读到的只是本进程用户时说清楚，别让人以为是前台用户。
        foregroundUser()?.let { parts += if (it.exact) "前台用户 ${it.id}" else "本进程用户 ${it.id}（读不到前台用户号）" }
        parts += "已配对 ${bondedCount(adapter)} 台"
        // 系统蓝牙服务有没有绑上（拿不到就是「蓝牙没开」那一类失败，和「被拒」是两回事）。
        parts += "蓝牙服务${if (serviceBound()) "在" else "没绑上"}"
        return parts.joinToString("，")
    }

    /**
     * 蓝牙能力自检（设置页「蓝牙自检」用）：不连手机，把三种带服务记录的开法各试一遍，
     * 再试「按通道号开监听」探针，最后附上环境。返回一段给用户看的中文说明。
     *
     * 只用来判断这台车机给不给本应用开 RFCOMM 服务端；试出来的监听立刻关掉。
     * 这里每种开法只试一次（不等重试），自检要快；真正连接时走 [openWithServiceRecord] 的 10 轮重试。
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
        val attempts = listOf(
            attempt("不加密监听（默认用这个）") {
                @Suppress("DEPRECATION")
                adapter.listenUsingInsecureRfcommWithServiceRecord(TEST_SERVICE_NAME, uuid)
            },
            attempt("只加密监听") {
                invokeListenWithRecord(adapter, "listenUsingEncryptedRfcommWithServiceRecord", TEST_SERVICE_NAME, uuid)
            },
            attempt("加密+认证监听") {
                @Suppress("DEPRECATION")
                adapter.listenUsingRfcommWithServiceRecord(TEST_SERVICE_NAME, uuid)
            },
        )
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
        val probe = probeFixedChannels(adapter)
        sb.append("补充检查：").append(probe.conclusion).append(LINE_BREAK)
        sb.append("明细：").append(probe.detail).append(LINE_BREAK)
        sb.append(LINE_BREAK)
        sb.append("结论：").append(diagnose(adapter, probe.conclusion, probe.detail))
        return sb.toString()
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 试一种开法。**任何**异常都要接住：这里是后台监听线程，抛出去就是进程崩溃（闪退）。
     *
     * 特别注意 [java.lang.reflect.InvocationTargetException]——`Method.invoke` 抛的是受检的 `Exception`，
     * 不是 `RuntimeException`，漏掉它的话会一路穿过 [openWithRetry] 和监听线程把应用带崩。
     * 反射调用被系统拒绝时，真正的原因在它的 cause 里，要拆出来记日志。
     */
    private inline fun attempt(how: String, body: () -> BluetoothServerSocket?): Attempt = try {
        val socket = body()
        Attempt(how, socket, if (socket == null) "这台系统没有这种开法" else null)
    } catch (e: IOException) {
        Attempt(how, null, describe(e))
    } catch (e: SecurityException) {
        Attempt(how, null, "没有蓝牙权限（${describe(e)}）")
    } catch (e: InvocationTargetException) {
        // 反射调用里抛的异常（系统拒绝建通道时就是这个）。
        val cause = e.cause ?: e
        val permission = cause is SecurityException
        Attempt(how, null, (if (permission) "没有蓝牙权限（" else "") + describe(cause) + (if (permission) "）" else ""))
    } catch (e: Exception) {
        Attempt(how, null, describe(e))
    } catch (e: LinkageError) {
        Attempt(how, null, "系统里没有这个接口（${describe(e)}）")
    }

    /** 按通道号开监听的结果：开成了记 [channel]；[missing] 表示系统里没有这个方法；否则 [error] 是最后一次报错。 */
    internal class ChannelProbe(val channel: Int?, val missing: Boolean, val error: String?) {
        fun describe(): String = when {
            channel != null -> "通道 $channel 可以"
            missing -> "系统里没有这个方法"
            else -> "全部通道都不行（$error）"
        }
    }

    /**
     * 依次试 1～[MAX_CHANNEL] 号通道，开成第一个就立刻关掉并返回通道号。
     *
     * 先看方法在不在：不在就标 [ChannelProbe.missing]，别把「ROM 裁了接口」误报成「协议栈拒绝」。
     */
    @SuppressLint("MissingPermission")
    private fun probeChannels(adapter: BluetoothAdapter, method: String): ChannelProbe {
        if (this.method(method, Int::class.javaPrimitiveType ?: return ChannelProbe(null, true, null)) == null) {
            return ChannelProbe(null, missing = true, error = null)
        }
        var lastError: String? = null
        for (channel in 1..MAX_CHANNEL) {
            val socket = try {
                invokeListenOnChannel(adapter, method, channel)
            } catch (e: SecurityException) {
                // 没权限：后面每个通道都一样，直接收工。
                return ChannelProbe(null, missing = false, error = "没有蓝牙权限（${describe(e)}）")
            } catch (e: InvocationTargetException) {
                // 反射包了一层，真实原因在 cause 里。
                lastError = describe(e.cause ?: e)
                continue
            } catch (e: Exception) {
                lastError = describe(e)
                continue
            } catch (e: LinkageError) {
                lastError = describe(e)
                continue
            } ?: continue
            try {
                socket.close()
            } catch (e: IOException) {
                // 关不掉也没关系，接着报结论。
            }
            return ChannelProbe(channel, missing = false, error = null)
        }
        return ChannelProbe(null, missing = false, error = lastError ?: "每个通道都返回空")
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
    } catch (e: LinkageError) {
        false
    }

    /**
     * 前台用户号。三条路依次试：
     *
     * 1. `ActivityManager.getCurrentUser()`（隐藏静态方法，AOSP 一直有，但有的 ROM 会裁掉）——这才是真正的「当前前台用户」；
     * 2. `UserHandle.myUserId()`（公开接口，安卓 4.1 起就有）——严格说是「本进程用户」；
     * 3. 从本进程 uid 反推——同上。
     *
     * [UserInfo.exact] 表示读的是第 1 条（真的前台用户），否则只是本进程用户。
     * 全部读不到才返回 null；区分开来是因为诊断里不能把「本进程用户」说成「当前前台用户」。
     */
    private class UserInfo(val id: Int, val exact: Boolean)

    private fun foregroundUser(): UserInfo? {
        // 1. ActivityManager.getCurrentUser()：AOSP 的正解，只有它能说明「当前前台用户」。
        try {
            val activityManager = Class.forName("android.app.ActivityManager")
            val getCurrentUser = activityManager.getMethod("getCurrentUser")
            (getCurrentUser.invoke(null) as? Int)?.let { return UserInfo(it, exact = true) }
        } catch (e: Exception) {
            // 换下一条。
        } catch (e: LinkageError) {
            // 换下一条。
        }
        // 2. UserHandle.myUserId()：公开接口，安卓 4.1 起就有。
        try {
            val userHandle = Class.forName("android.os.UserHandle")
            val myUserId = userHandle.getMethod("myUserId")
            (myUserId.invoke(null) as? Int)?.let { return UserInfo(it, exact = false) }
        } catch (e: Exception) {
            // 换下一条。
        } catch (e: LinkageError) {
            // 换下一条。
        }
        // 3. 从本进程 uid 反推（一定有值，但只能代表本进程）。
        return UserInfo(Process.myUid() / PER_USER_RANGE, exact = false)
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

    /**
     * 开监听最多试几轮。和这台车机蓝牙协议栈里 `BluetoothOppRfcommListener` / `BluetoothPbapService`
     * 的做法一致（反编译 `com.android.bluetooth` 4.4.2 得到：10 次）。
     */
    internal const val ROUNDS = 10

    /** 每轮之间等多久（同上，AOSP 是 300ms）。 */
    internal const val INTERVAL_MS = 300L

    private const val TAG = "OpenHC-Rfcomm"

    /** 自检时用的服务名（手机不看服务名）。 */
    private const val TEST_SERVICE_NAME = "OpenHC-SelfTest"

    /** 自检结果里的换行。 */
    private const val LINE_BREAK = "\n"
}
