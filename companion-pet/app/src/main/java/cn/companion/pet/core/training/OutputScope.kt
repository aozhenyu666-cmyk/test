package cn.companion.pet.core.training

/** A delayed callback belongs to one visible output, not whichever bubble happens to exist later. */
class OutputLifetime {
    private var generation = 0L
    fun replace(): Long = ++generation
    fun invalidate() { generation++ }
    fun owns(token: Long): Boolean = token == generation
    fun token(): Long = generation
}

object ScopedOutputPolicy {
    fun allows(requiredOwner: String?, owner: String, priority: OutputPriority, arbiter: OutputArbiter): Boolean {
        if (requiredOwner != null && owner != requiredOwner) return false
        return arbiter.heldBy(owner) || arbiter.allows(priority)
    }
}
