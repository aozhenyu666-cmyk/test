"""Launch Windows npm .cmd shims while preserving inherited stdin/console."""
import os
import re
import subprocess
import sys


def quoted(arg):
    # Force quotes even without spaces: paths may contain shell metacharacters.
    value = re.sub(r'(\\*)"', lambda m: m.group(1) * 2 + '\\"', arg)
    trailing = len(value) - len(value.rstrip('\\'))
    return '"' + value + '\\' * trailing + '"'


def command_line(argv, comspec):
    # /s /c strips the outer pair; inner quotes delimit each argument.
    return subprocess.list2cmdline([comspec]) + ' /d /s /c "' + ' '.join(quoted(a) for a in argv) + '"'


def main():
    if os.name != "nt" or len(sys.argv) < 2:
        sys.exit("This wrapper only launches configured Windows batch commands.")
    command = command_line(sys.argv[1:], os.environ.get("COMSPEC", "cmd.exe"))
    # The command is local configuration; model/user prompts stay in stdin.
    flags = subprocess.CREATE_NO_WINDOW if not sys.stdin.isatty() else 0
    sys.exit(subprocess.call(command, shell=False, creationflags=flags))


if __name__ == "__main__":
    main()
