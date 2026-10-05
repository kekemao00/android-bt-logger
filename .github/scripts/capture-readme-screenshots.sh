#!/usr/bin/env bash
# 在已启动的模拟器上运行 ReadmeScreenshotTest，并把截图拉取到 docs/screenshots/。
# 由 .github/workflows/screenshots.yml 调用；前提是 debug APK 与 androidTest APK 已构建。
set -euo pipefail

APP_ID="com.xingkeqi.btlogger"
RUNNER="${APP_ID}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="${APP_ID}.screenshots.ReadmeScreenshotTest"
OUT_DIR="docs/screenshots"
SHOTS=(device-list device-detail device-history fixed-volume)

adb install -r -g app/build/outputs/apk/debug/*.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/*.apk

# 断网，避免蒲公英更新弹框出现在截图里
adb shell svc wifi disable || true
adb shell svc data disable || true

# 系统界面演示模式：固定时间、满电量与满信号，隐藏通知图标
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0930
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

run_pass() {
  local suffix="$1"
  local output
  # adb shell 会把参数拼成一条命令，空字符串会被吞掉导致 am 参数错位，所以只在非空时传后缀
  local suffix_args=()
  if [[ -n "$suffix" ]]; then
    suffix_args=(-e readmeScreenshotSuffix "$suffix")
  fi
  output=$(adb shell am instrument -w \
    -e readmeScreenshots true \
    "${suffix_args[@]}" \
    -e class "$TEST_CLASS" \
    "$RUNNER")
  echo "$output"
  if ! grep -q "^OK (1 test)" <<<"$output"; then
    echo "::error::ReadmeScreenshotTest failed (suffix='$suffix')"
    exit 1
  fi
}

adb shell cmd uimode night no
run_pass ""
adb shell cmd uimode night yes
run_pass "-dark"
adb shell cmd uimode night no

mkdir -p "$OUT_DIR"
for shot in "${SHOTS[@]}"; do
  for suffix in "" "-dark"; do
    name="${shot}${suffix}.png"
    adb exec-out run-as "$APP_ID" cat "files/readme-screenshots/${name}" > "${OUT_DIR}/${name}"
    # 拉取失败时 run-as 会输出错误文本而不是 PNG
    if ! file "${OUT_DIR}/${name}" | grep -q "PNG image"; then
      echo "::error::Failed to pull ${name}"
      head -c 300 "${OUT_DIR}/${name}"
      exit 1
    fi
  done
done

ls -la "$OUT_DIR"
