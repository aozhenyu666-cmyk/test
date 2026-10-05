'use strict';
// 注册阶段，宿主对 *.ui.js 的 require 返回一个带模块路径标记的占位函数（不是模块导出），
// 必须把它原样传给 screen；在其他环境里（如电脑测试）则取模块的 default 导出。
const screenOf = m => (typeof m === 'function' ? m : m && (m.default || m.Screen));
const core = screenOf(require('./ui/core.ui.js'));
const panel = screenOf(require('./ui/console.ui.js'));

exports.registerToolPkg = function () {
  // 核心对话台：对话为主，侧边面板。路由 ID 保持不变，后台的主动触达会打开这里。
  const coreRoute = 'toolpkg:com.community.zhukong:ui:console';
  ToolPkg.registerUiRoute({id: 'console', route: coreRoute, runtime: 'compose_dsl', screen: core, params: {}, keepAlive: false,
    title: {zh: '核心对话台', en: 'Core Console'}});
  ToolPkg.registerNavigationEntry({id: 'console_sidebar', route: coreRoute, surface: 'main_sidebar_plugins',
    title: {zh: '核心对话台', en: 'Core Console'}, icon: 'SelfImprovement', order: 100});

  // 备用：不嵌对话的面板版。宿主不支持嵌入对话时用它。
  const panelRoute = 'toolpkg:com.community.zhukong:ui:panel';
  ToolPkg.registerUiRoute({id: 'panel', route: panelRoute, runtime: 'compose_dsl', screen: panel, params: {}, keepAlive: false,
    title: {zh: '主控台（面板版）', en: 'Control Panel'}});
  ToolPkg.registerNavigationEntry({id: 'panel_toolbox', route: panelRoute, surface: 'toolbox',
    title: {zh: '主控台（面板版）', en: 'Control Panel'}, icon: 'SelfImprovement', order: 110});
  return true;
};
