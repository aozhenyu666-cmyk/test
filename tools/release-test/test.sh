#!/usr/bin/env bash
# 第八批 R1 的发布脚本测试（mock gh / curl，不联网、不碰真实 release）。
# 覆盖：正常发布、过期跳过、已有 release 原地更新、取不到 HEAD 失败、draft 未生效失败、直链校验失败、创建失败。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$(cd "$HERE/.." && pwd)/publish-moling-release.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); echo "PASS $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL $1"; }

cat > "$WORK/gh" <<'MOCK'
#!/usr/bin/env bash
set -u
LOG="${GH_LOG:?}"; STATE="${GH_STATE_DIR:?}"
echo "gh $*" >> "$LOG"
cmd="${1:-}"; shift || true
state() { cat "$STATE/$1" 2>/dev/null || true; }
setstate() { printf '%s' "$2" > "$STATE/$1"; }
case "$cmd" in
  release)
    sub="${1:-}"; shift || true
    case "$sub" in
      view) [ -f "$STATE/exists" ] && exit 0 || exit 1 ;;
      upload) exit 0 ;;
      create)
        if [ "${FAIL_CREATE:-0}" = "1" ]; then echo "mock create failed" >&2; exit 1; fi
        touch "$STATE/exists"
        setstate tagsha "$GITHUB_SHA"; setstate draft false
        setstate name "墨灵 0.12.$GITHUB_RUN_NUMBER（${GITHUB_SHA:0:7}）"
        exit 0 ;;
    esac ;;
  api)
    path=""
    method=GET; jqexpr=""; sha=""; name=""; draftval=""
    while [ $# -gt 0 ]; do
      case "$1" in
        -X) method="$2"; shift 2 ;;
        --jq) jqexpr="$2"; shift 2 ;;
        -f|-F)
          kv="$2"; shift 2
          case "$kv" in
            sha=*) sha="${kv#sha=}" ;;
            name=*) name="${kv#name=}" ;;
            draft=*) draftval="${kv#draft=}" ;;
            target_commitish=*) setstate target "${kv#target_commitish=}" ;;
          esac ;;
        *) [ -z "$path" ] && path="$1"; shift ;;
      esac
    done
    if [ "$method" = "PATCH" ] && [[ "$path" == *"/git/refs/tags/"* ]]; then setstate tagsha "$sha"; exit 0; fi
    # 真实 GitHub 没有 PATCH /releases/tags/{tag}（会 404）；更新 release 只能用数字 id
    if [ "$method" = "PATCH" ] && [[ "$path" == *"/releases/tags/"* ]]; then echo "gh: Not Found (HTTP 404)" >&2; exit 1; fi
    if [ "$method" = "PATCH" ] && [[ "$path" =~ /releases/[0-9]+$ ]]; then
      [ -n "$name" ] && setstate name "$name"
      if [ "${STUCK_DRAFT:-0}" != "1" ] && [ -n "${draftval:-}" ]; then setstate draft "$draftval"; fi
      exit 0
    fi
    case "$path" in
      */git/ref/heads/*) echo "${REMOTE_HEAD-$GITHUB_SHA}" ;;
      */git/ref/tags/*) echo "$(state tagsha)" ;;
      */releases/tags/*)
        case "$jqexpr" in
          .draft) echo "$(state draft)" ;;
          .name) echo "$(state name)" ;;
          .id) echo "4242" ;;
          *assets*) echo "${ASSET_URL:-}" ;;
          *) printf '{}' ;;
        esac ;;
    esac
    exit 0 ;;
esac
exit 0
MOCK
cat > "$WORK/curl" <<'MOCK'
#!/usr/bin/env bash
set -u
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
if [ "${CORRUPT:-0}" = "1" ]; then printf 'not-the-apk' > "$out"; else printf 'apk-bytes' > "$out"; fi
exit 0
MOCK
cat > "$WORK/unzip" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "$WORK/gh" "$WORK/curl" "$WORK/unzip"
printf 'apk-bytes' > "$WORK/moling.apk"

run_case() {
  local scenario="$1"; shift
  local expected="$1"; shift
  local dir="$WORK/case-$scenario"
  mkdir -p "$dir"
  rm -f "$dir/log" "$dir/summary"
  env "$@" \
    GH_LOG="$dir/log" GH_STATE_DIR="$dir" GITHUB_STEP_SUMMARY="$dir/summary" \
    GITHUB_REPOSITORY="owner/repo" GITHUB_SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
    GITHUB_RUN_NUMBER="54" GITHUB_RUN_ID="39607863628" GITHUB_SERVER_URL="https://github.com" \
    GH="bash $WORK/gh" CURL="bash $WORK/curl" UNZIP="bash $WORK/unzip" SHA256="sha256sum" \
    ASSET_URL="https://github.com/owner/repo/releases/download/moling-latest/moling.apk" \
    bash "$SCRIPT" "$WORK/moling.apk" > "$dir/out" 2>&1
  local got=$?
  if [ "$got" != "$expected" ]; then bad "$scenario 退出码 $got，期望 $expected"; tail -5 "$dir/out"; else ok "$scenario 退出码"; fi
}

# 1 正常发布（首次创建）
run_case first 0
if grep -q "release create moling-latest" "$WORK/case-first/log"; then ok "first 创建 release"; else bad "first 未创建 release"; fi
if grep -qi "跳过" "$WORK/case-first/summary"; then bad "first 不应跳过"; else ok "first 未跳过"; fi

# 2 过期跳过：不动 release
run_case stale 0 REMOTE_HEAD="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
if grep -qE "release (create|upload)|-X PATCH" "$WORK/case-stale/log"; then bad "stale 仍改动了 release"; else ok "stale 未改动 release"; fi
if grep -q "跳过过期发布" "$WORK/case-stale/summary"; then ok "stale 写了跳过说明"; else bad "stale 没写跳过说明"; fi

# 3 已有 release：原地更新，不删除
mkdir -p "$WORK/case-existing"
touch "$WORK/case-existing/exists"
printf 'oldsha' > "$WORK/case-existing/tagsha"
printf 'false' > "$WORK/case-existing/draft"
printf '墨灵 0.12.0（oldsha）' > "$WORK/case-existing/name"
run_case existing 0
if grep -q "release upload moling-latest" "$WORK/case-existing/log"; then ok "existing 上传新 APK"; else bad "existing 没上传"; fi
if grep -q "release delete" "$WORK/case-existing/log"; then bad "existing 删除了 release"; else ok "existing 没删除 release"; fi
if grep -q "git/refs/tags/moling-latest -f sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "$WORK/case-existing/log"; then ok "existing tag 移到本次提交"; else bad "existing tag 未移动"; fi

# 4 取不到远端 HEAD：必须失败，不能当成过期跳过
run_case ghfail 1 REMOTE_HEAD=""
if grep -q "跳过" "$WORK/case-ghfail/summary"; then bad "ghfail 误报跳过"; else ok "ghfail 没有误报跳过"; fi

# 5 发布后仍是 draft：必须失败
mkdir -p "$WORK/case-draft"
touch "$WORK/case-draft/exists"
printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' > "$WORK/case-draft/tagsha"
printf 'true' > "$WORK/case-draft/draft"
printf '墨灵 0.12.54（aaaaaaa）' > "$WORK/case-draft/name"
run_case draft 1 STUCK_DRAFT=1

# 6 直链内容不一致：必须失败
run_case corrupt 1 CORRUPT=1

# 7 创建失败：必须失败，不能误报成功
run_case createfail 1 FAIL_CREATE=1

echo "-----"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
