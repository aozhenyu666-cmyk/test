#!/usr/bin/env bash
# 墨灵发布助手（第八批 R1）
#
# 把 CI 打出的 APK 更新到共享的固定下载地址（moling-latest / moling.apk），规则：
#  1. 只在调用方（workflow）已限定为工作分支时才会走到这里；
#  2. 先核对本次 SHA 仍是远端工作分支 HEAD，过期就跳过，绝不覆盖新包；
#  3. 已有 release 时原地更新（标题 / 说明 / target / tag），只有第一次才创建；
#  4. 先校验 APK，只有校验通过才动 release；失败立即退出，不删除现有包；
#  5. 发布后用 API 核对 draft=false、tag 指向本次提交、资产是固定 tag 地址，并下载比对 SHA-256。
#
# 用法：publish-moling-release.sh <apk>
# 环境：GITHUB_REPOSITORY、GITHUB_SHA、GITHUB_RUN_NUMBER、GITHUB_RUN_ID、GITHUB_SERVER_URL、GH_TOKEN
# 可覆盖：WORK_BRANCH（默认 ccr-b3c7616e-xlohkd）、TAG（默认 moling-latest）、GH、CURL、UNZIP、SHA256（测试注入 mock）
set -euo pipefail

APK="${1:-}"
REPO="${GITHUB_REPOSITORY:?缺少 GITHUB_REPOSITORY}"
SHA="${GITHUB_SHA:?缺少 GITHUB_SHA}"
RUN="${GITHUB_RUN_NUMBER:?缺少 GITHUB_RUN_NUMBER}"
RUN_ID="${GITHUB_RUN_ID:-unknown}"
SERVER="${GITHUB_SERVER_URL:-https://github.com}"
WORK_BRANCH="${WORK_BRANCH:-ccr-b3c7616e-xlohkd}"
TAG="${TAG:-moling-latest}"
GH="${GH:-gh}"
CURL="${CURL:-curl}"
UNZIP="${UNZIP:-unzip}"
SHA256="${SHA256:-sha256sum}"

if [ -z "$APK" ]; then
  echo "用法：$0 <apk>" >&2
  exit 2
fi
if [ ! -s "$APK" ]; then
  echo "校验失败：APK 不存在或为空：$APK" >&2
  exit 1
fi

summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"
  fi
  printf '%s\n' "$*"
}

# 1) 取得发布机会后，先确认这次构建仍是工作分支的最新提交。
remote_head="$($GH api "repos/$REPO/git/ref/heads/$WORK_BRANCH" --jq .object.sha 2>/dev/null || true)"
if [ -z "$remote_head" ]; then
  echo "发布失败：取不到 $WORK_BRANCH 的远端 HEAD，本次未改动 $TAG" >&2
  exit 1
fi
if [ "$remote_head" != "$SHA" ]; then
  summary "跳过过期发布：run $RUN_ID 的 $SHA 已不是 $WORK_BRANCH 的最新提交（当前 $remote_head），未改动 $TAG。"
  exit 0
fi

# 2) APK 先校验，失败立即退出；现有 release 与资产一概不碰。
$UNZIP -tq "$APK" >/dev/null
digest="$($SHA256 "$APK" | awk '{print $1}')"
if [ -z "$digest" ]; then
  echo "校验失败：算不出 APK 的 SHA-256" >&2
  exit 1
fi

version="0.12.$RUN"
short="${SHA:0:7}"
title="墨灵 $version（$short）"
notes="版本 $version
源码 $SHA
构建 $SERVER/$REPO/actions/runs/$RUN_ID
APK SHA-256 $digest

由提交 $short 自动构建，已通过单元测试。直接安装 moling.apk，可覆盖旧版本，数据保留。"

# 3) 已有 release 原地更新（不删除 release/tag）；首次才创建。
if $GH release view "$TAG" --json tagName,isDraft >/dev/null 2>&1; then
  echo "更新已有 release $TAG（不删除）"
  # GitHub 只支持按数字 id 改 release（PATCH /releases/tags/{tag} 是 404）
  release_id="$($GH api "repos/$REPO/releases/tags/$TAG" --jq .id)"
  if [ -z "$release_id" ]; then
    echo "发布失败：取不到 release $TAG 的 id，本次未改动" >&2
    exit 1
  fi
  $GH api -X PATCH "repos/$REPO/releases/$release_id" \
    -f target_commitish="$SHA" -f name="$title" -f body="$notes" \
    -F draft=false -F prerelease=true >/dev/null
  $GH api -X PATCH "repos/$REPO/git/refs/tags/$TAG" \
    -f sha="$SHA" -F force=true >/dev/null
  $GH release upload "$TAG" "$APK#moling.apk" --clobber
else
  echo "首次创建 release $TAG"
  $GH release create "$TAG" "$APK#moling.apk" \
    --target "$SHA" --prerelease --title "$title" --notes "$notes"
fi

# 4) 发布收尾核对：CLI 退出码 0 不算数，逐项查 API 和直链。
draft="$($GH api "repos/$REPO/releases/tags/$TAG" --jq .draft)"
if [ "$draft" != "false" ]; then
  echo "发布校验失败：release $TAG 仍是 draft（$draft）" >&2
  exit 1
fi
tag_sha="$($GH api "repos/$REPO/git/ref/tags/$TAG" --jq .object.sha)"
if [ "$tag_sha" != "$SHA" ]; then
  echo "发布校验失败：tag $TAG 指向 $tag_sha，不是本次 $SHA" >&2
  exit 1
fi
name="$($GH api "repos/$REPO/releases/tags/$TAG" --jq .name)"
case "$name" in
  *"$short"*) ;;
  *)
    echo "发布校验失败：release 标题「$name」里没有本次提交 $short" >&2
    exit 1
    ;;
esac
asset_url="$($GH api "repos/$REPO/releases/tags/$TAG" --jq '.assets[] | select(.name=="moling.apk") | .browser_download_url')"
expected="$SERVER/$REPO/releases/download/$TAG/moling.apk"
if [ "$asset_url" != "$expected" ]; then
  echo "发布校验失败：资产地址是 $asset_url，不是固定地址 $expected" >&2
  exit 1
fi
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
$CURL -fsSL "$expected" -o "$tmpdir/moling.apk"
got="$($SHA256 "$tmpdir/moling.apk" | awk '{print $1}')"
if [ "$got" != "$digest" ]; then
  echo "发布校验失败：直链下载的 APK SHA-256 与构建不一致（$got != $digest）" >&2
  exit 1
fi

summary "已发布 $title：tag $TAG = $SHA，APK SHA-256 $digest（直链校验通过）"
