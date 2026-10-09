package com.yishou.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.Task
import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()

    val llm: StateFlow<LlmConfig> = yishou.settings.llm

    val tasks: StateFlow<List<Task>> = dao.observeAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 保存接口设置。地址格式不对时返回错误文字，不保存。 */
    fun saveLlm(config: LlmConfig): String? {
        if (config.baseUrl.isNotBlank() && ChatClient.chatCompletionsUrl(config.baseUrl) == null) {
            return "接口地址要以 https:// 或 http:// 开头"
        }
        yishou.settings.saveLlm(config)
        return null
    }

    fun switchTo(taskId: Long) {
        viewModelScope.launch { dao.switchCurrentTask(taskId) }
    }
}
