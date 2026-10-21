# BtLogger

> Android 蓝牙音频设备连接日志记录与分析工具
> A Bluetooth audio connection logger & analyzer for Android.

[![CI](https://github.com/kekemao00/android-bt-logger/actions/workflows/ci.yml/badge.svg)](https://github.com/kekemao00/android-bt-logger/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/kekemao00/android-bt-logger)](https://github.com/kekemao00/android-bt-logger/releases/latest)
![minSdk](https://img.shields.io/badge/minSdk-25-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

BtLogger 在后台持续记录蓝牙耳机、音箱、车机等 A2DP 设备的**连接 / 断开、编解码切换、耳机电量变化、媒体音量、播放状态**，并按设备聚合展示、统计与导出。耳机电量画成经过每个实测点的平滑曲线，算出掉电速率与续航，还能把多台耳机放在一起对比。它面向蓝牙音频研发、测试与客诉复盘，把“偶现断连”“续航不达标”“Codec 不对”这类难以复现的问题变成可追溯的数据。

所有数据只保存在本机，不会上传。

<p align="center">
  <img src="docs/screenshots/device-list.png" width="32%" alt="设备列表" />
  <img src="docs/screenshots/device-detail.png" width="32%" alt="设备详情：连接统计与编解码" />
  <img src="docs/screenshots/battery-curve.png" width="32%" alt="耳机电量曲线：游标读数与掉电统计" />
</p>
<p align="center">
  <img src="docs/screenshots/battery-compare.png" width="32%" alt="多台耳机电量对比" />
  <img src="docs/screenshots/device-history.png" width="32%" alt="可筛选的历史记录" />
  <img src="docs/screenshots/fixed-volume.png" width="32%" alt="固定连接音量" />
</p>
<p align="center"><sub>设备列表 · 设备详情 · 耳机电量曲线 · 多耳机对比 · 历史记录 · 固定连接音量（截图使用示例数据，详见<a href="#截图">截图</a>）</sub></p>

## 目录

- [适用场景](#适用场景)
- [下载](#下载)
- [功能](#功能)
- [使用](#使用)
- [常见问题](#常见问题)
- [权限与隐私](#权限与隐私)
- [构建](#构建) · [架构](#架构) · [CI / 发布](#ci--发布) · [截图](#截图)

## 适用场景

- **耳机 / 音箱研发与测试**：长时间挂机记录连接稳定性，统计断连次数与单次连接时长。
- **客诉复盘**：让用户安装后正常使用，导出 Excel 即可看到“什么时候断的、当时电量多少、用的什么 Codec”。
- **续航测试**：耳机电量每变化一档记一条，曲线、掉电速率与满电续航自动算好；多台耳机可按连接开始对齐对比，导出表可直接在 Excel 里画图。
- **兼容性验证**：对比不同手机上可用 / 实际使用的编解码，以及耳机电量是否能正确上报。

## 下载

在 [Releases](https://github.com/kekemao00/android-bt-logger/releases/latest) 下载最新 APK。每个 Release 附带 `SHA256SUMS.txt` 用于校验。

## 功能

| 能力 | 说明 |
|------|------|
| 后台持续记录 | 前台服务监听 A2DP 连接状态；开机、应用更新后自动恢复；服务重建或蓝牙重新打开时自动同步已连接设备；看门狗闹钟在进程被清理后重新拉起服务 |
| 后台运行设置 | 菜单「后台运行设置」：一键申请忽略电池优化、检查通知、按机型跳转自启动 / 后台耗电页（小米、vivo、OPPO、华为、荣耀、三星、魅族）并给出操作提示；可开关“前台时屏幕常亮”（默认开启） |
| 中断检测与补记 | 服务每分钟记一次心跳，被系统清理后重启时还原出中断时段并在列表页提示；中断期间断开的设备按中断开始时间补记断开，不会一直停在“已连接” |
| 事件类型 | 连接、断开、编解码切换、耳机电量变化 |
| 每条记录字段 | 时间、耳机电量、媒体音量、是否播放、手机支持 / 双方可用 / 当前使用的编解码、设备类型、蓝牙版本、绑定状态、UUID（不记录手机电量） |
| 设备列表 | 按最近活动排序、实时显示已连接时长、按名称或 MAC 搜索、长按删除 |
| 设备详情 | 连接 / 断开时长占比、连接与断开次数、最长与平均单次连接时长、编解码快照、耳机电量曲线、可按类型筛选的历史记录（电量更新显示“上一档 → 当前”与上一档持续时长；连续重复的连接/断开与短时间反复连断合并为一行，点按展开原始记录） |
| 耳机电量记录 | 多通道采集：`BATTERY_LEVEL_CHANGED` 广播、HFP `+IPHONEACCEV` / `+XEVENT`、BLE Battery Service（0x180F），系统缓存每 30 秒兜底读取一次；只在电量变化时落库，时间取电量到达的时刻；连接后 2 分钟内到达的首个电量补到连接记录上；SOUNDPEATS 耳机可在详情页按设备打开「读取左右耳电量」，通过厂商私有协议（SPP）每 10 秒读取左耳 / 右耳 / 充电仓电量，同样变化即记录 |
| 耳机电量曲线 | 本次连接 / 24 小时 / 全部；有左右耳数据时可切换左耳 / 右耳 / 系统上报，左右耳曲线叠加对比；单调分段三次贝塞尔曲线经过每个实测点且不过冲，最后一个电量用虚线延伸到断开或当前时刻；轻触吸附实测点、拖动查看、双指缩放平移、双击还原；展示起止电量、记录时长、平均掉电（%/小时）与预计剩余 / 满电续航 |
| 多耳机对比 | 最多 6 台耳机各取最近一次能算出掉电速率的连接，可按连接开始对齐或按实际时间叠加；表格对比起止电量、时长、掉电速率与满电续航 |
| 蓝牙版本 | 综合 Device Information Service、BLE 广播指纹与设备目录推断，只会用更可信的结果覆盖已有值 |
| 固定连接音量 | 每次连接蓝牙音频设备后自动把媒体音量调到设定值，App 在后台同样生效，设置重启后保留 |
| 导出 / 导入 | 导出单个设备或全部设备为 `.xls`，通过系统分享面板发送到微信、邮件、网盘等；“连接记录”含左耳 / 右耳 / 充电仓电量列；另有“电量统计”（每次连接每个电量来源一行）与“耳机电量明细”（连接后分钟数 + 各来源电量，可直接画曲线），超过行数上限自动分表；菜单「导入记录」可把导出的文件读回（重装或换机后恢复） |
| 应用内更新 | 启动时与菜单「检查更新」查询 GitHub 最新 Release，展示更新日志；下载可取消，SHA-256 校验后调起系统安装，可跳过某个版本 |

## 使用

1. 安装并打开应用，授予“附近的设备”权限（Android 12+）；Android 13+ 建议同时允许通知，以便在通知栏看到记录状态。
2. 连接任意蓝牙音频设备，记录会自动开始。之后关闭 App 也会继续记录。
3. 在列表中点击设备查看详情；右上角分享按钮导出该设备记录，溢出菜单可导出全部或清空。
4. 首页顶栏的曲线按钮（或详情页电量卡片的「多机对比」）打开电量对比，勾选要对比的耳机。
5. 首页的音量按钮可开启“固定连接音量”。

> 部分国产 ROM（vivo、小米、OPPO、华为等）会限制后台运行，请在菜单「后台运行设置」中逐项放行：忽略电池优化、允许通知、打开自启动并放开后台耗电，再在最近任务中锁定 BtLogger。

## 常见问题

**关掉 App 之后还会记录吗？**
会。记录由前台服务完成，通知栏常驻“蓝牙日志记录中”通知即表示服务在运行；开机和应用更新后也会自动恢复。若通知消失，多半是系统清理了后台：再次启动后列表页会提示中断的时间与时长，请按「后台运行设置」放开自启动与电池优化。

**为什么打开 App 时屏幕不会自动熄灭？**
续航测试时常把手机放在一旁看实时电量，所以默认开启了“前台时屏幕常亮”，只在 App 处于前台时生效，切到后台或按电源键后照常熄屏。可在「后台运行设置」中关闭。

**为什么耳机电量显示“耳机未上报”？**
耳机电量依赖耳机通过 HFP 指令、电量广播或 BLE Battery Service 主动上报，部分耳机或手机组合不提供这些信息。连接后 2 分钟内拿到的首个电量会补到连接记录上，之后的变化单独记录。

**耳机电量多久记录一次？为什么曲线是一档一档的？**
只在耳机电量变化时记录一条，电量不变就不写，时间取手机收到电量的时刻；系统缓存每 30 秒兜底读取一次，个别漏掉的广播最晚 30 秒内补上。精度取决于耳机本身的上报粒度，多数耳机按 10% 一档上报，所以曲线呈台阶状下降。掉电速率从第一次跳档量到最后一次跳档（两端都落在档位边界上，不受连接时机影响），需要满 10 分钟且电量有下降才计算。

**为什么只记录了一只耳机（通常是左耳）？能分别记录左右耳吗？**
系统蓝牙（HFP）只上报一个电量，真无线耳机报的是当前主耳，所以“系统上报”的曲线跟着主耳走。SOUNDPEATS 耳机可以在详情页打开「读取左右耳电量」，App 通过 SOUNDPEATS App 使用的私有协议分别读取左耳、右耳与充电仓电量。读取通道与 SOUNDPEATS App 互斥，测试时请先关闭 SOUNDPEATS App；详情页会显示读取状态与连不上的原因。其他品牌暂不支持。

**还记录手机电量吗？**
不再记录。新记录的手机电量为空，升级前已有的旧记录保持原样；导出表也不再有“手机电量”列。

**历史记录里的“连接抖动 ×20”“蓝牙已断开 ×3”是什么？**
系统有时会在一两秒内连续发出十几条连接/断开广播（例如蓝牙重启、耳机在仓口反复连断），或者对同一状态重复上报。每条都会照常记录，详情页只是把它们合成一行：“连接抖动”是 10 秒内连续发生、且连接与断开都有的一串事件，“×N”是连续 N 条相同的状态。点按即可展开原始记录，长按单条仍可删除；重复上报的状态不计入连接/断开次数与时长，导出的 Excel 仍逐条列出。

**编解码显示“不可用”或“未知”？**
Android 8.0 以上才提供 Codec 信息，且各厂商实现差异较大；有的系统只在开始播放后才完成协商。播放一段音频后通常会出现编解码切换记录。

**导出的 Excel 在哪里？**
导出后会直接弹出系统分享面板，可以发送到微信、邮件或保存到网盘；文件同时保存在应用私有目录，卸载应用会一并删除。

**卸载或升级会丢数据吗？**
卸载会清空数据。从 v1.6 升级会保留记录（只新增左右耳电量列）；跨越更早的版本时如果数据库结构有变更会重建数据库，建议升级前先导出全部记录，需要时用「导入记录」恢复。

## 权限与隐私

不需要账号，也没有统计或上报：记录只写入本机数据库，只有你主动导出时才会离开手机。网络权限仅用于检查更新。

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_CONNECT`（12+） | 读取设备名称、连接状态与编解码 |
| `BLUETOOTH_SCAN`（12+，`neverForLocation`） | 读取 BLE 广播辅助推断蓝牙版本，拒绝不影响基础记录 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN`（11 及以下） | 同上 |
| `POST_NOTIFICATIONS`（13+） | 显示前台服务通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 后台持续监听 |
| `RECEIVE_BOOT_COMPLETED` | 开机自动恢复记录 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请忽略电池优化，后台不被推迟、被清理后可自动重启 |
| `WAKE_LOCK` | 仅在读取左右耳电量期间保持 CPU 运行，息屏后每 10 秒的读取不中断 |
| `INTERNET` 等 | 检查与下载 GitHub Release 更新 |
| `REQUEST_INSTALL_PACKAGES` | 下载完成后调起系统安装器 |

## 构建

环境：JDK 17、Android SDK（compileSdk 36）。

```bash
./gradlew testDebugUnitTest   # 单元测试
./gradlew assembleDebug       # 构建 debug APK
./gradlew assembleRelease     # 构建 release APK（未签名）
```

APK 输出到 `app/build/outputs/apk/{debug,release}/`，文件名形如 `BtLogger-release-v1.2.1-<versionCode>(com.xingkeqi.btlogger).apk`。

## 架构

```text
BtLoggerForegroundService ──(广播: A2DP / Codec / 电量)──▶ Room (devices, device_connection_records)
        │  探测器: BluetoothBatteryProbeManager / BluetoothVersion*ProbeManager                │
        │  固定音量: applyFixedMediaVolume                                                       │ Flow
        ▼                                                                                        ▼
   常驻通知（已连接设备）                         MainViewModel (StateFlow / 一次性 UiEvent)
                                                                │
                                         BtLoggerApp ─▶ DeviceListScreen / DeviceDetailScreen / BatteryCompareScreen (无状态 Compose)
```

- **采集**全部在前台服务中完成，不依赖界面是否存活；每条链路都有防崩边界，探测失败只降级，不影响基础连接记录。
- **ViewModel** 暴露 `StateFlow`，页面组件无状态，事件通过回调上抛；Toast / 分享等一次性事件走 `Channel`。
- **统计**（累计时长、单次连接时长等）是纯函数 `buildRecordTimeline` / `computeRecordStats`；耳机电量的按连接切分、掉电速率、多机对比与单调贝塞尔插值在 `domain/battery`（纯 Kotlin），都有单元测试覆盖。
- **设置**保存在 `AppSettings`（SharedPreferences + StateFlow），前台服务与界面共享。

主要目录：

```text
app/src/main/java/com/xingkeqi/btlogger/
├── MainActivity.kt / MainViewModel.kt
├── data/       Room 实体、DAO、记录时间线统计、电量导出数据
├── domain/     耳机电量分析（纯 Kotlin，无 Android 依赖）
├── service/    前台服务、编解码解析、电量与蓝牙版本探测
├── receiver/   开机自启接收器
├── ui/         BtLoggerApp、screens/、dialogs/、components/、theme/
└── utils/      Excel 导出、音量、电量、蓝牙版本、设置
```

## CI / 发布

- **CI**（`.github/workflows/ci.yml`）：每次 push 到 `main` 和每个 PR 都会运行单元测试并构建 debug APK，产物可在 Actions 页面下载。
- **Screenshots**（`.github/workflows/screenshots.yml`）：在模拟器上重新生成 README 截图，见[截图](#截图)。
- **Release**（`.github/workflows/release.yml`）：推送 `v*.*.*` tag 即自动发布：

  ```bash
  git tag v1.3.0
  git push origin v1.3.0
  ```

  流水线会运行测试、以 tag 作为 `versionName` 构建、签名、按 Conventional Commits 分组生成更新日志，并上传 APK 与校验文件。也可以不在本地打 tag：在 Actions → Release → Run workflow 中填写新版本号（如 `v1.3.0`），流水线会基于 `main` 自动创建 tag 并发布。合并 PR 本身不会发布新版本。

  应用内更新直接读取 GitHub 最新 Release（不含草稿与预发布），按当前安装包类型选择 debug 或 release APK，并用 `SHA256SUMS.txt` 校验。发布新版本后，已安装的用户打开应用即可收到提示。

  Android 只允许同签名的包覆盖安装。debug 包固定使用仓库内的 `app/debug.keystore`（标准 debug 口令）签名，CI 与 Release 都会校验证书指纹，保证每次发布都能在应用内直接升级。v1.4.0 及更早的版本每次构建签名随机，从这些版本升级需要卸载重装一次：先在旧版菜单「导出全部记录」保存 .xls，卸载后安装新版本，再用菜单「导入记录」选择该文件恢复（按表头读取，重复导入不会产生重复记录）。

  正式签名需要在仓库 Secrets 中配置 `KEYSTORE_FILE`（keystore 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。未配置时只发布 debug APK，不会让发布失败。

  ```bash
  base64 -w0 release.jks   # 生成 KEYSTORE_FILE 的值
  ```

## 截图

README 中的截图由 [Screenshots](.github/workflows/screenshots.yml) 工作流生成：在 Android 11 模拟器上写入五台示例设备的数据，启动真实应用并截取浅色与深色界面，结果提交到 [`docs/screenshots/`](docs/screenshots)。界面有较大改动时，在 Actions → Screenshots → Run workflow 选择分支即可重新生成。

截图逻辑在 [`ReadmeScreenshotTest`](app/src/androidTest/java/com/xingkeqi/btlogger/screenshots/ReadmeScreenshotTest.kt)。它会清空设备上的记录，所以只有传入 `-e readmeScreenshots true` 时才运行，普通的 `connectedAndroidTest` 会自动跳过。

<details>
<summary>深色模式</summary>
<p align="center">
  <img src="docs/screenshots/device-list-dark.png" width="32%" alt="设备列表（深色）" />
  <img src="docs/screenshots/device-detail-dark.png" width="32%" alt="设备详情（深色）" />
  <img src="docs/screenshots/battery-curve-dark.png" width="32%" alt="耳机电量曲线（深色）" />
</p>
<p align="center">
  <img src="docs/screenshots/battery-compare-dark.png" width="32%" alt="多台耳机电量对比（深色）" />
  <img src="docs/screenshots/device-history-dark.png" width="32%" alt="历史记录（深色）" />
  <img src="docs/screenshots/fixed-volume-dark.png" width="32%" alt="固定连接音量（深色）" />
</p>
</details>

## 已知限制

- 耳机电量、完整 Codec 信息依赖手机系统与耳机实现，部分组合无法获取；耳机电量精度受耳机上报粒度限制（常见 10% 一档）。
- 蓝牙版本为多源推断结果，不保证 100% 准确。
- 数据库升级使用破坏性迁移，大版本升级前请先导出数据。

## 参与贡献

提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)（`feat:`、`fix:`、`refactor:` 等），发布说明会据此自动分组。提交 PR 前请确保 `./gradlew testDebugUnitTest assembleDebug` 通过。

## License

[Apache License 2.0](LICENSE)
