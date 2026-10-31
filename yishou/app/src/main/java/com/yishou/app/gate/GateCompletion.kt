package com.yishou.app.gate

import com.yishou.app.judge.Verdict

object GateCompletion {
    /** 拉回只是把人带回这件事，不换放行；要点过了、存好触发器才放行 */
    fun ready(verdict: Verdict?, triggerStored: Boolean, pullBack: Boolean = false): Boolean =
        !pullBack && verdict?.passed == true && !verdict.unavailable && triggerStored
}
