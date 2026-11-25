package io.github.gcbond.openhc.projection

/** 投屏画布只归属一次连接：有线与无线的尝试编号分别计数。 */
internal data class ProjectionOwner(val wireless: Boolean, val attempt: Int)

/**
 * 同一次连接还在投屏时，暂时显示首页/设置也保留画布。只有会话结束、被另一次会话替代或 Activity 销毁才释放。
 * 主线程调用；[mount] / [unmount] 由界面层提供，以便不依赖 Android 检查切页是否引起重挂载。
 */
internal class ProjectionPageSlot<Page : Any>(
    private val mount: (ProjectionOwner) -> Page,
    private val unmount: (Page) -> Unit,
) {
    class Entry<Page>(val owner: ProjectionOwner, val page: Page)

    private var entry: Entry<Page>? = null

    fun reconcile(requested: ProjectionOwner?, isActive: (ProjectionOwner) -> Boolean): Entry<Page>? {
        val visible = requested?.takeIf(isActive)
        val old = entry
        if (old != null && (!isActive(old.owner) || (visible != null && visible != old.owner))) close()
        if (entry == null && visible != null) entry = Entry(visible, mount(visible))
        return entry
    }

    fun close() {
        val old = entry ?: return
        entry = null
        unmount(old.page)
    }
}
