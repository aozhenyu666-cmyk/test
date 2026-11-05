package com.yishou.app.gate

import com.yishou.app.judge.Verdict

object GateCompletion {
    /**
     * 过了就放行。触发器不再是硬门槛：想存就存，不存也走——
     * 原来要点过了还卡在“先存一句触发器”上，等于一关后面又冒一关。
     * 拉回题只把人带回这件事，不换放行。
     */
    fun ready(verdict: Verdict?, triggerStored: Boolean = false, pullBack: Boolean = false): Boolean =
        !pullBack && verdict?.passed == true && !verdict.unavailable
}
