// 体检工具没有需要注入的钩子，工具都在子包里；主入口留空实现以满足 manifest.main。
export function registerToolPkg() {
  return true;
}
