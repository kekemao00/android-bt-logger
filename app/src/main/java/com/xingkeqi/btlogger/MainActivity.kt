package com.xingkeqi.btlogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.receiver.getCurrVolume
import com.xingkeqi.btlogger.service.BtLoggerForegroundService
import com.xingkeqi.btlogger.ui.theme.BtLoggerTheme
import com.xingkeqi.btlogger.ui.theme.ConnectedGreen
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenDark
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenLight
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.components.BadgeType
import com.xingkeqi.btlogger.ui.components.BatteryIndicator
import com.xingkeqi.btlogger.ui.components.BatteryTrendChart
import com.xingkeqi.btlogger.ui.components.ConnectionStatusIndicator
import com.xingkeqi.btlogger.ui.components.DurationProgressBar
import com.xingkeqi.btlogger.ui.components.StatCard
import com.xingkeqi.btlogger.ui.components.StatItem
import com.xingkeqi.btlogger.ui.components.StatusBadge
import com.xingkeqi.btlogger.utils.getDurationString
import com.xingkeqi.btlogger.utils.saveDataToSheet
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Calendar
import kotlin.text.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val tag: String = this.javaClass.simpleName


    private val viewModel: MainViewModel by viewModels()

    private var bluetoothPermissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("@@@", "onCreate: MainActivity")
        super.onCreate(savedInstanceState)

        setContent {
            BtLoggerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    Column(modifier = Modifier.fillMaxSize()) {

                        if (bluetoothPermissionGranted) {
                            //  获取权限成功
                            MainScreen(viewModel)

                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                // Android 12+ 需要 BLUETOOTH_CONNECT 权限
                                RequestBluetoothPermission { bluetoothPermissionGranted = it }
                            } else {
                                // Android 11 及以下版本，蓝牙权限在 Manifest 中声明即可
                                // 直接显示主界面
                                bluetoothPermissionGranted = true
                                MainScreen(viewModel)
                            }
                        }
                    }

                }
            }
        }

        // 避免 Activity 重建时重复注册
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        // 启动前台服务，保持应用存活并监听蓝牙连接状态
        BtLoggerForegroundService.start(this)
        Log.i(tag, "onCreate: 已启动前台服务")

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: MessageEvent) {
        Log.i(
            tag,
            "onMessageEvent: msg=${event.message},device=${event.device},record=${event.record}"
        )
        if (event.message == "ADD_RECORD") {
            // 数据已由前台服务保存，此处仅处理音量调整
            if (viewModel.customVolumeSwitch.value == true && event.record.connectState == BluetoothA2dp.STATE_CONNECTED) {
                VolumeUtils.setVolume(
                    AudioManager.STREAM_MUSIC,
                    ((viewModel.presetTestVolume.toFloat() / 100) * VolumeUtils.getMaxVolume(
                        AudioManager.STREAM_MUSIC
                    )).toInt(),
                    0x01
                )
                Log.i(tag, "onMessageEvent: 已调整音量至 ${viewModel.presetTestVolume}%")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Composable
    fun RequestBluetoothPermission(
        onPermissionResult: (Boolean) -> Unit
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                onPermissionResult(isGranted)
            }
        )

        LaunchedEffect(Unit) {
            launcher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不停止前台服务，让它继续在后台运行
        // 只有用户主动退出应用时才停止服务
        EventBus.getDefault().unregister(this)
    }

    private var lastBackPressTime: Long = 0
    override fun onBackPressed() {
        if (showRecordState) {
            showRecordState = false
            return
        }
        if (lastBackPressTime + 2000 < System.currentTimeMillis()) {
            ToastUtils.showLong("再按一次返回键退出应用程序")
            lastBackPressTime = System.currentTimeMillis();
            return
        }
        super.onBackPressed()
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val openDialog = remember { mutableStateOf(false) }

    Column {

        var showDialog by remember { mutableStateOf(false) }

        var stepSliderPosition by remember { mutableStateOf(viewModel.presetTestVolume.toFloat()) }
        Box(contentAlignment = Alignment.Center) {
            if (showDialog) {
                AlertDialog(title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = viewModel.customVolumeSwitch.observeAsState().value == true,
                            onCheckedChange = {
                                viewModel.customVolumeSwitch.value = it
                            })
                        Text(
                            text = "固定音量已${if (viewModel.customVolumeSwitch.value == true) "启用" else "禁用"}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp),
                        )

                    }
                }, onDismissRequest = { showDialog = false }, text = {
                    Row(
                        modifier = Modifier
                            .wrapContentHeight()
                            .padding(vertical = 16.dp)
                    ) {


                        Column(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .align(Alignment.CenterVertically)
                        ) {

                            Text(
                                text = "连接时固定音量为 " + stepSliderPosition.toInt()
                                    .toString() + "%"
                            )

                            Row {
                                Icon(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .align(Alignment.CenterVertically),
                                    painter = painterResource(id = R.drawable.icon_volue),
                                    contentDescription = "音量图标"
                                )
                                Slider(
                                    value = stepSliderPosition,
                                    onValueChange = { stepSliderPosition = it },
                                    valueRange = 0f..100f,
                                    onValueChangeFinished = {
                                        // launch some business logic update with the state you hold
                                        // viewModel.updateSelectedSliderValue(sliderPosition)
                                        Log.d("@@@", "MainScreen: $stepSliderPosition")
                                        viewModel.presetTestVolume = stepSliderPosition.toInt()

                                    },
                                    steps = 9,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.secondary,
                                        activeTrackColor = MaterialTheme.colorScheme.secondary
                                    )
                                )
                            }

                        }

                    }

                }, confirmButton = { /*showDialog = false*/ })
            }
        }


        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "BtLogger") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showRecordState) {
                                showRecordState = false
                            } else {
                                ToastUtils.showShort("当前版本： v${AppUtils.getAppVersionName()}")
                                viewModel.checkUpdate()
                            }
                        }) {
                            Icon(
                                if (showRecordState) Icons.Filled.ArrowBack else Icons.Filled.Menu,
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "菜单"
                            )
                        }
                    },
                    actions = {

                        if (showRecordState) {
                            // Add your actions here
                            IconButton(onClick = {
                                if (viewModel.recordInfoList.value == null) {
                                    ToastUtils.showLong("导出失败：没有可以导出的数据")
                                } else {
                                    saveDataToSheet(
                                        viewModel.currDevice.value ?: DeviceInfo(),
                                        viewModel.recordInfoList.value!!
                                    ) {
                                        ToastUtils.showLong("已保存：${it.absoluteFile} : ${it.length()} bytes")
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${AppUtils.getAppPackageName()}.fileProvider",
                                            it
                                        )
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.setDataAndType(uri, "text/plain")
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(intent)

                                    }
                                }

                            }) {
                                Icon(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(2.dp),
                                    painter = painterResource(id = R.drawable.icon_ex_excel),
                                    tint = MaterialTheme.colorScheme.primary,
                                    contentDescription = "导出"
                                )
                            }
                        }
                        IconButton(onClick = {
                            ToastUtils.showShort("设置连接时的音量百分比")
                            showDialog = true
                        }) {

                            Icon(
                                modifier = Modifier
                                    .size(24.dp),
                                painter = painterResource(id = R.drawable.icon_volue),
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "音量预设置"
                            )
                        }

                        IconButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bluetooth_settings),
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "蓝牙设置"
                            )
                        }
                        // Add your actions here
                        IconButton(onClick = {
                            openDialog.value = true
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                tint = MaterialTheme.colorScheme.error,
                                contentDescription = "删除"
                            )
                        }

                        if (openDialog.value) {
                            ShowDialog(
                                openDialog,
                                title = if (showRecordState) "清空 ${viewModel.currDevice.value?.name} 的历史记录？" else "清空所有历史记录？",
                                content = if (showRecordState) "记录删除后，将无法恢复。是否继续？" else "历史记录清空后，将无法恢复。是否继续？",
                                onConfirm = {
                                    openDialog.value = false
                                    if (showRecordState) {
                                        viewModel.deleteRecordByMac(
                                            viewModel.currDevice.value?.mac ?: ""
                                        )
                                    } else {
                                        viewModel.cleanAll()
                                    }
                                }, onCancel = {
                                    openDialog.value = false
                                }
                            )
                        }

                    }
                )
            },

            content = {
                MainContent(
                    modifier = Modifier.padding(it),
                    viewModel = viewModel
                )
            }
        )
//        var progress by remember { mutableStateOf(0.0f) }

        // FIXME: 需要优化
        var speedStr by remember { mutableStateOf("0.0MB/s") }
        var timeLeftStr by remember { mutableStateOf("0分0秒") }
        LaunchedEffect(viewModel.showDialogLD.value == 2) {
            if (viewModel.showDialogLD.value == 2) {
                for (i in 0 until 100) {
                    viewModel.downloadProgressLD.value = (i + 1) / 100f
                    delay(500)
                    val downloadSize: Long = 100 * 1024 * 1024 // 下载文件的大小，这里假设为100MB
                    val downloadedSize: Long = ((viewModel.downloadProgressLD.value
                        ?: (0f * (viewModel.versionModelLD.value?.buildFileSize?.toInt()
                            ?: 0))).toLong())// 已下载的文件大小
                    val elapsedTime: Long = (i + 1) * 1000L // 已经过去的时间，这里假设为1分钟
                    val speed: Long =
                        if (elapsedTime > 0) downloadedSize / elapsedTime else 0 // 下载速度，单位为Byte/ms
                    val timeLeft: Long =
                        if (speed > 0) (downloadSize - downloadedSize) / speed else 0 // 剩余时间，单位为ms
                    val timeLeftInSecond = (timeLeft / 1000).toInt()
                    val minutes = timeLeftInSecond / 60
                    val seconds = timeLeftInSecond % 60
                    speedStr = String.format("%.1fMB/s", speed * 1000f / (1024 * 1024)) // 转换为MB/s
                    timeLeftStr = String.format("%d分%d秒", minutes, seconds)
                }
            }
        }


        when (viewModel.showDialogLD.observeAsState().value) {

            1 -> UpdateDialog(
                onDismiss = { viewModel.showDialogLD.value = 0 },
                viewModel = viewModel
            )

            2 -> DownloadDialog(
                progress = viewModel.downloadProgressLD.value ?: 0F,
                speed = speedStr,
                timeLeft = timeLeftStr
            ) {
            }
        }

    }
}

@Composable
private fun ShowDialog(
    openDialog: MutableState<Boolean>,
    title: String = "温馨提示",
    content: String = "确认删除吗？",
    onConfirm: () -> Unit,
    onCancel: () -> Unit,

    ) {
    AlertDialog(
        onDismissRequest = { openDialog.value = false },
        title = { Text(title) },
        text = { Text(content) },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error),
                onClick = {
                    onConfirm()

                }
            ) {
                Icon(
                    painter = rememberVectorPainter(image = Icons.Filled.Delete),
                    contentDescription = "删除"
                )
                Text("删除")
            }
        },
        dismissButton = {
            Button(
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary),
                onClick = {
                    onCancel()
                }
            ) {
                Text("取消")
            }
        }
    )
}

/**
 * 显示单个设备的详细记录
 */
private var showRecordState by mutableStateOf(false)

@Composable
fun MainContent(modifier: Modifier = Modifier, viewModel: MainViewModel) {
    if (!showRecordState) {
        val deviceUiModel = viewModel.deviceInfoList.observeAsState().value.orEmpty()
        DeviceList(modifier, devices = deviceUiModel, viewModel)
    } else {
        val recordUiModel = viewModel.recordInfoList.observeAsState().value.orEmpty()
        RecordCards(modifier, records = recordUiModel, viewModel)
    }

}

@Composable
fun RecordCards(
    modifier: Modifier = Modifier,
    records: List<RecordInfo?>,
    viewModel: MainViewModel
) {
    LazyColumn(modifier = modifier) {
        if (records.isEmpty()) return@LazyColumn

        val latestRecord = records[0]
        val isConnected = latestRecord?.connectState == 2

        // 详情页头部
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                if (isConnected) {
                                    if (isSystemInDarkTheme()) ConnectedGreenDark else ConnectedGreenLight
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(Dimens.spacingLg)
            ) {
                // 第一行：状态指示器 + 设备名称
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConnectionStatusIndicator(
                        isConnected = isConnected,
                        modifier = Modifier.size(Dimens.iconSizeXs)
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacingSm))
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        text = latestRecord?.name ?: "未知设备"
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.spacingXs))

                // MAC 地址
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelMedium,
                    text = latestRecord?.mac ?: ""
                )

                Spacer(modifier = Modifier.height(Dimens.spacingMd))

                // 连接/断开时长进度条
                val recordList = viewModel.recordInfoList.observeAsState().value.orEmpty()
                if (recordList.isNotEmpty()) {
                    DurationProgressBar(
                        connectionTime = recordList[0].totalConnectionTime ?: 0,
                        disconnectionTime = recordList[0].totalDisConnectionTime ?: 0
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.spacingMd))

                // 状态指标行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingLg)
                ) {
                    // 电量
                    if ((latestRecord?.batteryLevel ?: 0) > 0) {
                        BatteryIndicator(level = latestRecord?.batteryLevel ?: 0)
                    }

                    // 音量
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_volue),
                            contentDescription = "音量",
                            modifier = Modifier.size(Dimens.iconSizeSm),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${latestRecord?.volume ?: 0}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // 播放状态徽章
                    StatusBadge(
                        text = if (latestRecord?.isPlaying == 1) "记录时播放" else "记录时静音",
                        type = if (latestRecord?.isPlaying == 1) BadgeType.Playing else BadgeType.Paused
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.spacingMd))

                // 统计卡片
                StatCard(
                    items = listOf(
                        StatItem("连接次数", "${records.count { it?.connectState == 2 }}次"),
                        StatItem("设备类型", when (latestRecord?.deviceType) {
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典"
                            BluetoothDevice.DEVICE_TYPE_LE -> "低功耗"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                            else -> "其他"
                        }),
                        StatItem("绑定状态", when (latestRecord?.bondState) {
                            BluetoothDevice.BOND_BONDED -> "已配对"
                            BluetoothDevice.BOND_BONDING -> "配对中"
                            else -> "未配对"
                        })
                    )
                )

                Spacer(modifier = Modifier.height(Dimens.spacingMd))

                // 电量趋势图表
                BatteryTrendChart(records = records.filterNotNull())
            }

            Divider()

            Text(
                modifier = Modifier.padding(start = Dimens.spacingLg, top = Dimens.spacingLg, bottom = Dimens.spacingXs),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelMedium,
                text = "历史记录 (${records.size}条)"
            )
        }

        items(records) {
            AnimatedVisibility(
                visible = showRecordState,
                enter = fadeIn(animationSpec = tween(durationMillis = 300, delayMillis = 150)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300))
            ) {
                RecordItem(
                    record = it,
                    viewModel = viewModel
                )
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordItem(modifier: Modifier = Modifier, record: RecordInfo?, viewModel: MainViewModel) {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val showDialogDelItem = remember { mutableStateOf(false) }
    val isConnected = record?.connectState == 2

    ElevatedCard(modifier = modifier
        .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingXs)
        .fillMaxWidth()
        .wrapContentHeight()
        .clip(shape = RoundedCornerShape(Dimens.cardCornerRadius))
        .combinedClickable(onLongClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect =
                    VibrationEffect.createOneShot(1, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            showDialogDelItem.value = true
        }) {},
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                if (isSystemInDarkTheme()) ConnectedGreenDark else ConnectedGreenLight
            } else MaterialTheme.colorScheme.surface,
        )

    ) {
        if (showDialogDelItem.value) {
            ShowDialog(
                openDialog = showDialogDelItem,
                title = "删除此条记录？",
                content = "确定要删除 ${record?.name} 在 ${TimeUtils.millis2String(record?.timestamp ?: 0)} 的【${if (isConnected) "连接" else "断开"}】记录吗? 删除的数据将无法恢复，是否继续？",
                onConfirm = {
                    showDialogDelItem.value = false
                    viewModel.deleteRecordById(record?.id ?: -1)
                }) {
                showDialogDelItem.value = false
            }
        }

        Column(
            modifier = Modifier
                .padding(Dimens.cardPadding)
                .fillMaxWidth()
        ) {
            // 第一行：状态 + 时间戳（右对齐）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusIndicator(isConnected = isConnected)
                    Spacer(modifier = Modifier.width(Dimens.spacingSm))
                    Text(
                        style = MaterialTheme.typography.titleMedium,
                        text = if (isConnected) "蓝牙已连接" else "蓝牙已断开"
                    )
                }
                Text(
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    text = TimeUtils.millis2String(record?.timestamp ?: 0, "MM-dd HH:mm:ss")
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacingSm))

            // 第二行：指标横向排列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)
            ) {
                // 电量
                if ((record?.batteryLevel ?: 0) > 0) {
                    BatteryIndicator(level = record?.batteryLevel ?: 0)
                }

                // 音量
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.icon_volue),
                        contentDescription = "音量",
                        modifier = Modifier.size(Dimens.iconSizeSm),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${record?.volume ?: 0}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // 播放状态
                StatusBadge(
                    text = if (record?.isPlaying == 1) "播放" else "静音",
                    type = if (record?.isPlaying == 1) BadgeType.Playing else BadgeType.Paused
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacingSm))

            // 第三行：时长信息
            val durationText = if (record?.timestamp == record?.lastRecordTime) {
                "首条记录"
            } else {
                val duration = getDurationString((record?.timestamp ?: 0) - (record?.lastRecordTime ?: 0))
                if (isConnected) "断开间隔：$duration" else "本次连接：$duration"
            }

            Text(
                style = MaterialTheme.typography.labelMedium,
                color = if (isConnected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                fontWeight = if (!isConnected) FontWeight.Medium else FontWeight.Normal,
                text = durationText
            )
        }
    }
}

@Composable
fun DeviceList(
    modifier: Modifier = Modifier,
    devices: List<DeviceInfo?>,
    viewModel: MainViewModel
) {
    LazyColumn(modifier = modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        items(devices) {
            // FIXME: 入场动画不起作用为何？
            AnimatedVisibility(
                visible = !showRecordState, // 这里假设所有的 item 都是可见的
                enter = fadeIn(animationSpec = tween(durationMillis = 300, delayMillis = 150)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300))
            ) {
                DeviceItem(device = it, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceItem(device: DeviceInfo?, viewModel: MainViewModel) {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val showDialogDelItem = remember { mutableStateOf(false) }
    val isConnected = device?.connectState == 2

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(shape = RoundedCornerShape(Dimens.cardCornerRadius))
            .padding(Dimens.spacingXs)
            .combinedClickable(
                onLongClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect =
                            VibrationEffect.createOneShot(2, VibrationEffect.DEFAULT_AMPLITUDE)
                        vibrator.vibrate(effect)
                    }
                    showDialogDelItem.value = true
                }) {
                viewModel.getDeviceByMac(device?.mac ?: "")
                viewModel.currDevice.value = device
                showRecordState = true

            },
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                if (isSystemInDarkTheme()) ConnectedGreenDark else ConnectedGreenLight
            } else MaterialTheme.colorScheme.surface,

            )

    ) {

        Box(
            modifier = Modifier
                .padding(Dimens.cardPadding)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.align(alignment = Alignment.CenterStart),
            ) {
                // 第一行：状态指示器 + 设备名称
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ConnectionStatusIndicator(isConnected = isConnected)
                    Spacer(modifier = Modifier.width(Dimens.spacingSm))
                    Text(
                        style = MaterialTheme.typography.titleMedium,
                        text = device?.name ?: "未知设备",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.spacingXs))

                // 第二行：MAC 地址
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    text = device?.mac ?: ""
                )

                Spacer(modifier = Modifier.height(Dimens.spacingSm))

                // 第三行：时间信息
                Row {
                    Text(
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelMedium,
                        text = "首次 ${TimeUtils.millis2String(device?.firstRecordTime ?: 0, "MM-dd HH:mm")}"
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacingMd))
                    Text(
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelMedium,
                        text = "最近 ${TimeUtils.millis2String(device?.lastRecordTime ?: 0, "MM-dd HH:mm")}"
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.spacingSm))

                // 第四行：状态徽章
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
                ) {
                    StatusBadge(
                        text = if (isConnected) "已连接" else "已断开",
                        type = if (isConnected) BadgeType.Connected else BadgeType.Disconnected
                    )

                    if (isConnected) {
                        val deviceInfos = viewModel.deviceInfoList.observeAsState().value.orEmpty()
                        if (deviceInfos.isNotEmpty() && device?.connectState == viewModel.currDevice.value?.connectState) {
                            Clock(
                                deviceId = deviceInfos[0].mac,
                                fontSize = 11.sp,
                                color = ConnectedGreen,
                                lastRecordTime = device?.lastRecordTime ?: 0,
                                connectState = deviceInfos[0].connectState,
                                viewModel = viewModel
                            )
                        }
                    }
                }

            }

            Icon(
                modifier = Modifier.align(alignment = Alignment.CenterEnd),
                painter = rememberVectorPainter(image = Icons.Filled.KeyboardArrowRight),
                contentDescription = "箭头向右"
            )

            if (showDialogDelItem.value) {
                ShowDialog(
                    showDialogDelItem,
                    title = "删除 ${device?.name} ？",
                    content = "${device?.name} [${device?.mac}] \n\n确认要删除这个设备相关的所有记录吗？删除的数据将无法恢复。是否继续？",
                    onConfirm = {
                        showDialogDelItem.value = false
                        viewModel.deleteDevice(device?.mac ?: "")
                    }, onCancel = {
                        showDialogDelItem.value = false
                    }
                )
            }
        }
    }

}

@Composable
fun UpdateDialog(onDismiss: () -> Unit, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "版本更新") },
        text = {
            Column {

                Text(
                    text = "发现新版本可用，请更新以获得更好的使用体验"
                )
                Text(text = "版本名称：v${viewModel.versionModelLD.value?.buildVersion}")
                Text(text = "更新详情：\n${viewModel.versionModelLD.value?.buildUpdateDescription}")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // TODO: 处理更新逻辑
                    onDismiss()
                    viewModel.downLoadUpdate(viewModel.versionModelLD.value)
                }
            ) {
                Text(text = "立即更新")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = "稍后再说")
            }
        }
    )
}

@Composable
fun DownloadDialog(
    progress: Float,
    speed: String,
    timeLeft: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "下载中") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(progress * 100).toInt()}% 下载完成",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "下载速度: $speed")
                Text(text = "剩余时间: $timeLeft")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = "取消下载")
            }
        },
        confirmButton = {}

    )
}


@OptIn(ObsoleteCoroutinesApi::class)
@Composable
fun Clock(
    deviceId: String,
    lastRecordTime: Long,
    connectState: Int,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 16.sp,
    viewModel: MainViewModel
) {

    val timeDifference = remember(viewModel.currDevice.value?.connectState) {
        mutableStateOf(Calendar.getInstance().timeInMillis - lastRecordTime)
    }

    LaunchedEffect(deviceId, connectState) {
        val ticker = ticker(delayMillis = 10)
        for (event in ticker) {
            timeDifference.value = Calendar.getInstance().timeInMillis - lastRecordTime
        }
    }
    Row {

        Text(
            color = color,
            fontSize = fontSize,
            text = "（" + getDurationString(timeDifference.value) + "）",
        )
    }
}
