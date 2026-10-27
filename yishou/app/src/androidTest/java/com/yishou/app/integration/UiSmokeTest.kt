package com.yishou.app.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.reset
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking { reset() }
    private fun see(text: String) { compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun homeActuallyNavigatesToAssistantAndShowsAReply() {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        val activity = RuntimeSupport.activity()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            see("AI 助手 · 直接聊")
            compose.onNodeWithText("AI 助手 · 直接聊").performScrollTo().performClick()
            see("新对话")
            compose.onNode(hasSetTextAction() and hasText("想问什么，或者说一句")).performTextInput("帮我列出下一步。")
            server.enqueue(RuntimeSupport.streamed("下一步先确认一个可核对的事实。"))
            compose.onNodeWithText("发送", substring = false).performClick()
            see("下一步先确认一个可核对的事实。")
            RuntimeEvidence.capture("assistant-reply")
        } finally { activity.close(); server.shutdown() }
    }
    @Test fun customCategoryCanBeCreatedImportedSearchedAndMovedInTheRealScreen() {
        val activity = RuntimeSupport.activity("library")
        try {
            see("新标签页名称")
            compose.onNode(hasSetTextAction() and hasText("新标签页名称")).performTextInput("英语")
            compose.onNodeWithText("新建", substring = false).performClick()
            see("英语 0")
            compose.onNodeWithText("英语 0").performClick()
            RuntimeSupport.device.pressBack() // 收起新分类名称输入的键盘
            compose.onNodeWithText("粘贴导入").performScrollTo().performClick()
            see("题包、汤面汤底、文章都能放")
            compose.onNode(hasSetTextAction() and hasText("题包、汤面汤底、文章都能放")).performScrollTo().performTextInput("英语阅读练习：先确认这段材料的主张，再找支持它的具体证据，别只复述结论。")
            compose.onNodeWithText("收进当前分类").performScrollTo().performClick()
            see("英语 1")
            compose.onNode(hasSetTextAction() and hasText("搜题目或正文")).performScrollTo().performTextInput("具体证据")
            see("找到 1 条")
            RuntimeEvidence.capture("library-custom-category")
            compose.onNodeWithText("展开内容 / 移动").performScrollTo().performClick()
            compose.onNodeWithText("移动到分类").performScrollTo().performClick()
            compose.onNodeWithText("其他", substring = false).performClick()
            see("英语 0")
        } finally { activity.close() }
    }
}
