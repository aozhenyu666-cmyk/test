#!/bin/bash
# 大肥鱼人格测试：真模型（key 由代理注入或 DEEPSEEK_API_KEY），结果在 .work-persona/persona-results.json
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${E2E_WORK:-$HERE/.work-persona}"; ENG="$HERE/.engine"; PORT=18080
export E2E_WORK="$WORK" E2E_BASE="http://127.0.0.1:$PORT"
[ -x "$ENG/node_modules/.bin/dsh" ] || { echo "先跑一次 run.sh 装引擎"; exit 2; }
rm -rf "$WORK"; mkdir -p "$WORK/home"
P="$ROOT/app/assets/engine/plugins"
{ echo "- insert:"; for id in dsh-feiyu-mood dsh-go-memory dsh-go-relationship dsh-go-companion; do
  echo "    - id: $id"; echo "      name: $P/$id/lib/index.js"; done; } > "$WORK/patch.yml"
pkill -f "dsh/lib/bin.js web" 2>/dev/null; while pgrep -f "dsh/lib/bin.js web" >/dev/null; do :; done
(cd "$WORK" && DSH_HOME="$WORK/home" HOME="$WORK/home" DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-proxy-injected}" TZ=GMT \
  nohup node "$ENG/node_modules/@deepseek-ai/dsh/lib/bin.js" web --patch "$WORK/patch.yml" --host 127.0.0.1 --port $PORT --no-open > "$WORK/dsh.log" 2>&1 &)
for i in $(seq 1 300); do grep -q "dsh web: http" "$WORK/dsh.log" 2>/dev/null && break; timeout 0.2 tail -f /dev/null; done
tok=$(grep -o 'token=[^ ]*' "$WORK/dsh.log" | head -1 | cut -d= -f2); curl -s -c "$WORK/c.txt" -o /dev/null "$E2E_BASE/?token=$tok"
node "$HERE/persona.mjs"; rc=$?
pkill -f "dsh/lib/bin.js web" 2>/dev/null
exit $rc
