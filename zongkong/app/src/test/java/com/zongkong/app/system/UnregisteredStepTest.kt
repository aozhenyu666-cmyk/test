package com.zongkong.app.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UNREGISTERED 的处理分派（真机事故的回归测试）。
 *
 * ## 事故现场
 *
 * iQOO 12 上装了 0.16.0-health-v2：哨兵的通道静默之后，总控的 `registered` 变成 false，
 * 健康内核判 `UNREGISTERED`，而 `SelfHeal.check` 对这一个分支的处理是**直接 return**。
 * 结果：诊断里零条 `rebind_sent`，每 10 分钟只发一条「健康异常」通知，
 * 从 15:25 一直卡到人工重新打开 App——实测 6 小时。
 *
 * 结论：`UNREGISTERED` 不能是「什么都不做」。这个文件把那条判定钉住，
 * 免得以后有人顺手又把它改回 `return`。
 */
class UnregisteredStepTest {

    @Test
    fun `服务实例不在且能写设置时应当重绑`() {
        // 真机上最常见的那一种：系统把服务崩过之后不再 bind，实例根本连不上。
        // 这种重绑是有效的（现场手工验证过：拿掉再放回，6 秒内 GuardService 重新 bind）。
        assertEquals(
            SelfHeal.UnregStep.REBIND,
            SelfHeal.unregisteredStep(instanceAlive = false, canWrite = true),
        )
    }

    @Test
    fun `服务实例不在但没有写设置权限时只能提醒`() {
        // 没有 WRITE_SECURE_SETTINGS 就连重绑都发不出去，只能叫人手动去关开一次。
        assertEquals(
            SelfHeal.UnregStep.WARN,
            SelfHeal.unregisteredStep(instanceAlive = false, canWrite = false),
        )
    }

    @Test
    fun `服务实例还在时不要去动系统设置`() {
        // 实例在、只是没向哨兵登记：那是协议层没就绪，重绑系统设置解决不了问题，
        // 反而会把一个正在正常工作的无障碍服务踢掉。
        assertEquals(
            SelfHeal.UnregStep.WAIT,
            SelfHeal.unregisteredStep(instanceAlive = true, canWrite = true),
        )
        assertEquals(
            SelfHeal.UnregStep.WAIT,
            SelfHeal.unregisteredStep(instanceAlive = true, canWrite = false),
        )
    }

    @Test
    fun `任何组合都不会什么都不做`() {
        // 这条是这次事故的核心教训：四种输入里没有一种是「既不算重绑也不算提醒」。
        val combos = listOf(true to true, true to false, false to true, false to false)
        combos.forEach { (alive, write) ->
            val step = SelfHeal.unregisteredStep(alive, write)
            assertEquals(
                "instanceAlive=$alive canWrite=$write 不应该落到空动作",
                true,
                step == SelfHeal.UnregStep.REBIND || step == SelfHeal.UnregStep.WAIT || step == SelfHeal.UnregStep.WARN,
            )
        }
    }

    // ---------- 「设置里没有我」是系统摘的，还是用户关的 ----------

    @Test
    fun `运行过且总开关还开着算系统摘的`() {
        // 真机上的典型：服务崩掉后系统把它从列表里删了，但无障碍总开关仍是 1。
        // 这种必须修，不然就是「只能手点」。
        assertEquals(true, SelfHeal.systemRemoved(everRan = true, masterOn = true))
    }

    @Test
    fun `总开关被关掉算用户自己关的`() {
        // 用户去设置里关，通常会把总开关一起关掉。这种不能替他打开。
        assertEquals(false, SelfHeal.systemRemoved(everRan = true, masterOn = false))
    }

    @Test
    fun `从没运行过就不算被摘掉`() {
        // 刚装、还没授权的时候「列表里没有我」是正常的，不该去写系统设置。
        assertEquals(false, SelfHeal.systemRemoved(everRan = false, masterOn = true))
        assertEquals(false, SelfHeal.systemRemoved(everRan = false, masterOn = false))
    }
}
