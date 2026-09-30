#!/usr/bin/env bash
# 制品不变量 —— 发布链（build.yml）与 PR 把关（pr.yml）共用同一份，避免两份清单漂移。
#
# 用法（在仓库根运行）:
#   scripts/verify-artifacts.sh [制品目录] [基线 pin 文件]
# 默认: ext-runtime/build/libs 与 ext-runtime/android-stub/android-stub.properties
set -euo pipefail

LIB="${1:-ext-runtime/build/libs}"
PIN="${2:-ext-runtime/android-stub/android-stub.properties}"

test -f "$LIB/ext-runtime.jar" \
  || { echo "::error::缺少 $LIB/ext-runtime.jar"; exit 1; }
test -f "$LIB/ext-runtime-shared-sources.jar" \
  || { echo "::error::缺少 $LIB/ext-runtime-shared-sources.jar"; exit 1; }

# 1) fat jar 里的基线溯源必须与 pin 推导出的版本一致。
#    期望值从 pin 现算、不写死 —— 换基线只要改 android-stub.properties。
read -r API REV STRIP <<<"$(sed -n 's/^aospApiLevel=//p;s/^aospPlatformPackageRevision=//p;s/^stripRevision=//p' "$PIN" | paste -sd' ')"
test -n "$API" && test -n "$REV" && test -n "$STRIP" \
  || { echo "::error::读不全 $PIN 里的基线 pin"; exit 1; }
EXPECT="$API.$REV.$STRIP"

STUB_PROPS="${TMPDIR:-/tmp}/android-stub.properties"
echo "--- android-stub 基线（应为 $EXPECT）---"
unzip -p "$LIB/ext-runtime.jar" META-INF/android-stub.properties | tee "$STUB_PROPS"
grep -qx "version=$EXPECT" "$STUB_PROPS" \
  || { echo "::error::AOSP 基线与 pin 推导值 $EXPECT 不一致"; exit 1; }

# 2) 桩不打包 AOSP 的框架资源（P0，约 19.7 MiB）。桌面沙盒不读它们，但 res/ 那棵树有
#    8 千多个条目，一旦被某个依赖带回来，体积会静默涨回去 —— 所以在 CI 里钉住。
#    `res/` 下只允许非目录条目：依赖里有个名为 res 的包（res/Hex.class），用
#    `res/<小写目录>/` 而不是裸 `res/` 前缀，才不会把它误判成 AOSP 资源树。
echo "--- android-stub 不带框架资源 ---"
NOISE=$(unzip -l "$LIB/ext-runtime.jar" | awk '{print $4}' \
  | grep -E '^(resources\.arsc|assets/|AndroidManifest\.xml|res/[a-z0-9_-]+/)' || true)
if [ -n "$NOISE" ]; then
  # 别把整棵树打出来：这棵树有 8 千多条，全打会把 CI 日志淹掉。
  # 用数组切片而不是 `| head`：head 凑够行数就退出，上游 printf 收到 SIGPIPE，
  # 在 `set -o pipefail` 下整条流水线返回 141、脚本当场结束 —— `::error::` 那行
  # 根本来不及打印，CI 里就只剩一个没有诊断信息的失败。
  readarray -t NOISE_LINES <<<"$NOISE"
  echo "命中前 10 条（共 ${#NOISE_LINES[@]} 条）："
  printf '%s\n' "${NOISE_LINES[@]:0:10}"
  echo "::error::fat jar 里出现 AOSP 框架资源（共 ${#NOISE_LINES[@]} 条）—— 桩的 P0 排除失效"
  exit 1
fi

# 3) 共享源码树制品的内容
echo "--- 共享源码树制品内容 ---"
unzip -l "$LIB/ext-runtime-shared-sources.jar" | head -20
unzip -l "$LIB/ext-runtime-shared-sources.jar" | grep -q 'eu/kanade/tachiyomi/' \
  || { echo "::error::shared-sources 制品缺少 eu/kanade/tachiyomi/**"; exit 1; }
unzip -l "$LIB/ext-runtime-shared-sources.jar" | grep -q 'suwayomi/tachidesk/' \
  || { echo "::error::shared-sources 制品缺少 suwayomi/tachidesk/**"; exit 1; }

echo "制品不变量全部通过（基线 $EXPECT）"
