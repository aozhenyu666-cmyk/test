#include "flutter_window.h"

#include <flutter/standard_method_codec.h>

#include <algorithm>
#include <cstdint>
#include <cwchar>
#include <optional>
#include <string>
#include <vector>

#include "flutter/generated_plugin_registrant.h"
#include "resource.h"

namespace {

constexpr UINT kTrayCallbackMessage = WM_APP + 101;
constexpr UINT kShowMainFromSecondInstance = WM_APP + 102;
constexpr UINT_PTR kCmdTrayShow = 41001;
constexpr UINT_PTR kCmdTrayCapsule = 41002;
constexpr UINT_PTR kCmdTrayExit = 41003;
constexpr UINT_PTR kCmdTrayStatus = 41004;

constexpr wchar_t kAutoStartRegSubkey[] =
    L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr wchar_t kAutoStartRegValue[] = L"DshGoFishPc";
constexpr wchar_t kWatchdogMutexName[] = L"Local\\DshGoFishPc_Watchdog_v2";
constexpr wchar_t kWatchdogStopEventName[] = L"Local\\DshGoFishPc_WatchdogStop_v2";

std::string WideToUtf8(const std::wstring& wide) {
  if (wide.empty()) return {};
  int needed = ::WideCharToMultiByte(CP_UTF8, 0, wide.c_str(),
                                     static_cast<int>(wide.size()), nullptr, 0,
                                     nullptr, nullptr);
  if (needed <= 0) return {};
  std::string out(static_cast<size_t>(needed), '\0');
  ::WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), static_cast<int>(wide.size()),
                        out.data(), needed, nullptr, nullptr);
  return out;
}

std::wstring Utf8ToWide(const std::string& utf8) {
  if (utf8.empty()) return {};
  int needed = ::MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(),
                                     static_cast<int>(utf8.size()), nullptr, 0);
  if (needed <= 0) return {};
  std::wstring out(static_cast<size_t>(needed), L'\0');
  ::MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()),
                        out.data(), needed);
  return out;
}

std::string AsciiLower(std::string s) {
  for (char& ch : s) {
    if (ch >= 'A' && ch <= 'Z') {
      ch = static_cast<char>(ch - 'A' + 'a');
    }
  }
  return s;
}

std::string QueryProcessBasename(DWORD pid) {
  if (pid == 0) return {};
  HANDLE proc = ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
  if (!proc) return {};
  wchar_t buf[MAX_PATH * 2];
  DWORD size = static_cast<DWORD>(std::size(buf));
  std::string result;
  if (::QueryFullProcessImageNameW(proc, 0, buf, &size)) {
    std::wstring full(buf, size);
    size_t slash = full.find_last_of(L"\\/");
    std::wstring base =
        (slash == std::wstring::npos) ? full : full.substr(slash + 1);
    size_t dot = base.find_last_of(L'.');
    if (dot != std::wstring::npos) {
      base = base.substr(0, dot);
    }
    result = WideToUtf8(base);
  }
  ::CloseHandle(proc);
  return result;
}

std::wstring QuerySelfExecutablePath() {
  wchar_t buf[MAX_PATH * 2];
  DWORD len = ::GetModuleFileNameW(nullptr, buf, static_cast<DWORD>(std::size(buf)));
  if (len == 0 || len >= std::size(buf)) {
    return {};
  }
  return std::wstring(buf, len);
}

bool IsProtectedProcessBasename(const std::string& lower_base) {
  static constexpr const char* kProtected[] = {
      "explorer",
      "dwm",
      "csrss",
      "wininit",
      "winlogon",
      "lsass",
      "services",
      "smss",
      "svchost",
      "sihost",
      "taskmgr",
      "taskhostw",
      "runtimebroker",
      "ctfmon",
      "textinputhost",
      "searchhost",
      "searchapp",
      "startmenuexperiencehost",
      "shellexperiencehost",
      "lockapp",
      "applicationframehost",
      "systemsettings",
      "conhost",
      "cmd",
      "powershell",
      "pwsh",
      "windowsterminal",
      "wt",
      "code",
      "cursor",
      "windsurf",
      "antigravity",
      "obsidian",
      "anki",
      "zotero",
      "cajviewer",
      "foxitpdfreader",
      "wpspdf",
      "feishu",
      "lark",
      "dingtalk",
      "node",
      "python",
      "pythonw",
      "tailscale",
      "tailscaled",
      "clash",
      "clash-verge",
      "mihomo",
      "nekobox",
      "nekoray",
      "v2rayn",
      "chrome",
      "msedge",
      "edge",
      "firefox",
      "brave",
      "vivaldi",
      "opera",
      "arc",
      "360chrome",
      "qqbrowser",
      "chromium",
      "sogouexplorer",
      "centbrowser",
      "dsh_go_fish_pc",
      "dsh_fish_watchdog",
  };
  for (const char* item : kProtected) {
    if (lower_base == item) {
      return true;
    }
  }
  return false;
}

int QueryIdleSeconds() {
  LASTINPUTINFO lii{};
  lii.cbSize = sizeof(LASTINPUTINFO);
  if (!::GetLastInputInfo(&lii)) return 0;
  // Both GetTickCount() and LASTINPUTINFO.dwTime use 32-bit uptime ticks, so
  // unsigned subtraction handles the 49.7-day wraparound accurately.
  DWORD now_ms = ::GetTickCount();
  DWORD diff_ms = now_ms - lii.dwTime;
  return static_cast<int>(diff_ms / 1000UL);
}

bool QueryScreenLocked(HWND fg, const std::string& app_lower) {
  if (fg == nullptr || app_lower == "lockapp" || app_lower == "logonui") {
    return true;
  }
  HDESK desk = ::OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
  if (desk == nullptr) {
    return true;
  }
  ::CloseDesktop(desk);
  return false;
}

void SendMediaPlayPause() {
  INPUT inputs[2]{};
  inputs[0].type = INPUT_KEYBOARD;
  inputs[0].ki.wVk = VK_MEDIA_PLAY_PAUSE;
  inputs[1].type = INPUT_KEYBOARD;
  inputs[1].ki.wVk = VK_MEDIA_PLAY_PAUSE;
  inputs[1].ki.dwFlags = KEYEVENTF_KEYUP;
  ::SendInput(2, inputs, sizeof(INPUT));
}

bool TryGetBool(const flutter::EncodableMap& args, const char* key, bool* out) {
  auto it = args.find(flutter::EncodableValue(key));
  if (it == args.end()) return false;
  if (const auto* b = std::get_if<bool>(&it->second)) {
    *out = *b;
    return true;
  }
  return false;
}

bool TryGetInt64(const flutter::EncodableMap& args, const char* key,
                 int64_t* out) {
  auto it = args.find(flutter::EncodableValue(key));
  if (it == args.end()) return false;
  if (const auto* v64 = std::get_if<int64_t>(&it->second)) {
    *out = *v64;
    return true;
  }
  if (const auto* v32 = std::get_if<int32_t>(&it->second)) {
    *out = static_cast<int64_t>(*v32);
    return true;
  }
  return false;
}

bool TryGetString(const flutter::EncodableMap& args, const char* key,
                  std::string* out) {
  auto it = args.find(flutter::EncodableValue(key));
  if (it == args.end()) return false;
  if (const auto* s = std::get_if<std::string>(&it->second)) {
    *out = *s;
    return true;
  }
  return false;
}

bool SetAutoStartRegistry(bool enable) {
  HKEY key = nullptr;
  LSTATUS status = ::RegCreateKeyExW(HKEY_CURRENT_USER, kAutoStartRegSubkey, 0,
                                     nullptr, REG_OPTION_NON_VOLATILE,
                                     KEY_SET_VALUE, nullptr, &key, nullptr);
  if (status != ERROR_SUCCESS || key == nullptr) {
    return false;
  }
  bool ok = false;
  if (enable) {
    std::wstring self_exe = QuerySelfExecutablePath();
    if (!self_exe.empty()) {
      std::wstring val = L"\"" + self_exe + L"\" --minimized";
      DWORD bytes = static_cast<DWORD>((val.size() + 1) * sizeof(wchar_t));
      ok = ::RegSetValueExW(key, kAutoStartRegValue, 0, REG_SZ,
                            reinterpret_cast<const BYTE*>(val.c_str()),
                            bytes) == ERROR_SUCCESS;
    }
  } else {
    LSTATUS del = ::RegDeleteValueW(key, kAutoStartRegValue);
    ok = (del == ERROR_SUCCESS || del == ERROR_FILE_NOT_FOUND);
  }
  ::RegCloseKey(key);
  return ok;
}

bool GetAutoStartRegistry() {
  HKEY key = nullptr;
  LSTATUS status = ::RegOpenKeyExW(HKEY_CURRENT_USER, kAutoStartRegSubkey, 0,
                                   KEY_QUERY_VALUE, &key);
  if (status != ERROR_SUCCESS || key == nullptr) {
    return false;
  }
  DWORD type = 0;
  DWORD size = 0;
  LSTATUS q = ::RegQueryValueExW(key, kAutoStartRegValue, nullptr, &type,
                                 nullptr, &size);
  ::RegCloseKey(key);
  return q == ERROR_SUCCESS && type == REG_SZ && size > 0;
}

void SignalWatchdogStopEvent() {
  HANDLE stop_ev =
      ::OpenEventW(EVENT_MODIFY_STATE, FALSE, kWatchdogStopEventName);
  if (stop_ev != nullptr) {
    ::SetEvent(stop_ev);
    ::CloseHandle(stop_ev);
  }
}

bool EnsureWatchdogState(const std::string& state_dir_utf8, bool strict_armed) {
  if (!strict_armed) {
    SignalWatchdogStopEvent();
    return true;
  }

  HANDLE stop_ev =
      ::OpenEventW(EVENT_MODIFY_STATE, FALSE, kWatchdogStopEventName);
  if (stop_ev != nullptr) {
    ::ResetEvent(stop_ev);
    ::CloseHandle(stop_ev);
  }

  HANDLE existing = ::OpenMutexW(SYNCHRONIZE, FALSE, kWatchdogMutexName);
  if (existing != nullptr) {
    ::CloseHandle(existing);
    return true;
  }

  std::wstring self_exe = QuerySelfExecutablePath();
  if (self_exe.empty()) return false;
  size_t slash = self_exe.find_last_of(L"\\/");
  if (slash == std::wstring::npos) return false;
  std::wstring watchdog_exe =
      self_exe.substr(0, slash + 1) + L"dsh_fish_watchdog.exe";
  if (::GetFileAttributesW(watchdog_exe.c_str()) == INVALID_FILE_ATTRIBUTES) {
    return false;
  }

  std::wstring state_dir_w = Utf8ToWide(state_dir_utf8);
  while (!state_dir_w.empty() &&
         (state_dir_w.back() == L'\\' || state_dir_w.back() == L'/')) {
    state_dir_w.pop_back();
  }
  if (state_dir_w.empty()) return false;

  std::wstring cmdline = L"\"" + watchdog_exe + L"\" --parent-pid " +
                         std::to_wstring(::GetCurrentProcessId()) +
                         L" --exe \"" + self_exe + L"\" --state-dir \"" +
                         state_dir_w + L"\"";

  STARTUPINFOW si{};
  si.cb = sizeof(si);
  PROCESS_INFORMATION pi{};
  BOOL ok = ::CreateProcessW(
      watchdog_exe.c_str(), cmdline.data(), nullptr, nullptr, FALSE,
      CREATE_NO_WINDOW | DETACHED_PROCESS, nullptr, nullptr, &si, &pi);
  if (!ok) {
    return false;
  }
  ::CloseHandle(pi.hThread);
  ::CloseHandle(pi.hProcess);
  return true;
}

}  // namespace

FlutterWindow::FlutterWindow(const flutter::DartProject& project)
    : project_(project) {}

FlutterWindow::~FlutterWindow() {}

bool FlutterWindow::OnCreate() {
  if (!Win32Window::OnCreate()) {
    return false;
  }

  RECT frame = GetClientArea();
  flutter_controller_ = std::make_unique<flutter::FlutterViewController>(
      frame.right - frame.left, frame.bottom - frame.top, project_);
  if (!flutter_controller_->engine() || !flutter_controller_->view()) {
    return false;
  }
  RegisterPlugins(flutter_controller_->engine());
  SetupWin32Channel();
  SetChildContent(flutter_controller_->view()->GetNativeWindow());
  AddTrayIcon();

  flutter_controller_->engine()->SetNextFrameCallback([this]() {
    if (start_minimized_) {
      HWND hwnd = this->GetHandle();
      if (hwnd) {
        ::ShowWindow(hwnd, SW_HIDE);
      }
    } else {
      this->Show();
    }
  });
  flutter_controller_->ForceRedraw();
  return true;
}

void FlutterWindow::AddTrayIcon() {
  HWND hwnd = GetHandle();
  if (!hwnd || tray_added_) return;
  ::ZeroMemory(&nid_, sizeof(nid_));
  nid_.cbSize = sizeof(NOTIFYICONDATAW);
  nid_.hWnd = hwnd;
  nid_.uID = 1;
  nid_.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
  nid_.uCallbackMessage = kTrayCallbackMessage;
  nid_.hIcon =
      ::LoadIconW(::GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APP_ICON));
  if (!nid_.hIcon) {
    nid_.hIcon = ::LoadIconW(nullptr, IDI_APPLICATION);
  }
  wcscpy_s(nid_.szTip, L"DSH Go 大肥鱼 · PC 严防哨兵");
  tray_added_ = (::Shell_NotifyIconW(NIM_ADD, &nid_) == TRUE);
}

void FlutterWindow::RemoveTrayIcon() {
  if (!tray_added_) return;
  ::Shell_NotifyIconW(NIM_DELETE, &nid_);
  tray_added_ = false;
}

void FlutterWindow::RestoreFromTray() {
  HWND hwnd = GetHandle();
  if (!hwnd) return;
  ::ShowWindow(hwnd, SW_SHOW);
  ::ShowWindow(hwnd, SW_RESTORE);
  if (shield_topmost_ || in_capsule_mode_) {
    ::SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                   SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
  }
  ::SetForegroundWindow(hwnd);
}

void FlutterWindow::ShowTrayMenu() {
  HWND hwnd = GetHandle();
  if (!hwnd) return;
  HMENU menu = ::CreatePopupMenu();
  if (!menu) return;
  ::AppendMenuW(menu, MF_STRING, kCmdTrayShow, L"打开主控制台");
  ::AppendMenuW(menu, MF_STRING, kCmdTrayCapsule,
                in_capsule_mode_ ? L"恢复主窗口" : L"切换迷你胶囊");
  ::AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
  if (prevent_close_) {
    ::AppendMenuW(menu, MF_STRING | MF_GRAYED, kCmdTrayStatus,
                  L"严防守护中（请于控制台验证退出）");
  } else {
    ::AppendMenuW(menu, MF_STRING, kCmdTrayExit, L"退出大肥鱼 PC 哨兵");
  }
  POINT pt{};
  ::GetCursorPos(&pt);
  ::SetForegroundWindow(hwnd);
  ::TrackPopupMenu(menu, TPM_RIGHTBUTTON, pt.x, pt.y, 0, hwnd, nullptr);
  ::PostMessageW(hwnd, WM_NULL, 0, 0);
  ::DestroyMenu(menu);
}

void FlutterWindow::SetupWin32Channel() {
  win32_channel_ =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          flutter_controller_->engine()->messenger(), "dsh_go_fish_pc/win32",
          &flutter::StandardMethodCodec::GetInstance());

  win32_channel_->SetMethodCallHandler(
      [this](const flutter::MethodCall<flutter::EncodableValue>& call,
             std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>>
                 result) {
        const std::string& method = call.method_name();
        const auto* args = std::get_if<flutter::EncodableMap>(call.arguments());

        if (method == "getForeground") {
          HWND fg = ::GetForegroundWindow();
          std::wstring title_w;
          if (fg) {
            int len = ::GetWindowTextLengthW(fg);
            if (len > 0) {
              title_w.resize(static_cast<size_t>(len + 1), L'\0');
              int copied = ::GetWindowTextW(fg, title_w.data(), len + 1);
              title_w.resize(static_cast<size_t>(std::max(0, copied)));
            }
          }
          DWORD pid = 0;
          if (fg) ::GetWindowThreadProcessId(fg, &pid);
          std::string app_name = QueryProcessBasename(pid);
          bool screen_locked = QueryScreenLocked(fg, AsciiLower(app_name));
          flutter::EncodableMap map;
          map[flutter::EncodableValue("app")] =
              flutter::EncodableValue(app_name);
          map[flutter::EncodableValue("title")] =
              flutter::EncodableValue(WideToUtf8(title_w));
          map[flutter::EncodableValue("idleSec")] =
              flutter::EncodableValue(QueryIdleSeconds());
          map[flutter::EncodableValue("selfActive")] =
              flutter::EncodableValue(fg == GetHandle());
          map[flutter::EncodableValue("screenLocked")] =
              flutter::EncodableValue(screen_locked);
          map[flutter::EncodableValue("pid")] =
              flutter::EncodableValue(static_cast<int64_t>(pid));
          map[flutter::EncodableValue("hwnd")] = flutter::EncodableValue(
              static_cast<int64_t>(reinterpret_cast<uintptr_t>(fg)));
          result->Success(flutter::EncodableValue(map));
          return;
        }

        if (method == "minimizeWindow") {
          int64_t raw_hwnd = 0;
          bool ok = false;
          if (args && TryGetInt64(*args, "hwnd", &raw_hwnd) && raw_hwnd != 0) {
            HWND target =
                reinterpret_cast<HWND>(static_cast<uintptr_t>(raw_hwnd));
            if (target != GetHandle() && ::IsWindow(target)) {
              ok = (::ShowWindow(target, SW_MINIMIZE) != FALSE);
            }
          }
          result->Success(flutter::EncodableValue(ok));
          return;
        }

        if (method == "terminateProcess") {
          int64_t raw_pid = 0;
          std::string expected_app;
          bool killed = false;
          if (args && TryGetInt64(*args, "pid", &raw_pid) && raw_pid > 0 &&
              TryGetString(*args, "expectedApp", &expected_app)) {
            DWORD pid = static_cast<DWORD>(raw_pid);
            if (pid != ::GetCurrentProcessId()) {
              std::string actual_lower = AsciiLower(QueryProcessBasename(pid));
              std::string expected_lower = AsciiLower(expected_app);
              if (!actual_lower.empty() && actual_lower == expected_lower &&
                  !IsProtectedProcessBasename(actual_lower)) {
                HANDLE proc = ::OpenProcess(PROCESS_TERMINATE, FALSE, pid);
                if (proc != nullptr) {
                  killed = (::TerminateProcess(proc, 1) == TRUE);
                  ::CloseHandle(proc);
                }
              }
            }
          }
          result->Success(flutter::EncodableValue(killed));
          return;
        }

        if (method == "setShieldTopmost") {
          bool topmost = false;
          if (args) {
            TryGetBool(*args, "topmost", &topmost);
          }
          shield_topmost_ = topmost;
          HWND hwnd = GetHandle();
          if (hwnd) {
            if (topmost) {
              ::ShowWindow(hwnd, SW_SHOW);
              ::ShowWindow(hwnd, SW_RESTORE);
              ::SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                             SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
              ::SetForegroundWindow(hwnd);
            } else if (!in_capsule_mode_) {
              ::SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
                             SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            }
          }
          result->Success(flutter::EncodableValue(true));
          return;
        }

        if (method == "raiseWindow") {
          HWND hwnd = GetHandle();
          if (hwnd) {
            ::ShowWindow(hwnd, SW_SHOW);
            ::ShowWindow(hwnd, SW_RESTORE);
            ::SetForegroundWindow(hwnd);
          }
          result->Success(flutter::EncodableValue(true));
          return;
        }

        if (method == "sendMediaPlayPause") {
          SendMediaPlayPause();
          result->Success(flutter::EncodableValue(true));
          return;
        }

        if (method == "setCapsuleMode") {
          bool enable = false;
          if (args) {
            TryGetBool(*args, "capsule", &enable);
          }
          HWND hwnd = GetHandle();
          if (hwnd) {
            if (enable && !in_capsule_mode_) {
              ::GetWindowRect(hwnd, &saved_normal_rect_);
              RECT work{};
              ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
              int w = 440;
              int h = 136;
              int x = work.right - w - 20;
              int y = work.top + 20;
              ::ShowWindow(hwnd, SW_SHOW);
              ::SetWindowPos(hwnd, HWND_TOPMOST, x, y, w, h, SWP_SHOWWINDOW);
              in_capsule_mode_ = true;
            } else if (!enable && in_capsule_mode_) {
              int w = std::max<int>(
                  960, saved_normal_rect_.right - saved_normal_rect_.left);
              int h = std::max<int>(
                  640, saved_normal_rect_.bottom - saved_normal_rect_.top);
              HWND z_order = shield_topmost_ ? HWND_TOPMOST : HWND_NOTOPMOST;
              ::ShowWindow(hwnd, SW_SHOW);
              ::SetWindowPos(hwnd, z_order, saved_normal_rect_.left,
                             saved_normal_rect_.top, w, h, SWP_SHOWWINDOW);
              in_capsule_mode_ = false;
            }
          }
          result->Success(flutter::EncodableValue(in_capsule_mode_));
          return;
        }

        if (method == "setPreventClose") {
          bool prevent = false;
          if (args) {
            TryGetBool(*args, "prevent", &prevent);
          }
          prevent_close_ = prevent;
          result->Success(flutter::EncodableValue(prevent_close_));
          return;
        }

        if (method == "setAutoStart") {
          bool enabled = false;
          if (args) {
            TryGetBool(*args, "enabled", &enabled);
          }
          result->Success(
              flutter::EncodableValue(SetAutoStartRegistry(enabled)));
          return;
        }

        if (method == "getAutoStart") {
          result->Success(flutter::EncodableValue(GetAutoStartRegistry()));
          return;
        }

        if (method == "ensureWatchdog") {
          std::string state_dir;
          bool strict_armed = false;
          if (args) {
            TryGetString(*args, "stateDir", &state_dir);
            TryGetBool(*args, "strictArmed", &strict_armed);
          }
          bool ok = EnsureWatchdogState(state_dir, strict_armed);
          result->Success(flutter::EncodableValue(ok));
          return;
        }

        if (method == "authorizedExit") {
          SignalWatchdogStopEvent();
          prevent_close_ = false;
          shield_topmost_ = false;
          RemoveTrayIcon();
          result->Success(flutter::EncodableValue(true));
          HWND hwnd = GetHandle();
          if (hwnd) {
            ::DestroyWindow(hwnd);
          }
          return;
        }

        result->NotImplemented();
      });
}

void FlutterWindow::OnDestroy() {
  if (!prevent_close_) {
    SignalWatchdogStopEvent();
  }
  RemoveTrayIcon();
  win32_channel_ = nullptr;
  if (flutter_controller_) {
    flutter_controller_ = nullptr;
  }
  Win32Window::OnDestroy();
}

LRESULT
FlutterWindow::MessageHandler(HWND hwnd, UINT const message,
                              WPARAM const wparam,
                              LPARAM const lparam) noexcept {
  if (message == WM_QUERYENDSESSION) {
    // Never block Windows shutdown, restart, or logoff.
    return TRUE;
  }

  if (message == WM_ENDSESSION) {
    if (wparam != FALSE) {
      SignalWatchdogStopEvent();
      prevent_close_ = false;
      shield_topmost_ = false;
      RemoveTrayIcon();
      ::DestroyWindow(hwnd);
    }
    return 0;
  }

  if (prevent_close_ &&
      (message == WM_CLOSE ||
       (message == WM_SYSCOMMAND && (wparam & 0xFFF0) == SC_CLOSE))) {
    ::ShowWindow(hwnd, SW_HIDE);
    if (win32_channel_) {
      win32_channel_->InvokeMethod("onCloseIntercepted", nullptr);
    }
    return 0;
  }

  if (message == kShowMainFromSecondInstance) {
    RestoreFromTray();
    if (win32_channel_) {
      win32_channel_->InvokeMethod("onTrayShowMain", nullptr);
    }
    return 0;
  }

  if (message == kTrayCallbackMessage) {
    UINT event_id = LOWORD(lparam);
    if (event_id == WM_LBUTTONUP || event_id == WM_LBUTTONDBLCLK) {
      RestoreFromTray();
      if (win32_channel_) {
        win32_channel_->InvokeMethod("onTrayShowMain", nullptr);
      }
      return 0;
    }
    if (event_id == WM_RBUTTONUP || event_id == WM_CONTEXTMENU) {
      ShowTrayMenu();
      return 0;
    }
  }

  if (message == WM_COMMAND) {
    UINT_PTR cmd = LOWORD(wparam);
    if (cmd == kCmdTrayShow) {
      RestoreFromTray();
      if (win32_channel_) {
        win32_channel_->InvokeMethod("onTrayShowMain", nullptr);
      }
      return 0;
    }
    if (cmd == kCmdTrayCapsule) {
      RestoreFromTray();
      if (win32_channel_) {
        win32_channel_->InvokeMethod("onToggleCapsuleFromTray", nullptr);
      }
      return 0;
    }
    if (cmd == kCmdTrayExit && !prevent_close_) {
      SignalWatchdogStopEvent();
      RemoveTrayIcon();
      ::DestroyWindow(hwnd);
      return 0;
    }
  }

  if (flutter_controller_) {
    std::optional<LRESULT> result =
        flutter_controller_->HandleTopLevelWindowProc(hwnd, message, wparam,
                                                      lparam);
    if (result) {
      return *result;
    }
  }

  switch (message) {
    case WM_FONTCHANGE:
      if (flutter_controller_) {
        flutter_controller_->engine()->ReloadSystemFonts();
      }
      break;
  }

  return Win32Window::MessageHandler(hwnd, message, wparam, lparam);
}
