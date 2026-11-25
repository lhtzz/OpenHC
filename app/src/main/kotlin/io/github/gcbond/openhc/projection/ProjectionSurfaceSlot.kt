package io.github.gcbond.openhc.projection

/**
 * 同一个 Surface 对象也可能跨系统桌面来回复用。退后台先明确交出 null，回来可重新绑定；
 * Android 正常送来的销毁回调也处理，但不会重复解绑。与投屏页和应用首页的切换无关，主线程调用。
 */
internal class ProjectionSurfaceSlot<S : Any>(
    private val usable: (S) -> Boolean,
    private val deliver: (S?) -> Unit,
) {
    private var holder: S? = null
    private var hostStarted = true
    val holderReady: Boolean get() = holder?.let(usable) == true

    var output: S? = null
        private set

    fun setHostStarted(value: Boolean): Boolean {
        if (hostStarted == value) return false
        hostStarted = value
        syncOutput()
        return true
    }

    fun setSurface(value: S?): Boolean {
        val changed = value !== holder
        holder = value
        // surfaceCreated 有些 ROM 还不可用，surfaceChanged 可能用同一引用补上有效状态。
        syncOutput()
        return changed
    }

    private fun syncOutput() {
        val next = if (hostStarted) holder?.takeIf(usable) else null
        if (next === output) return
        output = next
        deliver(next)
    }
}
