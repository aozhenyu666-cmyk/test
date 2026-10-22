#!/usr/bin/env bash
# 开发用：在 Linux 上一键起一个"假模型 + N.E.K.O. + 伴生插件"的测试环境（没有声音设备也能跑）。
#   bash bansheng/scripts/dev_neko_up.sh <N.E.K.O.目录> [--real]
# --real：不起假模型，沿用当前环境里的 BANSHENG_LLM_* 变量。
# 日志：<N.E.K.O.目录>/../bansheng-dev-*.log
set -euo pipefail
NEKO="$(cd "$1" && pwd)"; shift || true
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGDIR="$(dirname "$NEKO")"
REAL=0; [[ "${1:-}" == "--real" ]] && REAL=1

if [[ $REAL == 0 ]]; then
  if ! curl -s -m1 -o /dev/null http://127.0.0.1:48999/v1/chat/completions -XPOST -d '{}' ; then
    nohup setsid python3 "$HERE/mock_llm_server.py" 48999 > "$LOGDIR/bansheng-dev-mock.log" 2>&1 < /dev/null &
  fi
  export BANSHENG_LLM_BASE_URL=http://127.0.0.1:48999/v1 BANSHENG_LLM_API_KEY=x BANSHENG_LLM_MODEL=mock-mind
fi

python3 "$HERE/install_into_neko.py" "$NEKO" --patch
( cd "$NEKO" && nohup setsid .venv/bin/python launcher.py > "$LOGDIR/bansheng-dev-neko.log" 2>&1 < /dev/null & )
for _ in $(seq 1 60); do sleep 2; curl -s -m2 -o /dev/null http://127.0.0.1:48916/plugins && break; done

# 首次运行 N.E.K.O. 会等前端确认数据目录；无头环境里直接确认当前目录
ROOT=$(curl -s http://127.0.0.1:48911/api/storage/location/status | python3 -c "import sys,json;print(json.load(sys.stdin).get('effective_root',''))")
curl -s -XPOST http://127.0.0.1:48911/api/storage/location/select -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:48911' -d "{\"selected_root\":\"$ROOT\",\"selection_source\":\"current\"}" > /dev/null
# 打开 Agent 里的“用户插件”开关：不开的话 N.E.K.O. 不把对话转给插件
curl -s -XPOST http://127.0.0.1:48915/agent/flags -H 'Content-Type: application/json' -d '{"user_plugin_enabled":true}' > /dev/null
curl -s -XPOST http://127.0.0.1:48916/plugin/bansheng/start > /dev/null
sleep 3
curl -s http://127.0.0.1:48925/state | python3 -c "import sys,json;d=json.load(sys.stdin);print('伴生已就绪:', d['llm'], d['host'])"
