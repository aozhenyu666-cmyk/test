@echo off
chcp 65001 >nul
rem 大肥鱼 · Gemini 桥：开机自动跑。复制成 start-bridge.cmd，改下面两行，再把快捷方式放进 shell:startup。
set BRIDGE_URL=https://你的电脑名.你的tailnet.ts.net
set VAULT=D:\Obsidian\我的库

cd /d "%~dp0"
tailscale funnel --bg 8787
:loop
node server.mjs --public-url %BRIDGE_URL% --vault "%VAULT%"
echo 桥退出了，5 秒后重启……
timeout /t 5 /nobreak >nul
goto loop
