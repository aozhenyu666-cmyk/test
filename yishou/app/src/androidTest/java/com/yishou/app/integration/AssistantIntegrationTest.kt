package com.yishou.app.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import com.yishou.app.YishouApp
import com.yishou.app.assistant.ChatHistory
import com.yishou.app.assistant.ChatLine
import com.yishou.app.data.AssistantChat
import com.yishou.app.ui.AssistantViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<YishouApp>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun savedFailureCanBeRetriedAfterOpeningChatAgain() = runBlocking {
        app.database.clearAllTables()
        val id = app.database.everyday().insertChat(AssistantChat(title = "待重试", json = ChatHistory.encode(listOf(
            ChatLine("user", "先检查哪个条件？"), ChatLine("error", "接口暂时不可用"))), updatedAt = 1))
        lateinit var vm: AssistantViewModel
        val owner = ViewModelStore()
        main { vm = AssistantViewModel(app); owner.put("assistant", vm) }
        try {
            repeat(100) { if (vm.id == id) return@repeat; delay(50) }
            assertEquals(id, vm.id)
            assertEquals("先检查哪个条件？", vm.lines.first().text)
            assertEquals("接口暂时不可用", vm.error)
        } finally { main { owner.clear() } }
    }
}
