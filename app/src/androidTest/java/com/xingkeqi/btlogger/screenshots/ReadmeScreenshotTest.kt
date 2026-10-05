package com.xingkeqi.btlogger.screenshots

import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xingkeqi.btlogger.MainActivity
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.BtLoggerDatabase
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
private const val MINUTE = 60_000L
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

            composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("filters")
            capture("device-history")

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
     * 写入三台示例设备：一台当前已连接、带多次连接与电量变化的耳机，两台已断开的设备
     */
    private fun seedSampleData(now: Long) = runBlocking {
        val database = BtLoggerDatabase.getDatabase(context)
        database.deviceWithRecordsDao().deleteAll()

        val buds = sampleDevice("3C:4D:BE:21:7A:10", PRIMARY_DEVICE_NAME, "5.3", BluetoothDevice.DEVICE_TYPE_DUAL, "LDAC")
        val car = sampleDevice("00:1A:7D:DA:71:13", "车载蓝牙 CarKit", "5.0", BluetoothDevice.DEVICE_TYPE_CLASSIC, "SBC")
        val speaker = sampleDevice("F4:4E:FD:08:3B:52", "Soundbar Mini", "4.2", BluetoothDevice.DEVICE_TYPE_CLASSIC, "AAC")
        listOf(buds, car, speaker).forEach { database.deviceDao().insert(it) }

        val records = buildList {
            // Buds Pro：过去三天的三段连接，以及当前仍在进行的一段
            addSession(buds.mac, start = now - 3 * DAY, length = 3 * HOUR + 20 * MINUTE, phone = 96, headset = 100, codec = "AAC")
            addSession(buds.mac, start = now - 2 * DAY + 2 * HOUR, length = 1 * HOUR + 45 * MINUTE, phone = 88, headset = 90, codec = "AAC")
            addSession(buds.mac, start = now - 1 * DAY - 5 * HOUR, length = 2 * HOUR + 10 * MINUTE, phone = 79, headset = 100, codec = "LDAC")
            addSession(buds.mac, start = now - 2 * HOUR - 17 * MINUTE, length = null, phone = 91, headset = 100, codec = "LDAC")

            // 车载：昨天两次通勤
            addSession(car.mac, start = now - 1 * DAY - 10 * HOUR, length = 42 * MINUTE, phone = 67, headset = null, codec = "SBC")
            addSession(car.mac, start = now - 1 * DAY + 8 * HOUR, length = 38 * MINUTE, phone = 54, headset = null, codec = "SBC")

            // 音箱：三天前一次
            addSession(speaker.mac, start = now - 3 * DAY - 6 * HOUR, length = 1 * HOUR + 5 * MINUTE, phone = 72, headset = 60, codec = "AAC")
        }
        records.forEach { database.connectionRecordDao().insert(it) }
        // 打开固定连接音量，弹框截图里展示开启后的状态
        AppSettings.get(context).apply {
            setFixedVolumeEnabled(true)
            setFixedVolumePercent(60)
        }
        Log.i(TAG, "[ReadmeScreenshotTest] seedSampleData -> devices=3 records=${records.size}")
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
     * 追加一段连接：连接 -> 若干电量采样（-> 编解码切换）-> 断开；length 为 null 表示仍在连接中
     */
    private fun MutableList<DeviceConnectionRecord>.addSession(
        mac: String,
        start: Long,
        length: Long?,
        phone: Int,
        headset: Int?,
        codec: String
    ) {
        val end = start + (length ?: (System.currentTimeMillis() - start - 5 * MINUTE))
        val samples = 4
        val step = (end - start) / (samples + 1)
        val headsetLevel = { index: Int -> headset?.let { (it - index * 10).coerceAtLeast(10) } ?: -1 }

        add(record(mac, start, STATE_CONNECTED, RecordEventType.CONNECTED, phone, headsetLevel(0), codec, playing = false))
        if (codec == "LDAC") {
            // 首次连接时协商为 AAC，随后切换到 LDAC，展示编解码切换记录
            add(record(mac, start + step / 2, STATE_CONNECTED, RecordEventType.CODEC_CHANGED, phone, headsetLevel(0), codec, playing = true))
        }
        for (index in 1..samples) {
            add(
                record(
                    mac,
                    start + step * index,
                    STATE_CONNECTED,
                    RecordEventType.BATTERY_CHANGED,
                    phone - index * 3,
                    headsetLevel(index),
                    codec,
                    // 最后一次采样处于播放中，让详情页头部展示“播放中”状态
                    playing = index % 2 == 0
                )
            )
        }
        if (length != null) {
            add(record(mac, end, STATE_DISCONNECTED, RecordEventType.DISCONNECTED, phone - samples * 3 - 1, -1, codec, playing = false))
        }
    }

    private fun record(
        mac: String,
        timestamp: Long,
        state: Int,
        type: String,
        phone: Int,
        headset: Int,
        codec: String,
        playing: Boolean
    ) = DeviceConnectionRecord(
        deviceMac = mac,
        timestamp = timestamp,
        connectState = state,
        batteryLevel = phone,
        headsetBatteryLevel = headset,
        volume = 60,
        isPlaying = playing,
        eventType = type,
        phoneSupportedCodecs = "AAC, LDAC, SBC",
        negotiableCodecs = if (codec == "SBC") "SBC" else "AAC, LDAC, SBC",
        activeCodec = codec
    )
}
