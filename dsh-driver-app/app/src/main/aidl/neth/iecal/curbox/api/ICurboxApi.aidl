// Curbox 公开的 Binder 接口（API 版本 3）。包名、方法顺序和签名必须和 Curbox 里的一字不差，
// 见 https://github.com/curbox-app/curbox-android/blob/kt-rewrite/CURBOX_API.md 。
package neth.iecal.curbox.api;

interface ICurboxApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
}
