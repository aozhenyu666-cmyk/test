"""Offline demonstration; intentionally does not call any model or execute tasks."""
import argparse
import sys
import time

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", default="演示 Agent")
    parser.add_argument("--delay", type=float, default=.5)
    args = parser.parse_args()
    prompt = sys.stdin.read()
    time.sleep(args.delay)
    print(f"[{args.name}] 已收到平台自动输入的任务。")
    print(f"收到 {len(prompt)} 字的任务与上下文。")
    print("已验证：命令启动 → 标准输入 → 结果输出 → 交还平台。")
    print("这是离线演示回执，没有实际执行用户任务，也没有调用模型。")


if __name__ == "__main__":
    main()
