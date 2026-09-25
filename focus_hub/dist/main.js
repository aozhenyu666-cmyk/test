"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
const index_ui_js_1 = __importDefault(require("./ui/focus_hub/index.ui.js"));
const FOCUS_HUB_ROUTE = "toolpkg:local.focus_hub:ui:focus_hub";
function registerToolPkg() {
    ToolPkg.registerUiRoute({
        id: "focus_hub",
        route: FOCUS_HUB_ROUTE,
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
        route: FOCUS_HUB_ROUTE,
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
