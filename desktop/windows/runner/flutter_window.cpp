#include "flutter_window.h"

#include <flutter/standard_method_codec.h>

#include <algorithm>
#include <optional>
#include <string>

#include "flutter/generated_plugin_registrant.h"

namespace {

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
    std::wstring base = (slash == std::wstring::npos) ? full : full.substr(slash + 1);
    size_t dot = base.find_last_of(L'.');
    if (dot != std::wstring::npos) {
      base = base.substr(0, dot);
    }
    result = WideToUtf8(base);
  }
  ::CloseHandle(proc);
  return result;
}

int QueryIdleSeconds() {
  LASTINPUTINFO lii{};
  lii.cbSize = sizeof(LASTINPUTINFO);
  if (!::GetLastInputInfo(&lii)) return 0;
  ULONGLONG now_ms = ::GetTickCount64();
  ULONGLONG last_ms = static_cast<ULONGLONG>(lii.dwTime);
  ULONGLONG diff_ms = now_ms >= last_ms ? (now_ms - last_ms) : 0;
  return static_cast<int>(diff_ms / 1000ULL);
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

  flutter_controller_->engine()->SetNextFrameCallback([&]() {
    this->Show();
  });
  flutter_controller_->ForceRedraw();
  return true;
}

void FlutterWindow::SetupWin32Channel() {
  win32_channel_ = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      flutter_controller_->engine()->messenger(), "dsh_go_fish_pc/win32",
      &flutter::StandardMethodCodec::GetInstance());

  win32_channel_->SetMethodCallHandler(
      [this](const flutter::MethodCall<flutter::EncodableValue>& call,
             std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        const std::string& method = call.method_name();
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
          flutter::EncodableMap map;
          map[flutter::EncodableValue("app")] =
              flutter::EncodableValue(QueryProcessBasename(pid));
          map[flutter::EncodableValue("title")] =
              flutter::EncodableValue(WideToUtf8(title_w));
          map[flutter::EncodableValue("idleSec")] =
              flutter::EncodableValue(QueryIdleSeconds());
          map[flutter::EncodableValue("selfActive")] =
              flutter::EncodableValue(fg == GetHandle());
          result->Success(flutter::EncodableValue(map));
          return;
        }
        if (method == "sendMediaPlayPause") {
          SendMediaPlayPause();
          result->Success(flutter::EncodableValue(true));
          return;
        }
        if (method == "setCapsuleMode") {
          bool enable = false;
          if (const auto* args = std::get_if<flutter::EncodableMap>(call.arguments())) {
            auto it = args->find(flutter::EncodableValue("capsule"));
            if (it != args->end()) {
              if (const auto* b = std::get_if<bool>(&it->second)) {
                enable = *b;
              }
            }
          }
          HWND hwnd = GetHandle();
          if (hwnd) {
            if (enable && !in_capsule_mode_) {
              ::GetWindowRect(hwnd, &saved_normal_rect_);
              RECT work{};
              ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
              int w = 420;
              int h = 136;
              int x = work.right - w - 20;
              int y = work.top + 20;
              ::SetWindowPos(hwnd, HWND_TOPMOST, x, y, w, h, SWP_SHOWWINDOW);
              in_capsule_mode_ = true;
            } else if (!enable && in_capsule_mode_) {
              int w = std::max<int>(920, saved_normal_rect_.right - saved_normal_rect_.left);
              int h = std::max<int>(620, saved_normal_rect_.bottom - saved_normal_rect_.top);
              ::SetWindowPos(hwnd, HWND_NOTOPMOST, saved_normal_rect_.left,
                             saved_normal_rect_.top, w, h, SWP_SHOWWINDOW);
              in_capsule_mode_ = false;
            }
          }
          result->Success(flutter::EncodableValue(in_capsule_mode_));
          return;
        }
        if (method == "raiseWindow") {
          HWND hwnd = GetHandle();
          if (hwnd) {
            ::ShowWindow(hwnd, SW_RESTORE);
            ::SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                           SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            ::SetForegroundWindow(hwnd);
          }
          result->Success(flutter::EncodableValue(true));
          return;
        }
        result->NotImplemented();
      });
}

void FlutterWindow::OnDestroy() {
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
      flutter_controller_->engine()->ReloadSystemFonts();
      break;
  }

  return Win32Window::MessageHandler(hwnd, message, wparam, lparam);
}
