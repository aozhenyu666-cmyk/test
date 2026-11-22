# PC Companion Sync (`M4.3`)

轻量零依赖（Node >= 20）桌面协同脚本，将本机 [ActivityWatch](https://activitywatch.net/)（`http://127.0.0.1:5600`）的活跃窗口分类、AFK 状态与专注分钟数聚合后推送至手机端 `dsh-go-companion`（局域网 / Tailscale）。

## 用法

```bash
# 单次读取并输出摘要（不推送）
node tooling/pc-companion-sync/sync.mjs

# 每 60 秒向手机端 Tailscale/LAN 伴侣端点发送心跳并写本地快照
node tooling/pc-companion-sync/sync.mjs \
  --aw http://127.0.0.1:5600 \
  --target http://100.64.0.12:3000/dsh-go-companion/v1/event \
  --out ./pc-heartbeat.json \
  --interval-sec 60
```

## 环境变量

- `AW_BASE_URL`：ActivityWatch 地址，默认 `http://127.0.0.1:5600`
- `COMPANION_EVENT_URL`：伴侣事件接收地址
- `AW_LOOKBACK_SEC`：统计回看窗口秒数，默认 `900`（15 分钟）
