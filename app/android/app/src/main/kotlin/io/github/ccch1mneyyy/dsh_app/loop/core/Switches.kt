package io.github.ccch1mneyyy.dsh_app.loop.core

/**
 * Who each switch is for. Three switches the person sees, five jobs:
 *
 * | job                     | runs when                         | owner                          |
 * |-------------------------|-----------------------------------|--------------------------------|
 * | foreground observation  | any feature is on                 | LoopRuntime (package name only)|
 * | app-enter trigger       | 触发测试 is on                     | LoopEngine visit + TriggerRules|
 * | random attempts         | 主动搭话 is on                     | ProactiveScheduler             |
 * | interaction arbitration | any feature is on                 | Coordinator (sole result owner)|
 * | entertainment intercept | 娱乐拦截 is on                     | InterceptGuard (own book)      |
 *
 * Turning one feature off cancels only that feature's events in the coordinator; the other features
 * keep working. Protected-page detection depends on observation only, never on which feature asked.
 *
 * Round 4: the intercept feature has its *own* ledger and its own decision path. It appears here only
 * so that "observe the foreground" has one owner — it never routes through the coordinator, and no
 * counter is shared.
 */
data class Switches(
    val triggerTest: Boolean,
    val proactive: Boolean,
    val intercept: Boolean = false,
    /** 原生专注监督：只要它在跑，就必须读前台（即使三个旧开关都关着）。 */
    val focus: Boolean = false,
) {
    val observeForeground: Boolean get() = triggerTest || proactive || intercept || focus
    val arbitrate: Boolean get() = triggerTest || proactive

    /** Whether events from [source] may be accepted / kept. */
    fun accepts(source: String): Boolean = when (source) {
        SOURCE_APP_ENTER -> triggerTest
        SOURCE_SCHEDULER -> proactive
        else -> arbitrate
    }

    /** Sources whose events must be cancelled now. */
    fun disabledSources(): List<Pair<String, String>> = buildList {
        if (!triggerTest) add(SOURCE_APP_ENTER to "trigger_test_off")
        if (!proactive) add(SOURCE_SCHEDULER to "proactive_off")
    }

    companion object {
        const val SOURCE_APP_ENTER = "A11Y_WINDOW"
        const val SOURCE_SCHEDULER = "SCHEDULER"
    }
}
