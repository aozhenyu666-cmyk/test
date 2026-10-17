"""Both native executables use this entry point; CLI helpers never import the GUI."""
import multiprocessing
import sys


def main():
    multiprocessing.freeze_support()
    flag = sys.argv[1] if len(sys.argv) > 1 else ""
    if flag in {"--mcp", "--demo", "--batch-launch", "--serve", "--self-test", "--window-self-test"}:
        sys.argv.pop(1)
        if flag == "--mcp":
            from mcp_server import main
        elif flag == "--demo":
            from demo_agent import main
        elif flag == "--batch-launch":
            from windows_launcher import main
        elif flag == "--serve":
            from bridge import main
        elif flag == "--window-self-test":
            from packaging_window_check import main
        else:
            from packaging_check import main
        main()
    else:
        from desktop import main, report_error
        try:
            main()
        except Exception as exc:
            report_error(exc)
            raise SystemExit(1)


if __name__ == "__main__":
    main()
