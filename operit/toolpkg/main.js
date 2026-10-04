'use strict';
const {Screen} = require('./ui/console.ui.js');

exports.registerToolPkg = function () {
  const route = 'toolpkg:com.community.zhukong:ui:console';
  ToolPkg.registerUiRoute({id: 'console', route, runtime: 'compose_dsl', screen: Screen, params: {}, keepAlive: false,
    title: {zh: '主控台', en: 'Control Hub'}});
  ToolPkg.registerNavigationEntry({id: 'console_sidebar', route, surface: 'main_sidebar_plugins',
    title: {zh: '主控台', en: 'Control Hub'}, icon: 'SelfImprovement', order: 110});
  ToolPkg.registerNavigationEntry({id: 'console_toolbox', route, surface: 'toolbox',
    title: {zh: '主控台', en: 'Control Hub'}, icon: 'SelfImprovement', order: 110});
  return true;
};
