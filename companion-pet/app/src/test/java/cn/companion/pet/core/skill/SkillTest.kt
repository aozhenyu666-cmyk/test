package cn.companion.pet.core.skill

import cn.companion.pet.core.device.DeviceCommand
import cn.companion.pet.core.device.DeviceController
import cn.companion.pet.core.device.DeviceObservation
import cn.companion.pet.core.device.DeviceResult
import cn.companion.pet.core.device.DeviceSnapshot
import cn.companion.pet.core.device.DeviceStatus
import cn.companion.pet.core.device.NodeRef
import cn.companion.pet.core.device.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 技能：按元素回放、等元素出现、应用守卫、失败即停、日志如实、存储原子。 */
class SkillTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(id: String, text: String, rid: String = "", pkg: String = "com.app", clickable: Boolean = true,
                     editable: Boolean = false, top: Int = 0) =
        NodeRef(id, "o", pkg, text, rid, "android.widget.Button", clickable, editable, false, 0, top, 100, top + 50)

    /** 一台假手机：前台应用 + 每个应用一屏节点；点某些节点会换屏。 */
    private class FakePhone(var active: String = "cn.companion.pet") : DeviceController {
        val screens = mutableMapOf<String, List<NodeRef>>()
        val onTap = mutableMapOf<String, () -> Unit>()
        val executed = mutableListOf<DeviceCommand>()
        var unlocked = true
        /** 前几次观察里元素还没加载出来。 */
        var loadingObservations = 0
        private var latest: DeviceObservation? = null
        var typed: String? = null

        override fun snapshot(now: Long) = DeviceSnapshot(active, unlocked, true, ShizukuStatus.NOT_INSTALLED,
            activeWindowPackage = active, ownPackage = "cn.companion.pet")

        override fun observe(now: Long): DeviceResult {
            if (active == "cn.companion.pet") return DeviceResult(DeviceStatus.UNAVAILABLE, "不能操作桌宠自己的窗口")
            val nodes = if (loadingObservations > 0) { loadingObservations--; emptyList() } else screens[active].orEmpty()
            latest = DeviceObservation("obs-$now", active, now, now + 45_000, nodes)
            return DeviceResult(DeviceStatus.SUCCESS, "ok", latest!!.id)
        }

        override fun lastObservation() = latest

        override fun execute(command: DeviceCommand, now: Long): DeviceResult {
            executed += command
            return when (command) {
                is DeviceCommand.OpenApp -> { active = command.packageName; DeviceResult(DeviceStatus.SUCCESS, "已打开") }
                is DeviceCommand.TapNode -> { onTap[command.nodeId]?.invoke(); DeviceResult(DeviceStatus.UNVERIFIED, "已点击", evidence = mapOf("pageChanged" to "true")) }
                is DeviceCommand.InputText -> { typed = command.text; DeviceResult(DeviceStatus.SUCCESS, "已写入") }
                is DeviceCommand.Back, is DeviceCommand.Scroll -> DeviceResult(DeviceStatus.UNVERIFIED, "ok", evidence = mapOf("pageChanged" to "false"))
                else -> DeviceResult(DeviceStatus.UNAVAILABLE, "不支持")
            }
        }
    }

    private var now = 0L
    private fun runner(phone: DeviceController) = SkillRunner(phone, clock = { now }, sleeper = { now += it },
        findTimeoutMs = 2_000, pollMs = 500, openTimeoutMs = 1_000)

    private fun step(type: SkillStepType, target: ElementTarget? = null, pkg: String = "", text: String = "", wait: Long = 0) =
        SkillStep(type.name + (target?.text ?: pkg), type, appPackage = pkg, target = target, text = text, waitMs = wait)

    private fun skill(vararg steps: SkillStep) = Skill("s", "测试", steps.toList(), 0, 0)

    @Test
    fun replaysOpenTapInputAndLogsEachStep() {
        val phone = FakePhone()
        phone.screens["com.app"] = listOf(node("n-search", "搜索", rid = "com.app:id/search"))
        phone.onTap["n-search"] = { phone.screens["com.app"] = listOf(node("n-box", "", rid = "com.app:id/query", editable = true)) }
        val result = runner(phone).run(skill(
            step(SkillStepType.OPEN_APP, pkg = "com.app"),
            step(SkillStepType.TAP, ElementTarget("搜索", "com.app:id/search", packageName = "com.app")),
            step(SkillStepType.INPUT, ElementTarget("", "com.app:id/query", packageName = "com.app"), text = "番茄钟"),
        ))
        assertTrue(result.ok)
        assertEquals("番茄钟", phone.typed)
        assertEquals(listOf("✓", "?", "✓"), result.log.map { it.take(1) })
        assertTrue(result.log[1].contains("页面有变化"))
    }

    @Test
    fun waitsForSlowElementInsteadOfFailingImmediately() {
        val phone = FakePhone("com.app")
        phone.screens["com.app"] = listOf(node("n1", "开始"))
        phone.loadingObservations = 2
        val result = runner(phone).run(skill(step(SkillStepType.TAP, ElementTarget("开始"))))
        assertTrue(result.ok)
        assertTrue(phone.executed.any { it is DeviceCommand.TapNode })
    }

    @Test
    fun missingElementStopsTheRunHonestly() {
        val phone = FakePhone("com.app")
        phone.screens["com.app"] = listOf(node("n1", "开始"))
        val result = runner(phone).run(skill(
            step(SkillStepType.TAP, ElementTarget("发送")),
            step(SkillStepType.BACK),
        ))
        assertFalse(result.ok)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.log.single().startsWith("✗ 1"))
        assertTrue(result.log.single().contains("没找到"))
        assertTrue("never tapped anything", phone.executed.none { it is DeviceCommand.TapNode || it is DeviceCommand.Back })
    }

    @Test
    fun appGuardRefusesTargetInAnotherApp() {
        val phone = FakePhone("com.other")
        phone.screens["com.other"] = listOf(node("x", "发送", pkg = "com.other"))
        val result = runner(phone).run(skill(step(SkillStepType.TAP, ElementTarget("发送", packageName = "com.app"))))
        assertFalse(result.ok)
        assertTrue(result.log.single().contains("先加一步“打开应用”"))
        assertTrue(phone.executed.isEmpty())
    }

    @Test
    fun lockScreenAndCancelStop() {
        val phone = FakePhone("com.app")
        phone.unlocked = false
        val locked = runner(phone).run(skill(step(SkillStepType.BACK)))
        assertFalse(locked.ok)
        assertTrue(locked.log.last().contains("锁屏"))
        phone.unlocked = true
        var stop = false
        val cancelled = runner(phone).run(skill(step(SkillStepType.WAIT, wait = 5_000), step(SkillStepType.BACK)), cancelled = { stop }) { i, _, _ ->
            if (i == 0) stop = true
        }
        assertFalse(cancelled.ok)
        assertTrue(phone.executed.none { it is DeviceCommand.Back })
    }

    @Test
    fun resolverPrefersIdThenTextAndRefusesAmbiguousIds() {
        val obs = DeviceObservation("o", "com.app", 0, 1, listOf(
            node("a", "张三", rid = "com.app:id/item", top = 0),
            node("b", "李四", rid = "com.app:id/item", top = 60),
            node("c", "设置", rid = "com.app:id/settings", top = 120),
        ))
        assertEquals("c", ElementResolver.resolve(ElementTarget("", "com.app:id/settings"), obs)!!.id)
        assertNull("list item id alone is ambiguous", ElementResolver.resolve(ElementTarget("", "com.app:id/item"), obs))
        assertEquals("b", ElementResolver.resolve(ElementTarget("李四", "com.app:id/item"), obs)!!.id)
        assertEquals("a", ElementResolver.resolve(ElementTarget("张三"), obs)!!.id)
        assertNull(ElementResolver.resolve(ElementTarget("张三", packageName = "com.other"), obs))
        assertEquals(listOf("a", "b", "c"), ElementResolver.pickable(obs).map { it.id })
    }

    @Test
    fun storeRoundTripReorderAndRunLog() {
        val store = SkillStore(tmp.root)
        val s = store.create("打开番茄钟", 1)!!
        store.addStep(s.id, step(SkillStepType.OPEN_APP, pkg = "com.app"), 2)
        store.addStep(s.id, step(SkillStepType.BACK), 3)
        val stepIds = store.get(s.id)!!.steps.map { it.id }
        assertTrue(store.moveStep(s.id, stepIds[1], -1, 4))
        assertEquals(SkillStepType.BACK, SkillStore(tmp.root).get(s.id)!!.steps.first().type)
        store.recordRun(s.id, SkillRunner.RunResult(false, emptyList(), "你停止了"), 5)
        val reloaded = SkillStore(tmp.root).get(s.id)!!
        assertEquals(false, reloaded.lastRunOk)
        assertEquals(listOf("■ 你停止了"), reloaded.lastRunLog)
        assertTrue(store.delete(s.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun corruptSkillFileIsBackedUp() {
        val dir = java.io.File(tmp.root, "skills").apply { mkdirs() }
        java.io.File(dir, "skills.json").writeText("[{broken")
        val store = SkillStore(tmp.root)
        assertNotNull(store.create("新的", 1))
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("skills.corrupt-") }!!.size)
    }
}
