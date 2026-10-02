package com.behaviordept.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.behaviordept.app.AppContainer
import com.behaviordept.app.container

/** 用 AppContainer 构造 ViewModel；每个导航目的地有自己的 ViewModelStore。 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContext.current.container
    return viewModel(factory = viewModelFactory { initializer { create(c) } })
}
