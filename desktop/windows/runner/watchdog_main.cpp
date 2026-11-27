#include <windows.h>
#include <shellapi.h>

#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cwchar>
#include <string>
#include <vector>

namespace {

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

uint64_t CurrentEpochMs() {
  FILETIME ft{};
  ::GetSystemTimeAsFileTime(&ft);
  ULARGE_INTEGER uli{};
  uli.LowPart = ft.dwLowDateTime;
  uli.HighPart = ft.dwHighDateTime;
  constexpr uint64_t kEpochOffset = 116444736000000000ULL;
  if (uli.QuadPart < kEpochOffset) return 0ULL;
  return (uli.QuadPart - kEpochOffset) / 10000ULL;
}

bool FileExists(const std::wstring& path) {
  DWORD attrs = ::GetFileAttributesW(path.c_str());
  return attrs != INVALID_FILE_ATTRIBUTES &&
         (attrs & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

std::string ReadSmallUtf8File(const std::wstring& path) {
  HANDLE h = ::CreateFileW(
      path.c_str(), GENERIC_READ,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
      OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    return {};
  }
  std::string out;
  char buf[4096];
  DWORD read_bytes = 0;
  while (::ReadFile(h, buf, static_cast<DWORD>(sizeof(buf)), &read_bytes,
                    nullptr) &&
         read_bytes > 0) {
    out.append(buf, static_cast<size_t>(read_bytes));
    if (out.size() >= 65536) {
      break;
    }
  }
  ::CloseHandle(h);
  return out;
}

bool IsStrictArmedInStateDir(const std::wstring& state_dir) {
  if (state_dir.empty()) return false;
  std::wstring exit_flag = state_dir + L"\\authorized_exit.flag";
  if (FileExists(exit_flag)) {
    return false;
  }
  std::wstring lock_file = state_dir + L"\\guard_lock.json";
  std::string json = ReadSmallUtf8File(lock_file);
  if (json.empty()) {
    return false;
  }
  // Strip whitespace so `"strictArmed": true` and `"strictArmed":true` match uniformly.
  std::string compact;
  compact.reserve(json.size());
  for (char ch : json) {
    if (!std::isspace(static_cast<unsigned char>(ch))) {
      compact.push_back(ch);
    }
  }
  return compact.find("\"strictArmed\":true") != std::string::npos;
}

void AppendWatchdogLog(const std::wstring& state_dir, const std::string& reason) {
  if (state_dir.empty()) return;
  ::CreateDirectoryW(state_dir.c_str(), nullptr);
  std::wstring log_path = state_dir + L"\\watchdog_restarts.log";
  HANDLE h = ::CreateFileW(
      log_path.c_str(), FILE_APPEND_DATA,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
      OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    return;
  }
  std::string line = std::to_string(CurrentEpochMs()) + "|" + reason + "\n";
  DWORD written = 0;
  ::WriteFile(h, line.data(), static_cast<DWORD>(line.size()), &written, nullptr);
  ::CloseHandle(h);
}

bool IsProcessAlive(DWORD pid) {
  if (pid == 0) return false;
  HANDLE proc = ::OpenProcess(SYNCHRONIZE, FALSE, pid);
  if (!proc) {
    DWORD err = ::GetLastError();
    if (err == ERROR_ACCESS_DENIED) {
      return true;
    }
    return false;
  }
  DWORD wait_res = ::WaitForSingleObject(proc, 0);
  ::CloseHandle(proc);
  return wait_res == WAIT_TIMEOUT;
}

DWORD RespawnParentMinimized(const std::wstring& exe_path) {
  if (exe_path.empty()) return 0;
  std::wstring cmdline = L"\"" + exe_path + L"\" --minimized";
  STARTUPINFOW si{};
  si.cb = sizeof(si);
  PROCESS_INFORMATION pi{};
  BOOL ok = ::CreateProcessW(
      exe_path.c_str(), cmdline.data(), nullptr, nullptr, FALSE,
      CREATE_NEW_PROCESS_GROUP, nullptr, nullptr, &si, &pi);
  if (!ok) {
    return 0;
  }
  DWORD new_pid = pi.dwProcessId;
  ::CloseHandle(pi.hThread);
  ::CloseHandle(pi.hProcess);
  return new_pid;
}

}  // namespace

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t* command_line, _In_ int show_command) {
  (void)instance;
  (void)prev;
  (void)command_line;
  (void)show_command;

  int argc = 0;
  wchar_t** argv = ::CommandLineToArgvW(::GetCommandLineW(), &argc);
  DWORD parent_pid = 0;
  std::wstring exe_path;
  std::wstring state_dir;

  if (argv != nullptr) {
    for (int i = 1; i + 1 < argc; ++i) {
      std::wstring arg = argv[i];
      if (arg == L"--parent-pid") {
        parent_pid = static_cast<DWORD>(wcstoul(argv[i + 1], nullptr, 10));
        ++i;
      } else if (arg == L"--exe") {
        exe_path = argv[i + 1];
        ++i;
      } else if (arg == L"--state-dir") {
        state_dir = argv[i + 1];
        ++i;
      }
    }
    ::LocalFree(argv);
  }

  if (parent_pid == 0 || exe_path.empty() || state_dir.empty()) {
    return 1;
  }

  HANDLE mutex = ::CreateMutexW(nullptr, TRUE, kWatchdogMutexName);
  if (!mutex) {
    return 1;
  }
  if (::GetLastError() == ERROR_ALREADY_EXISTS) {
    ::CloseHandle(mutex);
    return 0;
  }

  HANDLE stop_event =
      ::CreateEventW(nullptr, TRUE, FALSE, kWatchdogStopEventName);
  if (!stop_event) {
    ::CloseHandle(mutex);
    return 1;
  }

  std::vector<ULONGLONG> recent_restarts;

  for (;;) {
    if (!IsStrictArmedInStateDir(state_dir)) {
      break;
    }

    DWORD wait_rc = ::WaitForSingleObject(stop_event, 500);
    if (wait_rc == WAIT_OBJECT_0) {
      break;
    }

    if (!IsStrictArmedInStateDir(state_dir)) {
      break;
    }

    if (!IsProcessAlive(parent_pid)) {
      // Re-check arming flags right before relaunch in case parent disarmed and exited.
      if (!IsStrictArmedInStateDir(state_dir)) {
        break;
      }

      ULONGLONG now_tick = ::GetTickCount64();
      std::vector<ULONGLONG> filtered;
      for (ULONGLONG ts : recent_restarts) {
        if (now_tick >= ts && (now_tick - ts) <= 60000ULL) {
          filtered.push_back(ts);
        }
      }
      recent_restarts.swap(filtered);

      if (recent_restarts.size() >= 5) {
        AppendWatchdogLog(
            state_dir,
            "circuit_breaker_pause: 60s 内连续拉起超过 5 次，熔断冷却 120 秒");
        DWORD cooldown_rc = ::WaitForSingleObject(stop_event, 120000);
        if (cooldown_rc == WAIT_OBJECT_0) {
          break;
        }
        recent_restarts.clear();
        continue;
      }

      DWORD new_pid = RespawnParentMinimized(exe_path);
      if (new_pid != 0) {
        recent_restarts.push_back(now_tick);
        AppendWatchdogLog(
            state_dir,
            "respawned_after_abnormal_exit: old_pid=" +
                std::to_string(parent_pid) + " -> new_pid=" +
                std::to_string(new_pid) + " (" + WideToUtf8(exe_path) + ")");
        parent_pid = new_pid;
      } else {
        AppendWatchdogLog(
            state_dir,
            "respawn_failed: err=" + std::to_string(::GetLastError()));
      }
    }
  }

  ::CloseHandle(stop_event);
  ::CloseHandle(mutex);
  return 0;
}
