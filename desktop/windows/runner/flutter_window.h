#ifndef RUNNER_FLUTTER_WINDOW_H_
#define RUNNER_FLUTTER_WINDOW_H_

#include <flutter/dart_project.h>
#include <flutter/encodable_value.h>
#include <flutter/flutter_view_controller.h>
#include <flutter/method_channel.h>
#include <shellapi.h>

#include <memory>

#include "win32_window.h"

// Hosts the Flutter desktop view plus Win32 MethodChannel integration for active window
// observation, AFK detection, process enforcement, system tray, anti-close interception,
// auto-start registry setup, and companion watchdog management.
class FlutterWindow : public Win32Window {
 public:
  explicit FlutterWindow(const flutter::DartProject& project);
  virtual ~FlutterWindow();

  void SetStartMinimized(bool start_minimized) {
    start_minimized_ = start_minimized;
  }

 protected:
  bool OnCreate() override;
  void OnDestroy() override;
  LRESULT MessageHandler(HWND window, UINT const message, WPARAM const wparam,
                         LPARAM const lparam) noexcept override;

 private:
  void SetupWin32Channel();
  void AddTrayIcon();
  void RemoveTrayIcon();
  void RestoreFromTray();
  void ShowTrayMenu();

  flutter::DartProject project_;
  std::unique_ptr<flutter::FlutterViewController> flutter_controller_;
  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> win32_channel_;
  NOTIFYICONDATAW nid_{};
  bool tray_added_ = false;
  RECT saved_normal_rect_{100, 80, 1340, 840};
  bool in_capsule_mode_ = false;
  bool prevent_close_ = false;
  bool start_minimized_ = false;
  bool shield_topmost_ = false;
};

#endif  // RUNNER_FLUTTER_WINDOW_H_
