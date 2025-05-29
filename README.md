# BtLogger - 安卓蓝牙连接日志记录与分析工具

BtLogger 是一款 Android 应用程序，旨在帮助用户详细记录和管理蓝牙设备的连接与断开事件。它能够自动捕获蓝牙状态变化，存储相关信息，并提供友好的用户界面进行查看和导出数据。对于需要监控蓝牙设备行为、分析连接稳定性或简单追踪设备使用情况的用户来说，这是一款非常实用的工具。

## 目录

* [主要功能](#主要功能)
* [屏幕截图/界面概览](#屏幕截图界面概览)
* [技术栈与依赖库](#技术栈与依赖库)
* [项目结构](#项目结构)
* [核心实现逻辑](#核心实现逻辑)
    * [蓝牙事件监听](#蓝牙事件监听)
    * [数据存储与管理](#数据存储与管理)
    * [数据展示](#数据展示)
    * [数据导出](#数据导出)
    * [音量控制](#音量控制)
    * [应用更新](#应用更新)
* [权限说明](#权限说明)
* [如何构建（可选）](#如何构建可选)
* [使用说明](#使用说明)
* [已知问题/未来改进](#已知问题未来改进)

## 主要功能

* **自动事件记录：** 自动监听并记录蓝牙音频设备 (A2DP) 的连接和断开事件。
* **详细日志信息：** 为每次事件记录以下信息：
    * 设备名称和 MAC 地址
    * 设备类型 (经典蓝牙、LE、双模)
    * 绑定状态
    * 事件发生时间戳
    * 连接/断开状态
    * 当前媒体音量百分比
    * 音乐是否正在播放
    * 手机当前电量
    * 设备 UUIDs (如果可用)
* **设备列表总览：**
    * 以列表形式展示所有记录过的蓝牙设备。
    * 显示每个设备的首次和末次记录时间。
    * 指示设备当前的连接状态（基于最新记录）。
* **设备连接历史详情：**
    * 查看特定设备的详细连接/断开历史记录。
    * 清晰展示每次连接的持续时长和每次断开的间隔时长。
    * 动态显示当前连接的已持续时间或当前断开的已持续时间。
* **数据持久化存储：**
    * 使用 Room 数据库在本地持久化存储所有设备信息和连接记录。
* **Excel 数据导出：**
    * 支持将指定蓝牙设备的详细连接历史记录导出为 Excel (.xls) 文件。
    * 导出的文件包含设备名称、MAC 地址、记录时间、绑定状态、设备类型、连接状态、手机电量、播放状态和 UUID 等信息。
* **自定义音量设置：**
    * 允许用户启用“固定音量”功能。
    * 当蓝牙设备连接时，如果该功能启用，应用会自动将媒体音量调整到用户预设的百分比。
* **应用内更新：**
    * 集成蒲公英 (Pgyer) SDK，支持应用内检查新版本、查看更新日志并下载安装新版本。
* **现代化 UI：**
    * 使用 Jetpack Compose 构建用户界面，提供流畅的交互体验。
    * 支持 Android 系统深色模式。
* **数据清理：**
    * 支持单独删除某个设备的所有记录。
    * 支持删除单条连接/断开记录。
    * 支持清空所有历史记录。

## 屏幕截图/界面概览

*(由于无法直接生成截图，此处用文字描述主要界面)*

1.  **主屏幕 (设备列表):**
    * 顶部应用栏 (TopAppBar) 显示应用名称 "BtLogger"，包含菜单按钮（显示版本信息/检查更新，或在详情页时为返回按钮）、音量预设按钮、系统蓝牙设置快捷方式和全局删除按钮。
    * 列表区域展示所有曾记录过的蓝牙设备卡片。每个卡片显示设备名称、MAC 地址、首次/末次记录时间以及当前连接状态（通过背景色区分）。点击卡片进入设备详情。长按卡片可删除该设备及其所有相关记录。
2.  **设备详情屏幕 (记录列表):**
    * 顶部应用栏变为返回按钮，并增加导出到 Excel 按钮。
    * 设备信息区域：展示当前查看设备的名称、MAC 地址、当前状态、总连接时长、总断开时长、设备类型和绑定状态。
    * 历史记录列表：按时间倒序展示该设备的每一条连接/断开记录。每条记录卡片显示事件类型（连接/断开）、播放状态、音量、手机电量、记录时间以及与上一条记录的时间间隔（即连接时长或断开间隔）。长按可删除单条记录。
3.  **对话框:**
    * **音量预设对话框：** 允许用户开启/关闭“固定音量”开关，并通过滑块设置预设的音量百分比。
    * **删除确认对话框：** 在执行删除操作前弹出，请求用户确认。
    * **版本更新对话框：** 当检测到新版本时，显示版本信息和更新日志，并提供“立即更新”和“稍后再说”的选项。
    * **下载进度对话框：** 更新包下载时显示下载进度条、下载速度和预估剩余时间。

## 技术栈与依赖库

* **核心语言：** Kotlin
* **UI 框架：** Jetpack Compose - 用于构建声明式用户界面。
* **架构模式：** MVVM (Model-View-ViewModel)
    * `ViewModel`: `androidx.lifecycle.ViewModel`
    * `LiveData`: `androidx.lifecycle.LiveData`
    * `StateFlow` (通过 `asLiveData()` 转换)
* **依赖注入：** Hilt (`dagger.hilt.android`) - 用于简化依赖管理。
* **数据库：** Room Persistence Library (`androidx.room`) - 用于本地数据持久化。
* **异步编程：**
    * Kotlin Coroutines (`kotlinx.coroutines`) - 用于处理后台任务和异步操作。
    * RxJava (可能通过蒲公英 SDK 的 `rxdownload4` 间接使用) - 用于文件下载。
* **事件通信：** GreenRobot EventBus (`org.greenrobot:eventbus`) - 用于组件间消息传递。
* **工具库：**
    * `com.blankj:utilcodex`: 强大的 Android 工具类库，用于Toast、音量控制、App信息获取、时间转换等。
    * `net.sourceforge.jexcelapi:jxl`: 用于创建和写入 Excel (.xls) 文件。
* **第三方服务 SDK：**
    * `com.pgyersdk:sdk`: 蒲公英 SDK，提供应用分发、版本更新检查、下载、APM（应用性能管理，如启动时间、卡顿监测）、异常上报等功能。
    * `zlc.season:rxdownload4`: (可能由蒲公英 SDK 依赖) 基于 RxJava 的文件下载库。
* **测试：**
    * JUnit (`org.junit`)
    * AndroidJUnitRunner (`androidx.test.ext.junit`)
    * InstrumentationRegistry (`androidx.test.platform.app`)

## 项目结构

项目遵循标准的 Android Gradle 项目结构。关键代码包和文件如下：

* `com.xingkeqi.btlogger`
    * `BtLoggerApplication.kt`: Application 类，用于 Hilt 初始化和蒲公英 SDK 初始化。
    * `MainActivity.kt`: 主 Activity，承载所有 Jetpack Compose UI。
    * `MainViewModel.kt`:主要的 ViewModel，负责处理 UI 逻辑、数据获取与更新。
    * `data/`
        * `BtLogggerEntity.kt`: 定义数据实体类 (如 `Device`, `DeviceConnectionRecord`, `DeviceInfo`, `RecordInfo`)。
        * `BtLoggerDao.kt`: Room 数据访问对象 (DAO)，定义数据库操作接口 (如 `DeviceDao`, `RecordDao`)。
        * `BtLoggerDatabase.kt`: Room 数据库的抽象类和实例获取。
        * `MessageEvent.kt`: EventBus 使用的事件类。
        * `repo/`: 数据仓库类 (如 `DeviceRepository`, `RecordRepository`) 和接口。
    * `receiver/`
        * `BtLoggerRecevier.kt`: BroadcastReceiver，用于监听系统蓝牙连接状态变化 (如 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`)。
    * `ui/`
        * `AppComponent.kt`: 通用的 Composable UI 组件。
        * `slider/SliderScreen.kt`: (似乎是一个示例或未完全集成的Slider组件屏幕)。
        * `theme/`: Jetpack Compose 的主题、颜色和排版定义。
    * `utils/`
        * `JxlUtils.kt`: 使用 JExcelApi 将数据导出到 Excel 表格的工具类。
        * `SimpleUtils.kt`: 简单的工具函数，如时间格式化。
* `res/`
    * `drawable/`: 应用图标和矢量图形。
    * `mipmap/`: 应用启动器图标。
    * `values/`: 字符串、颜色、主题等资源。
    * `xml/`:
        * `backup_rules.xml`, `data_extraction_rules.xml`: Android 自动备份和数据提取规则。
        * `excel_file_paths.xml`: `FileProvider` 路径配置，用于安全地共享导出的 Excel 文件。
* `AndroidManifest.xml`: 应用清单文件，声明组件、权限和元数据。

## 核心实现逻辑

### 蓝牙事件监听

* 通过注册一个 `BroadcastReceiver` (`BtLoggerReceiver`) 来监听 `android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED` 等蓝牙状态变化广播。
* 当接收到广播时，提取蓝牙设备信息（名称、地址、类型等）和连接状态。
* 同时获取当前手机的电量、媒体音量和音乐播放状态。
* 将这些信息封装成 `Device` 和 `DeviceConnectionRecord` 对象。
* 使用 `Handler` 延迟一秒处理，以确保获取到最新的音量信息，然后通过 `EventBus` 发送 `MessageEvent`。

### 数据存储与管理

* `MainActivity` 订阅 `MessageEvent`。
* 收到事件后，调用 `MainViewModel` 中的方法将 `Device` 和 `DeviceConnectionRecord` 插入到 Room 数据库。
* `DeviceDao` 和 `RecordDao` 提供了对设备表和记录表的增删改查操作。
* `MainViewModel` 使用 `Flow` 从 DAO 获取数据，并转换为 `LiveData` 供 UI 观察。
* 数据库定义在 `BtLoggerDatabase.kt`，包含 `devices` 和 `device_connection_records` 两张表。

### 数据展示

* UI 由 `MainActivity` 中的 Composable 函数构建。
* `MainViewModel` 持有从数据库查询到的设备列表 (`deviceInfoList`) 和当前选中设备的记录列表 (`recordInfoList`) 的 `LiveData`。
* `DeviceList` Composable 用于展示设备概览列表，每个设备是一个 `DeviceItem`。
* 点击 `DeviceItem` 后，`showRecordState` 变为 `true`，UI 切换到 `RecordCards` Composable，展示该设备的详细连接历史。
* `RecordItem` Composable 用于渲染单条连接/断开记录。
* 连接时长和断开间隔通过计算相邻两条记录的时间戳差值得到。总连接/断开时长在 `MainViewModel` 中处理 `recordInfoList` 时累加计算。

### 数据导出

* 在设备详情界面，用户可以点击导出按钮。
* 调用 `JxlUtils.saveDataToSheet()` 方法。
* 该方法使用 `jxl` 库创建一个 Excel 工作簿和工作表。
* 将当前设备的 `RecordInfo` 列表数据写入表格中，包括设备名、MAC地址、时间、连接状态等。
* 文件保存在应用的内部存储空间 (`filesDir`)。
* 导出成功后，使用 `FileProvider` 生成一个内容 URI，并通过 `Intent.ACTION_VIEW` 尝试打开该 Excel 文件。

### 音量控制

* 用户可以在主界面通过音量图标按钮打开音量预设对话框。
* 在对话框中，可以开启/关闭“固定音量”开关，并使用滑块设置一个音量百分比值，该值存储在 `MainViewModel` 的 `presetTestVolume` 变量中。
* 当 `BtLoggerReceiver` 接收到蓝牙连接成功的事件，并且 `MainViewModel` 中的 `customVolumeSwitch` 为 `true` 时，会使用 `VolumeUtils.setVolume()` 将系统媒体音量设置为预设值。

### 应用更新

* 蒲公英 SDK 在 `BtLoggerApplication` 中初始化，并启用了版本检查等功能。
* 用户可以在主界面菜单中手动触发版本检查，或者 SDK 可能会自动检查。
* `MainViewModel` 中的 `checkUpdate()` 方法调用蒲公英 SDK 的接口检查新版本。
* 如果检测到新版本，`showDialogLD` LiveData 会更新，触发 UI 显示更新提示对话框 (`UpdateDialog`)。
* 用户选择更新后，调用 `downLoadUpdate()` 方法下载更新包，并通过 `DownloadDialog` 展示下载进度。
* 下载完成后，调用 `AppUtils.installApp()` 安装 APK。

## 权限说明

应用在 `AndroidManifest.xml` 中请求了以下主要权限：

* **蓝牙相关：**
    * `android.permission.BLUETOOTH`: 允许应用连接到已配对的蓝牙设备 (旧版 API)。
    * `android.permission.BLUETOOTH_ADMIN`: 允许应用发现和配对蓝牙设备 (旧版 API)。
    * `android.permission.BLUETOOTH_CONNECT`: 允许应用连接到已配对的蓝牙设备 (Android 12+)。这是运行时权限，应用在启动时会请求。
* **后台与唤醒：**
    * `android.permission.RECEIVE_BOOT_COMPLETED`: 允许应用在设备启动完成后接收广播 (当前代码中 `BtLoggerReceiver` 并未注册此 action，但权限已声明)。
    * `android.permission.WAKE_LOCK`: 允许应用阻止设备进入休眠状态，确保后台任务（如日志记录）可以持续进行。
* **存储（用于文件导出）：**
    * `android.permission.READ_EXTERNAL_STORAGE` (android:maxSdkVersion="32"): 允许应用读取外部存储。
    * `android.permission.WRITE_EXTERNAL_STORAGE` (android:maxSdkVersion="32"): 允许应用写入外部存储。这两个权限主要用于在 Android 10 (API 29) 及以下版本导出 Excel 文件到公共目录。对于更高版本，应用使用 `FileProvider` 在应用专属目录操作，或需要通过存储访问框架 (SAF)。
* **其他：**
    * `android.permission.VIBRATE`: 允许应用使用震动功能，用于操作反馈。
    * `android.permission.WRITE_SETTINGS` (tools:ignore="ProtectedPermissions"): 允许应用修改系统设置（当前应用中似乎未使用此权限更改系统设置，但已声明）。
* **蒲公英 SDK 所需权限：**
    * `android.permission.ACCESS_NETWORK_STATE`: 允许应用访问网络状态信息。
    * `android.permission.INTERNET`: 允许应用打开网络套接字。
    * `android.permission.READ_PHONE_STATE`: 允许应用访问电话状态，通常用于获取设备标识符。
    * `android.permission.ACCESS_WIFI_STATE`: 允许应用访问 Wi-Fi 网络信息。
    * `android.permission.REQUEST_INSTALL_PACKAGES`: 允许应用请求安装应用包，用于应用内更新功能。

## 如何构建（可选）

1.  克隆仓库。
2.  使用 Android Studio Bumblebee 或更高版本打开项目。
3.  确保已安装所需的 Android SDK 版本 (根据 `build.gradle` 文件配置)。
4.  项目使用 Hilt 进行依赖注入，构建时会自动生成相关代码。
5.  蒲公英 SDK 的 API Key 已在 `AndroidManifest.xml` 中配置。
6.  构建并运行到 Android 设备或模拟器上。

## 使用说明

1.  **首次启动与权限：**
    * 首次启动应用时，如果设备是 Android 12 或更高版本，系统会提示请求“附近设备”（即 `BLUETOOTH_CONNECT`）权限。请授予此权限以确保应用能正常工作。
2.  **自动记录：**
    * 应用启动后会自动在后台监听蓝牙音频设备的连接和断开事件。无需额外操作。
3.  **查看设备列表：**
    * 主屏幕会显示所有曾经连接并被记录过的蓝牙设备。
    * 每个设备卡片会显示其名称、MAC 地址、首次和最近一次记录的时间，以及当前的连接状态（通过背景色区分：绿色表示当前已连接，默认颜色表示已断开）。
4.  **查看详细记录：**
    * 点击设备列表中的任意设备卡片，即可进入该设备的详细连接历史记录页面。
    * 此页面会显示该设备的具体信息（名称、MAC、类型、绑定状态、总连接/断开时长等）。
    * 下方列表按时间倒序列出每一次连接和断开的详细记录，包括发生时间、音量、播放状态、手机电量以及与上一条记录的时间间隔。
5.  **导出 Excel 日志：**
    * 在设备详细记录页面，点击右上角的“导出”图标 (Excel 图标)。
    * 应用会将当前设备的所有历史记录导出为一个 `.xls` 文件，并尝试使用系统默认应用打开它。
    * 文件保存在应用的内部存储中，路径通常为 `/data/data/com.xingkeqi.btlogger/files/BtLogger_设备名_时间戳.xls`。
6.  **设置固定音量：**
    * 在主屏幕，点击顶部操作栏的“音量”图标。
    * 在弹出的对话框中，打开“固定音量已启用”开关。
    * 拖动下方的滑块，设置一个期望的音量百分比 (0-100%)。
    * 设置完成后，每当蓝牙设备连接时，应用的媒体音量会自动调整到此预设值。
7.  **检查更新：**
    * 在主屏幕，点击左上角的“菜单”图标（或应用图标）。
    * 在弹出的提示中会显示当前应用版本号，同时会自动检查是否有新版本。
    * 如果发现新版本，会弹出对话框提示更新，您可以选择立即更新或稍后。
8.  **数据清理：**
    * **清空所有记录：** 在主屏幕，点击顶部操作栏的“删除”图标，在确认对话框中选择“删除”。
    * **删除单个设备的所有记录：** 在主屏幕，长按要删除的设备卡片，在确认对话框中选择“删除”。
    * **删除单条记录：** 在设备详细记录页面，长按要删除的记录卡片，在确认对话框中选择“删除”。
9.  **其他操作：**
    * 点击主屏幕顶部操作栏的“设置”图标，可以快速跳转到系统的蓝牙设置页面。

## 已知问题/未来改进

* **下载进度模拟：** `MainActivity.kt` 中更新下载进度的部分 (`LaunchedEffect(viewModel.showDialogLD.value == 2)`) 似乎是模拟的进度更新，未来应替换为真实的下载进度回调。
* **动画效果：** 代码中提到 `AnimatedVisibility` 的入场动画可能未按预期工作 (`FIXME: 入场动画不起作用为何？`)。
* **设备列表总时长显示：** `DeviceItem` Composable 中关于单个设备总连接/断开时长的显示被注释掉了 (`FIXME: 每条数据不能独立显示，待修复后放开`)，这部分功能可以进一步完善和启用。
* **Receiver 启动权限：** `AndroidManifest.xml` 中声明了 `RECEIVE_BOOT_COMPLETED` 权限，但 `BtLoggerReceiver` 的 intent filter 中并未注册对应的 action，可以考虑是否需要开机自启动功能。
* **存储权限处理：** 对于 Android 11+，`WRITE_EXTERNAL_STORAGE` 权限的行为有所改变。虽然目前导出到应用专属目录并通过 `FileProvider` 共享是推荐做法，但如果未来需要导出到公共目录，可能需要适配 Scoped Storage 或 `MANAGE_EXTERNAL_STORAGE` 权限（需谨慎使用）。
* **更丰富的统计：** 可以增加更多的统计功能，如图表展示连接频率、平均连接时长等。
* **过滤与排序：** 为设备列表和记录列表增加过滤和排序功能。
* **设置选项：** 增加更多用户可配置的选项，例如日志保留期限、通知偏好等。
