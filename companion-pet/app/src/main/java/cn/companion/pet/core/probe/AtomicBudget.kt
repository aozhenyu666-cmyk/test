package cn.companion.pet.core.probe

/**
 * 预算的持久化端口。生产环境用 SharedPreferences.commit()，测试用内存实现。
 * 只要 [load] 与 [save] 是同步的，[AtomicBudget] 就能在当前进程内保证不超卖。
 */
interface BudgetPersistence {
    fun load(day: String): Int
    fun save(day: String, used: Int)
}

/**
 * 所有模型调用入口共用的一本预算账：
 * - 先预留再发请求；预留成功才真的调用；
 * - 一次真正的 HTTP 尝试占一格；客户端本身不重试，WorkManager 重试算新的一次；
 * - 按天键计数，跨日归零，进程重启从持久化恢复；
 * - 同一进程内用一把锁把“读-判断-写”合成一个原子操作。
 *
 * 不承诺多进程同时写同一个 SharedPreferences 的强一致；本应用所有调用入口都在同一进程。
 */
class AtomicBudget(private val persistence: BudgetPersistence) {
    private val lock = Any()

    fun reserve(limit: Int, day: String): Boolean = synchronized(lock) {
        if (limit <= 0) return false
        val used = persistence.load(day)
        if (used >= limit) return false
        persistence.save(day, used + 1)
        true
    }

    fun used(day: String): Int = synchronized(lock) { persistence.load(day) }

    fun remaining(limit: Int, day: String): Int = synchronized(lock) {
        (limit - persistence.load(day)).coerceAtLeast(0)
    }
}
