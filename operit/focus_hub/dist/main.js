"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
const index_ui_js_1 = __importDefault(require("./ui/focus_hub/index.ui.js"));
const nav_js_1 = require("./shared/nav.js");
function registerToolPkg() {
    ToolPkg.registerUiRoute({
        id: "focus_hub",
        route: nav_js_1.FOCUS_HUB_ROUTE,
        runtime: "compose_dsl",
        screen: index_ui_js_1.default,
        params: {},
        keepAlive: true,
        title: {
            zh: "主控台",
            en: "Focus Hub",
        },
    });
    ToolPkg.registerNavigationEntry({
        id: "focus_hub_sidebar",
        route: nav_js_1.FOCUS_HUB_ROUTE,
        surface: "main_sidebar_plugins",
        title: {
            zh: "主控台",
            en: "Focus Hub",
        },
        icon: Icons.Dashboard,
        order: 5,
    });
    return true;
}
