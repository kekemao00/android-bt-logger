package com.xingkeqi.btlogger.screenshots

import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xingkeqi.btlogger.MainActivity
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.utils.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "ReadmeScreenshotTest"
private const val STATE_CONNECTED = 2
private const val STATE_DISCONNECTED = 0
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val UI_TIMEOUT_MILLIS = 15_000L

/** 示例数据中的设备名，测试通过它定位列表项 */
private const val PRIMARY_DEVICE_NAME = "Buds Pro"

/**
 * 为 README 截取真实应用界面。
 *
 * Why:
 * README 截图需要与当前 UI 保持一致，手工截图容易过期且依赖真实蓝牙设备。
 * 这里向 Room 写入示例数据后启动真实的 MainActivity，用 UiAutomation 截取整屏
 * （含状态栏与弹框），由 `.github/workflows/screenshots.yml` 在模拟器上运行并提交图片。
 *
 * 该测试会清空本机数据库，因此只有显式传入 `-e readmeScreenshots true` 时才执行，
 * 普通的 connectedAndroidTest 会直接跳过。
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val outputDir = File(context.filesDir, "readme-screenshots")

    /** 由脚本传入，用于区分浅色 / 深色主题的文件名 */
    private val suffix = arguments.getString("readmeScreenshotSuffix").orEmpty()

    @Before
    fun setUp() {
        assumeTrue(arguments.getString("readmeScreenshots") == "true")
        outputDir.mkdirs()
        seedSampleData(System.currentTimeMillis())
    }

    @Test
    fun captureReadmeScreenshots() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(PRIMARY_DEVICE_NAME)
            capture("device-list")

            composeRule.onNodeWithText(PRIMARY_DEVICE_NAME).performClick()
            waitForText(context.getString(R.string.stat_connect_count))
            capture("device-detail")

            // 把整张耳机电量卡片滚进屏幕，轻触曲线展示游标读数
            composeRule.onNodeWithText(context.getString(R.string.battery_note)).performScrollTo()
            tapChart(context.getString(R.string.battery_chart_description), fraction = 0.62f)
            capture("battery-curve")

            composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("filters")
            capture("device-history")

            composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
            waitForText(PRIMARY_DEVICE_NAME)
            composeRule.onNodeWithContentDescription(context.getString(R.string.action_compare)).performClick()
            waitForText(context.getString(R.string.compare_table_caption))
            tapChart(context.getString(R.string.compare_title), fraction = 0.4f)
            capture("battery-compare")

            composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
            waitForText(PRIMARY_DEVICE_NAME)
            composeRule.onNodeWithContentDescription(context.getString(R.string.action_volume_preset)).performClick()
            capture("fixed-volume")
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 轻触曲线横向 [fraction] 处，游标吸附到最近的实测点。
     * 对比页标题与顶栏按钮的无障碍描述相同，这里排除可点击的按钮，只匹配图表画布。
     */
    private fun tapChart(description: String, fraction: Float) {
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription(description) and !hasClickAction())
            .performTouchInput { click(Offset(width * fraction, height / 2f)) }
    }

    /**
     * 等待界面与图表动画稳定后截取整屏
     */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        SystemClock.sleep(1_500L)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("[ReadmeScreenshotTest] capture -> takeScreenshot returned null: $name")
        val file = File(outputDir, "$name$suffix.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i(TAG, "[ReadmeScreenshotTest] capture -> saved ${file.absolutePath}")
    }

    /**
     * 写入五台示例设备：三台耳机用于电量曲线与多机对比（其中一台当前已连接），车机与音箱不上报耳机电量。
     * 耳机电量只在变化时落库、手机电量写未知，与前台服务的记录方式一致。
     */
    private fun seedSampleData(now: Long) = runBlocking {
        val database = BtLoggerDatabase.getDatabase(context)
        database.deviceWithRecordsDao().deleteAll()

        val buds = sampleDevice("3C:4D:BE:21:7A:10", PRIMARY_DEVICE_NAME, "5.3", BluetoothDevice.DEVICE_TYPE_DUAL, "LDAC")
        val studio = sampleDevice("88:C9:E8:4A:19:6F", "Studio ANC", "5.2", BluetoothDevice.DEVICE_TYPE_DUAL, "AAC")
        val neckband = sampleDevice("5C:FB:7C:63:0E:2A", "Sport Neckband", "5.0", BluetoothDevice.DEVICE_TYPE_CLASSIC, "AAC")
        val car = sampleDevice("00:1A:7D:DA:71:13", "车载蓝牙 CarKit", "5.0", BluetoothDevice.DEVICE_TYPE_CLASSIC, "SBC")
        val speaker = sampleDevice("F4:4E:FD:08:3B:52", "Soundbar Mini", "4.2", BluetoothDevice.DEVICE_TYPE_CLASSIC, "AAC")
        val devices = listOf(buds, studio, neckband, car, speaker)
        devices.forEach { database.deviceDao().insert(it) }

        val records = buildList {
            // Buds Pro：过去三天的三段连接，以及当前仍在进行的一段；10% 一档上报
            addSession(now, buds.mac, start = now - 3 * DAY, length = 3 * HOUR + 20 * MINUTE,
                drain = HeadsetDrain(100, 10, listOf(34, 36, 33, 35, 37)), codec = "AAC")
            addSession(now, buds.mac, start = now - 2 * DAY + 2 * HOUR, length = 1 * HOUR + 45 * MINUTE,
                drain = HeadsetDrain(90, 10, listOf(38, 35)), codec = "AAC")
            addSession(now, buds.mac, start = now - 1 * DAY - 5 * HOUR, length = 2 * HOUR + 10 * MINUTE,
                drain = HeadsetDrain(100, 10, listOf(36, 34, 37)), codec = "LDAC")
            addSession(now, buds.mac, start = now - 2 * HOUR - 17 * MINUTE, length = null,
                drain = HeadsetDrain(100, 10, listOf(33, 36, 34, 35)), codec = "LDAC")

            // Studio ANC：头戴降噪，5% 一档、掉电慢
            addSession(now, studio.mac, start = now - 3 * DAY + 4 * HOUR, length = 2 * HOUR + 30 * MINUTE,
                drain = HeadsetDrain(85, 5, listOf(40, 42, 39)), codec = "AAC")
            addSession(now, studio.mac, start = now - 1 * DAY + 1 * HOUR, length = 5 * HOUR + 40 * MINUTE,
                drain = HeadsetDrain(100, 5, listOf(41, 38, 44, 40, 43, 39, 42, 41)), codec = "AAC")

            // Sport Neckband：运动颈挂，掉电快
            addSession(now, neckband.mac, start = now - 2 * DAY - 4 * HOUR, length = 3 * HOUR + 5 * MINUTE,
                drain = HeadsetDrain(100, 10, listOf(26, 24, 27, 25, 23, 26)), codec = "AAC")

            // 车载：昨天两次通勤；音箱：三天前一次，二者都不上报耳机电量
            addSession(now, car.mac, start = now - 1 * DAY - 10 * HOUR, length = 42 * MINUTE, drain = null, codec = "SBC")
            addSession(now, car.mac, start = now - 1 * DAY + 8 * HOUR, length = 38 * MINUTE, drain = null, codec = "SBC")
            addSession(now, speaker.mac, start = now - 3 * DAY - 6 * HOUR, length = 1 * HOUR + 5 * MINUTE, drain = null, codec = "AAC")
        }
        records.forEach { database.connectionRecordDao().insert(it) }
        // 打开固定连接音量，弹框截图里展示开启后的状态
        AppSettings.get(context).apply {
            setFixedVolumeEnabled(true)
            setFixedVolumePercent(60)
        }
        Log.i(TAG, "[ReadmeScreenshotTest] seedSampleData -> devices=${devices.size} records=${records.size}")
    }

    private fun sampleDevice(mac: String, name: String, version: String, type: Int, codec: String) = Device(
        mac = mac,
        name = name,
        bondState = BluetoothDevice.BOND_BONDED,
        rssi = null,
        alias = name,
        deviceType = type,
        bluetoothVersion = version,
        uuids = "",
        latestPhoneSupportedCodecs = "AAC, LDAC, SBC",
        latestNegotiableCodecs = if (codec == "SBC") "SBC" else "AAC, LDAC, SBC",
        latestActiveCodec = codec
    )

    /**
     * 一段连接里的耳机电量变化：从 [startLevel] 起，每档分别持续 [holdMinutes] 后下降 [step]。
     * 每档时长不等并带秒级偏差，接近真实耳机的上报节奏。
     */
    private class HeadsetDrain(val startLevel: Int, val step: Int, val holdMinutes: List<Int>)

    /**
     * 追加一段连接：连接（带首个耳机电量）->（编解码切换）-> 每次电量变化一条 -> 断开（带断开时的耳机电量）。
     * length 为 null 表示仍在连接中，此时只写到当前时刻之前的变化；drain 为 null 表示设备不上报电量。
     */
    private fun MutableList<DeviceConnectionRecord>.addSession(
        now: Long,
        mac: String,
        start: Long,
        length: Long?,
        drain: HeadsetDrain?,
        codec: String
    ) {
        val end = if (length != null) start + length else now - MINUTE
        val firstLevel = drain?.startLevel ?: DEVICE_BATTERY_LEVEL_UNKNOWN

        add(record(mac, start, STATE_CONNECTED, RecordEventType.CONNECTED, firstLevel, codec, playing = false))
        if (codec == "LDAC") {
            // 首次连接时协商为 AAC，随后切换到 LDAC，展示编解码切换记录
            add(record(mac, start + 20 * SECOND, STATE_CONNECTED, RecordEventType.CODEC_CHANGED, firstLevel, codec, playing = true))
        }
        var level = firstLevel
        if (drain != null) {
            var timestamp = start
            for ((index, hold) in drain.holdMinutes.withIndex()) {
                timestamp += hold * MINUTE + (index * 17 % 50) * SECOND
                if (timestamp >= end) break
                level -= drain.step
                // 电量变化时处于播放中，让详情页头部展示“播放中”状态
                add(record(mac, timestamp, STATE_CONNECTED, RecordEventType.BATTERY_CHANGED, level, codec, playing = true))
            }
        }
        if (length != null) {
            // 与前台服务一致：断开记录带着断开前最后一次上报的耳机电量
            add(record(mac, end, STATE_DISCONNECTED, RecordEventType.DISCONNECTED, level, codec, playing = false))
        }
    }

    private fun record(
        mac: String,
        timestamp: Long,
        state: Int,
        type: String,
        headset: Int,
        codec: String,
        playing: Boolean
    ) = DeviceConnectionRecord(
        deviceMac = mac,
        timestamp = timestamp,
        connectState = state,
        batteryLevel = DEVICE_BATTERY_LEVEL_UNKNOWN,
        headsetBatteryLevel = headset,
        volume = 60,
        isPlaying = playing,
        eventType = type,
        phoneSupportedCodecs = "AAC, LDAC, SBC",
        negotiableCodecs = if (codec == "SBC") "SBC" else "AAC, LDAC, SBC",
        activeCodec = codec
    )
}
