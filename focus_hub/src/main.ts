import focusHubScreen from "./ui/focus_hub/index.ui.js";

const FOCUS_HUB_ROUTE = "toolpkg:local.focus_hub:ui:focus_hub";

export function registerToolPkg(): boolean {
  ToolPkg.registerUiRoute({
    id: "focus_hub",
    route: FOCUS_HUB_ROUTE,
    runtime: "compose_dsl",
    screen: focusHubScreen,
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
