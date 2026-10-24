package cn.companion.pet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.companion.pet.core.action.*
import cn.companion.pet.ui.CapabilityPanel
import cn.companion.pet.ui.theme.CompanionTheme
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Recreates an Android host with a fake adapter; does not touch the user's memory or real TTS. */
class CapabilityRotationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    class Owner(val session: ActionSession) : ViewModel() {
        override fun onCleared() { session.close() }
    }

    @Test fun retainedOwnerDoesNotResubmitQueuedWorkAfterRecreation() {
        val queue = mutableListOf<Runnable>()
        var calls = 0
        val runner = ActionRunner(mapOf(ActionKind.READ_MEMORY to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                calls++
                return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "合成读取完成")
            }
        }))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Owner(ActionSession(runner, Executor { queue += it })) as T
        }
        lateinit var first: Owner
        fun attach(activity: ComponentActivity): Owner {
            val owner = ViewModelProvider(activity, factory)[Owner::class.java]
            activity.setContent {
                val state by owner.session.state.collectAsState()
                CompanionTheme { CapabilityPanel(state, owner.session::submit, owner.session::cancel, {}, {}) }
            }
            return owner
        }
        compose.activityRule.scenario.onActivity { first = attach(it) }
        compose.onNodeWithText("执行").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { assertSame(first, attach(it)) }
        compose.onNodeWithText("等待执行").performScrollTo().assertIsDisplayed()
        assertEquals(1, queue.size)
        queue.single().run()
        compose.onNodeWithText("合成读取完成").performScrollTo().assertIsDisplayed()
        assertEquals(1, calls)
    }
}
