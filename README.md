# BtLogger

> Android 蓝牙连接日志记录、诊断与分析工具  
> A Bluetooth connection logging, diagnostics and analysis tool for Android.

![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Language](https://img.shields.io/badge/language-Kotlin-blue)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![Database](https://img.shields.io/badge/database-Room-orange)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 项目简介

**BtLogger** 是一款面向 Android 蓝牙设备测试、研发和问题排查场景的工具型应用。

它可以在后台持续监听蓝牙音频设备的连接状态、断开事件、Codec 切换、手机电量、耳机电量、媒体音量、播放状态等关键数据，并将完整链路日志持久化到本地数据库中。

相比普通的蓝牙连接记录工具，BtLogger 更关注 **蓝牙连接行为分析** 与 **稳定性诊断**，适用于蓝牙耳机、音箱、车载音频、智能硬件等设备的测试验证场景。

当前版本：**v1.2.0**  
数据库版本：**schema v7**

---

## 为什么做这个项目

在蓝牙音频设备研发与测试过程中，经常会遇到以下问题：

- 用户反馈「蓝牙偶现断连」，但没有完整日志支撑分析
- 耳机电量显示异常，无法判断数据来源是否可靠
- 不同手机厂商、不同 Android 版本下蓝牙行为不一致
- Codec 切换、A2DP/HFP 状态变化难以复现和追踪
- 测试人员需要手动记录连接时间、断开时间、音量、电量等信息
- 研发人员需要导出结构化数据进行问题复盘

BtLogger 的目标是将这些零散、不可见、难复现的蓝牙行为转化为**可记录、可追踪、可分析、可导出**的数据。

---

## 核心能力

### 1. 蓝牙连接全链路记录

BtLogger 会自动监听并记录蓝牙音频设备的关键状态变化：

- A2DP 连接 / 断开事件
- A2DP Codec 配置变化
- 手机电量变化
- 蓝牙耳机电量变化
- 媒体音量变化
- 当前蓝牙设备是否为音频输出
- 当前是否处于媒体播放状态

支持的事件类型包括：

```kotlin
CONNECTED
DISCONNECTED
CODEC_CHANGED
BATTERY_CHANGED
```

每条日志记录包含：

- 设备名称
- MAC 地址
- Alias
- Bond State
- Device Type
- UUIDs
- 蓝牙版本推断结果
- 当前 Codec 信息
- 手机电量
- 耳机电量
- 媒体音量百分比
- 原始音量 level / maxLevel
- 当前音频输出状态
- 播放状态
- 时间戳

---

### 2. 前台服务持续监听

核心监听逻辑运行在前台服务中：

```kotlin
BtLoggerForegroundService
```

即使应用 UI 被系统回收，也可以持续记录蓝牙设备状态。

主要能力：

- 前台服务保活
- 常驻通知栏展示运行状态
- 蓝牙状态广播监听
- 音频设备变化监听
- 音量变化监听
- 播放状态采集
- 数据异步写入 Room 数据库

适合长时间测试、压力测试、稳定性验证等场景。

---

### 3. 多通道耳机电量采集

Android 蓝牙耳机电量获取在不同设备、厂商、系统版本上存在明显差异。BtLogger 针对该问题实现了多通道采集策略：

#### 支持的数据来源

- 反射读取系统缓存：

```kotlin
BluetoothDevice.getBatteryLevel()
```

- 监听隐藏广播：

```kotlin
android.bluetooth.device.action.BATTERY_LEVEL_CHANGED
```

- 解析 HFP Vendor Specific Event：

```text
+XEVENT
+IPHONEACCEV
```

- BLE GATT Battery Service：

```text
Service:        0x180F
Characteristic: 0x2A19
```

- LE / 双模设备 GATT 订阅
- 多源数据合并、去重与回填

#### 设计目标

- 尽可能兼容不同品牌耳机
- 优先使用可信度更高的数据源
- 自动将后续采集到的电量回填到当前连接记录
- 避免重复记录和无效数据污染

---

### 4. 蓝牙版本探测

BtLogger 提供蓝牙版本推断能力，用于辅助判断设备能力和兼容性问题。

#### 探测方式

- 基于 `BluetoothClass` 做设备类型判断
- 基于 `BluetoothAdapter` 能力判断手机侧特性
- 读取 GATT Device Information Service
- 分析厂商在特征值中明文上报的版本信息
- BLE 广播扫描辅助识别
- 多源结果合并

核心工具类：

```kotlin
BluetoothVersionUtils.mergeBluetoothVersion()
```

该模块不会简单覆盖已有结果，而是选择信息量更高、更可信的版本描述。

---

### 5. Codec 信息记录

BtLogger 支持记录 A2DP Codec 相关信息：

- 当前激活 Codec
- 当前可协商 Codec
- 手机支持的 Codec 列表
- Codec 配置变化事件

可以用于分析：

- SBC / AAC / aptX / LDAC 等 Codec 是否正确协商
- 不同手机上的 Codec 表现差异
- 连接后 Codec 是否发生切换
- 音频质量问题是否与 Codec 有关

---

### 6. 设备列表总览

主界面以设备为维度聚合展示所有历史记录。

展示信息包括：

- 设备名称
- MAC 地址
- 当前连接状态
- 首次记录时间
- 最近记录时间
- 最新电量
- 最新 Codec
- 最新蓝牙版本

交互能力：

- 点击进入设备详情
- 长按删除设备及其全部记录
- 当前连接设备高亮展示
- 支持空状态和加载状态处理

---

### 7. 设备详情分析

设备详情页展示某个蓝牙设备的完整历史数据。

包含：

- 设备基础信息
- 连接 / 断开时间线
- 总连接时长
- 总断开时长
- 最新 Codec 快照
- 最新电量
- UUID 信息
- 音量信息
- 播放状态
- 事件间隔
- 单条记录删除

适合用于复盘单个设备的长时间连接表现。

---

### 8. 电量趋势图表

基于 Vico 2.0 绘制耳机电量趋势图。

可以直观看到：

- 电量下降趋势
- 电量上报是否异常
- 是否存在跳变
- 是否存在长时间不上报
- 设备断连前后电量变化

适合测试人员与研发人员快速判断设备电量上报稳定性。

---

### 9. Excel 数据导出

BtLogger 支持将指定设备的完整历史记录导出为 `.xls` 文件。

实现方式：

- 使用 `jxl` 生成 Excel 文件
- 使用 `FileProvider` 提供安全文件分享
- 支持通过系统分享面板发送到微信、邮箱、网盘等应用

导出数据适合用于：

- Bug 复现记录
- 测试报告归档
- 研发分析
- 客诉问题排查
- 设备稳定性对比

---

### 10. 固定音量功能

BtLogger 提供固定媒体音量能力。

开启后，当蓝牙设备连接时，应用会自动将媒体音量调整到用户设定的百分比。

内部实现：

- `AudioManager`
- `ContentObserver`
- `AudioDeviceCallback`
- 媒体音量百分比换算
- 连接状态联动

适用于蓝牙设备测试过程中保持统一音量条件，减少人为变量干扰。

---

### 11. 应用内更新

集成蒲公英 SDK，用于测试分发场景下的应用更新。

相关能力：

- 版本检查
- 下载更新包
- 安装 APK
- 测试版本快速分发

使用依赖：

```kotlin
com.pgyer:analytics
RxDownload4
```

---

## 技术栈

### 核心语言与平台

- Kotlin
- Android SDK
- Jetpack Compose
- Material 3

### Android Jetpack

- Compose UI
- ViewModel
- Lifecycle
- Room
- Navigation
- Coroutines
- Flow
- DataStore / SharedPreferences

### 蓝牙与系统能力

- BluetoothAdapter
- BluetoothDevice
- BluetoothA2dp
- BluetoothHeadset
- BluetoothGatt
- BroadcastReceiver
- Foreground Service
- AudioManager
- AudioDeviceCallback
- ContentObserver

### 数据与图表

- Room Database
- Vico Chart
- jxl Excel Export
- FileProvider

### 工程化

- Gradle
- Kotlin DSL
- GitHub Actions
- CI 构建
- APK Artifact 上传

---

## 架构设计

BtLogger 采用较清晰的分层结构，将蓝牙监听、数据采集、数据持久化、UI 展示进行解耦。

```text
UI Layer
├── Compose Screens
├── ViewModel
└── StateFlow / UI State

Domain / Logic Layer
├── Bluetooth event processing
├── Battery aggregation
├── Codec parsing
├── Version detection
└── Audio state collection

Data Layer
├── Room Database
├── DAO
├── Entity
└── Repository

System Layer
├── Foreground Service
├── BroadcastReceiver
├── Bluetooth APIs
├── AudioManager
└── FileProvider
```

### 设计特点

- 前台服务负责持续监听
- Repository 统一封装数据访问
- ViewModel 管理 UI 状态
- Compose 负责声明式界面渲染
- Room 保证本地数据持久化
- Flow / Coroutines 处理异步数据流
- 多源数据合并逻辑独立封装，避免 UI 层复杂化

---

## 项目结构

```text
android-bt-logger/
├── app/
│   ├── src/main/
│   │   ├── java/...
│   │   │   ├── service/
│   │   │   │   └── BtLoggerForegroundService.kt
│   │   │   ├── receiver/
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   ├── dao/
│   │   │   │   ├── entity/
│   │   │   │   └── repository/
│   │   │   ├── bluetooth/
│   │   │   ├── audio/
│   │   │   ├── ui/
│   │   │   │   ├── screen/
│   │   │   │   ├── component/
│   │   │   │   └── theme/
│   │   │   └── util/
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/
│   └── workflows/
├── gradle/
├── build.gradle.kts
└── README.md
```

> 具体目录可能会随着版本演进略有调整，请以实际代码为准。

---

## 权限说明

由于应用需要监听蓝牙状态、读取蓝牙设备信息、执行 BLE 扫描、导出文件以及运行前台服务，因此需要以下权限。

### Android 12 及以上

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Android 12 以下

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### 前台服务

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

### 文件导出

通过 `FileProvider` 进行文件共享，不直接暴露私有路径。

---

## 构建方式

### 环境要求

- Android Studio
- JDK 17+
- Gradle 8+
- Android Gradle Plugin 8+
- Android SDK 35 或项目配置对应版本

### 克隆项目

```bash
git clone https://github.com/kekemao00/android-bt-logger.git
cd android-bt-logger
```

### 构建 Debug 包

```bash
./gradlew assembleDebug
```

### 构建 Release 包

```bash
./gradlew assembleRelease
```

### 安装到设备

```bash
./gradlew installDebug
```

---

## CI/CD

项目支持 GitHub Actions 自动构建。

典型流程：

```text
Push / Pull Request
        ↓
Checkout Code
        ↓
Setup JDK
        ↓
Setup Gradle
        ↓
Build Debug APK
        ↓
Upload Artifact
```

适合用于：

- 自动验证代码可构建
- 快速生成测试包
- 多端协作分发
- 后续扩展自动发布流程

---

## 使用说明

### 1. 安装应用

通过 Android Studio 安装，或使用 GitHub Actions 构建产物安装。

### 2. 授予权限

首次启动时，请根据系统提示授予：

- 蓝牙权限
- 附近设备权限
- 通知权限
- 定位权限，部分 Android 版本 BLE 扫描需要
- 前台服务相关权限

### 3. 启动日志服务

进入应用后启动蓝牙日志监听服务。

启动后通知栏会显示常驻通知，表示 BtLogger 正在后台监听蓝牙事件。

### 4. 连接蓝牙设备

连接蓝牙耳机、音箱或其他 A2DP 音频设备。

应用会自动记录连接事件，并采集相关信息。

### 5. 查看设备记录

在设备列表中选择目标设备，进入详情页查看：

- 连接历史
- 断开历史
- Codec 变化
- 电量变化
- 音量变化
- 播放状态
- 时间间隔

### 6. 导出数据

进入设备详情后，可将该设备历史数据导出为 Excel 文件，并通过系统分享面板发送给其他应用。

---

## 典型应用场景

### 蓝牙耳机研发测试

用于记录耳机在不同手机、不同系统版本下的连接稳定性、电量上报、Codec 协商结果。

### 客诉问题复盘

当用户反馈断连、无声、电量异常时，可通过历史日志辅助定位问题。

### 兼容性验证

对比不同厂商手机上的蓝牙行为差异，例如：

- 小米
- OPPO
- vivo
- 华为
- Samsung
- Pixel

### 长时间稳定性测试

通过前台服务持续记录设备数小时甚至数天的连接状态，用于发现偶现问题。

### Codec 行为分析

用于观察设备连接后是否正确协商 AAC、aptX、LDAC 等音频编码格式。

### 电量上报验证

用于测试耳机电量广播、HFP 电量事件、BLE Battery Service 是否稳定。

---

## 技术亮点

### 多源蓝牙电量采集

Android 对蓝牙耳机电量并没有完全统一的公开标准实现。BtLogger 通过系统缓存、隐藏广播、HFP 厂商事件和 BLE GATT 多种方式进行采集，并进行合并去重。

### 蓝牙版本推断

通过公开 API、BLE GATT、广播扫描等多种信息源推断蓝牙版本，提升设备信息完整度。

### 前台服务稳定监听

将核心监听逻辑从 Activity 解耦到 Foreground Service，提升长时间运行可靠性。

### Compose + Material 3

使用 Jetpack Compose 构建现代化 UI，降低传统 View 系统的状态同步复杂度。

### Room 本地持久化

所有连接事件和设备信息均持久化到本地数据库，支持历史回溯、统计和导出。

### Excel 结构化导出

将测试过程中的蓝牙行为转化为结构化数据，便于研发、测试、产品和售后协作分析。

---

## 数据库设计概览

### Device Entity

用于存储设备维度信息：

```text
device_mac
device_name
alias
bond_state
device_type
uuids
bluetooth_version
first_seen_time
last_seen_time
latest_codec
latest_battery
```

### Connection Log Entity

用于存储事件维度信息：

```text
id
device_mac
event_type
timestamp
codec_info
phone_battery
headset_battery
media_volume
is_bluetooth_output
is_playing
raw_extra
```

---

## 隐私说明

BtLogger 仅在本地记录蓝牙设备相关信息，不会默认上传任何用户数据。

本地记录的数据包括：

- 蓝牙设备名称
- 蓝牙 MAC 地址
- 连接 / 断开时间
- Codec 信息
- 电量信息
- 音量状态
- 播放状态

这些数据仅用于本地分析和用户主动导出。

如果后续引入云端同步或远程日志能力，应明确增加用户授权与隐私说明。

---

## 已知限制

由于 Android 系统和厂商实现差异，部分能力可能受到限制：

- 某些设备不公开耳机电量
- 某些系统屏蔽隐藏广播
- 某些手机不允许读取完整 Codec 信息
- Android 12+ 对蓝牙权限要求更严格
- BLE 扫描在后台可能受到系统限制
- 部分厂商 ROM 会限制前台服务长时间运行
- 蓝牙版本只能基于多源信息推断，不能保证 100% 准确

---

## Roadmap

后续计划：

- [ ] 支持更多图表维度，如连接时长、断连次数、Codec 分布
- [ ] 支持按日期范围筛选日志
- [ ] 支持 CSV 导出
- [ ] 支持 JSON 导出
- [ ] 支持批量导出全部设备记录
- [ ] 增加日志搜索能力
- [ ] 增加设备标签功能
- [ ] 增加断连原因分析
- [ ] 增加蓝牙 RSSI 记录
- [ ] 增加测试报告自动生成
- [ ] 支持更多蓝牙 Profile 状态记录
- [ ] 优化数据库迁移策略，替换 destructive migration
- [ ] 增加单元测试与 UI 自动化测试
- [ ] 增加更多厂商耳机电量协议适配

---

## 适合人群

该项目适合以下开发者或团队参考：

- Android 蓝牙开发工程师
- 智能硬件 App 开发者
- 蓝牙耳机测试工程师
- 音频设备研发团队
- Android 系统兼容性测试人员
- 想学习 Android 蓝牙开发的开发者
- 想了解 Compose + Room + 前台服务实践的开发者

---

## 相关知识点

通过该项目可以学习和实践：

- Android 蓝牙连接状态监听
- A2DP Profile 使用
- HFP Vendor Specific Event 解析
- BLE GATT Service 读取与订阅
- Android 12+ 蓝牙权限适配
- 前台服务保活
- Room 数据库设计
- Jetpack Compose 状态管理
- Flow / Coroutines 异步处理
- Android 音频输出设备判断
- Excel 文件导出
- FileProvider 文件共享
- GitHub Actions 自动构建

---

## 贡献

欢迎提交 Issue 和 Pull Request。

如果你在不同设备上发现：

- 电量无法读取
- Codec 信息异常
- 连接状态记录不准确
- 某些厂商设备存在特殊广播格式
- Android 某版本权限行为异常

欢迎反馈设备型号、系统版本、蓝牙设备型号和复现步骤。

---

## License

MIT License

---

## 作者

**毛科 / kekemao00**

- GitHub: [https://github.com/kekemao00](https://github.com/kekemao00)
- Blog: [https://kekemao.me](https://kekemao.me)

---

## 项目定位

BtLogger 不只是一个简单的蓝牙日志工具，它更像是一个面向 Android 蓝牙设备研发和测试场景的轻量级可观测性工具。

它尝试将蓝牙连接、Codec、电量、音量、播放状态等分散在系统各处的信息统一采集、结构化存储并可视化展示，从而帮助开发者更高效地定位蓝牙连接稳定性和兼容性问题。
