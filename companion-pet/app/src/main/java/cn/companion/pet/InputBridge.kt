package cn.companion.pet

/** Each keyboard activity owns one input ticket; an old activity cannot submit to a newer question. */
object InputBridge {
    private data class Pending(val id: Long, val hint: String, val submit: (String) -> Unit, val cancel: () -> Unit)
    private var nextId = 0L
    private var pending: Pending? = null

    fun open(hint: String, submit: (String) -> Unit, cancel: () -> Unit): Long {
        val old: Pending?
        val id: Long
        synchronized(this) {
            old = pending
            id = ++nextId
            pending = Pending(id, hint, submit, cancel)
        }
        old?.cancel?.invoke()
        return id
    }
    @Synchronized fun isActive(id: Long): Boolean = pending?.id == id
    @Synchronized fun hint(id: Long): String = pending?.takeIf { it.id == id }?.hint.orEmpty()
    fun submit(id: Long, text: String): Boolean {
        val current = take(id) ?: return false
        current.submit(text)
        return true
    }
    fun cancel(id: Long): Boolean {
        val current = take(id) ?: return false
        current.cancel()
        return true
    }
    @Synchronized private fun take(id: Long): Pending? = pending?.takeIf { it.id == id }?.also { pending = null }
    @Synchronized fun clear() { pending = null }
}
