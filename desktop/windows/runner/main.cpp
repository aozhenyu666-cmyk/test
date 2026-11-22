#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>

#include <string>
#include <vector>

#include "flutter_window.h"
#include "utils.h"

namespace {

constexpr wchar_t kWindowTitle[] = L"DSH Go 大肥鱼 · PC 严防哨兵";
constexpr wchar_t kSingleInstanceMutex[] = L"Local\\DshGoFishPc_SingleInstance_v2";

}  // namespace

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t* command_line, _In_ int show_command) {
  (void)instance;
  (void)prev;
  (void)command_line;
  (void)show_command;

  std::vector<std::string> command_line_arguments = GetCommandLineArguments();
  bool start_minimized = false;
  for (const std::string& arg : command_line_arguments) {
    if (arg == "--minimized") {
      start_minimized = true;
      break;
    }
  }

  HANDLE single_mutex = ::CreateMutexW(nullptr, TRUE, kSingleInstanceMutex);
  if (single_mutex != nullptr && ::GetLastError() == ERROR_ALREADY_EXISTS) {
    if (!start_minimized) {
      HWND existing = ::FindWindowW(L"FLUTTER_RUNNER_WIN32_WINDOW", kWindowTitle);
      if (existing != nullptr) {
        ::ShowWindow(existing, SW_SHOW);
        ::ShowWindow(existing, SW_RESTORE);
        ::SetForegroundWindow(existing);
      }
    }
    ::CloseHandle(single_mutex);
    return EXIT_SUCCESS;
  }

  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  flutter::DartProject project(L"data");
  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  FlutterWindow window(project);
  window.SetStartMinimized(start_minimized);
  Win32Window::Point origin(80, 60);
  Win32Window::Size size(1240, 760);
  if (!window.Create(kWindowTitle, origin, size)) {
    if (single_mutex != nullptr) {
      ::CloseHandle(single_mutex);
    }
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  if (single_mutex != nullptr) {
    ::CloseHandle(single_mutex);
  }
  return EXIT_SUCCESS;
}
