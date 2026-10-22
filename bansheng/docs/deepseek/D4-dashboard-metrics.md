# D4：面板显示延迟表

## 背景

伴生服务的面板是一个静态页面 `bansheng/neko_plugin/bansheng/engine/static/dashboard.html`，每 1.5 秒拉一次 `/state` 和 `/ledger` 刷新。服务新增了 `GET /metrics`，返回：

```json
{
  "stages": {
    "材料出现→开始思考": {"n": 5, "median": 0.62, "p90": 0.71},
    "模型思考": {"n": 5, "median": 1.8, "p90": 3.2},
    "思考完→交给角色（含等待话权）": {"n": 5, "median": 2.1, "p90": 6.0},
    "交给角色→角色开口": {"n": 3, "median": 1.2, "p90": 1.9},
    "材料出现→角色开口（总计）": {"n": 3, "median": 5.4, "p90": 9.8},
    "角色说话时长": {"n": 3, "median": 2.5, "p90": 4.0},
    "交给角色→播放结束": {"n": 0, "median": null, "p90": null}
  },
  "delivered": 5,
  "cancelled": 12,
  "cancel_reasons": {"user_continued": 6, "main_ai_moved_on": 3, "below_threshold": 3},
  "note": "说明文字"
}
```

## 你要做的

只改 `dashboard.html`：

1. 在“诊断记录”卡片上方加一张“反应速度”卡片：表格三列——阶段、典型值（中位数）、慢的时候（P90），再加一列样本数。数值显示成“1.8 秒”，`null` 显示“—”。表格下面用小字显示 `note`。
2. 同一张卡片里显示“说了 N 句 / 忍住 M 句”，以及忍住原因的分布，原因翻译成中文：
   `user_continued` 你接着说了 · `main_ai_moved_on` 主力 AI 又回复了 · `below_threshold` 不够值得说 · `repetitive` 和说过的重复 · `expired` 等太久过期 · `muted` 静音 · `proactive_off` 主动关闭 · `basis_retired` 依据被改口取代 · `superseded_by_new_thought` 有了新想法 · `sibling_delivered` 同批另一句说了 · `deliver_failed` 交付失败 · 其他原样显示。
3. `/metrics` 每 5 秒拉一次即可（不要每 1.5 秒）。请求失败时卡片显示“暂时取不到”。
4. 风格和现有卡片一致（使用已有的 CSS 变量，支持深色模式），手机宽度下不横向溢出（表格可以放在 `overflow-x:auto` 的容器里）。
5. 用 `esc()`（文件里已有）处理所有插入 HTML 的文字。

## 注意

- 只改这一个文件，不新增依赖，注释用中文。

## 验收命令

```bash
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs-d4 &
python bansheng/scripts/smoke_http.py
# 浏览器打开 http://127.0.0.1:48925/ ，确认“反应速度”卡片有数据，宽度缩到 375px 不出现横向滚动
```
