package com.xingkeqi.btlogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blankj.utilcode.constant.TimeConstants
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.ToastUtils
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.callback.CheckoutCallBack
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack
import com.pgyer.pgyersdk.model.CheckSoftModel
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.receiver.BtLoggerReceiver
import com.xingkeqi.btlogger.ui.theme.BtLoggerTheme
import com.xingkeqi.btlogger.utils.getDurationString
import com.xingkeqi.btlogger.utils.longLongLongTriple
import com.xingkeqi.btlogger.utils.saveDataToSheet
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.lang.Exception
import kotlin.text.*


class MainActivity : ComponentActivity() {

    private val tag: String = this.javaClass.simpleName


    private val viewModel: MainViewModel by viewModels()

    private val btLoggerReceiver by lazy { BtLoggerReceiver() }

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
                                RequestBluetoothPermission { bluetoothPermissionGranted = it }
                            } else {
                                Log.w(
                                    tag,
                                    "onCreate:  Build.VERSION.SDK_INT = ${Build.VERSION.SDK_INT}"
                                )
                                MainScreen(viewModel)
                            }
                        }
                    }

                }
            }
        }

        EventBus.getDefault().register(this)

        val receiver = btLoggerReceiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(receiver, filter);

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: MessageEvent) {
        Log.i(
            tag,
            "onMessageEvent: msg=${event.message},device=${event.device},record=${event.record}"
        )
        if (event.message == "ADD_RECORD") {
            viewModel.insertDevice(event.device)
            viewModel.insertRecord(event.record)
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
        EventBus.getDefault().unregister(this)
        unregisterReceiver(btLoggerReceiver)
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
                        IconButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Icon(
                                Icons.Filled.Settings,
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "设置"
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
                    val speed: Long = if (elapsedTime > 0) downloadedSize / elapsedTime else 0 // 下载速度，单位为Byte/ms
                    val timeLeft: Long = if (speed > 0) (downloadSize - downloadedSize) / speed else 0 // 剩余时间，单位为ms
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
        val deviceUiModel by viewModel.deviceInfoList.observeAsState(listOf(DeviceInfo()))
        DeviceList(modifier, devices = deviceUiModel, viewModel)
    } else {
        val recordUiModel by viewModel.recordInfoList.observeAsState(listOf(RecordInfo()))
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
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                if (records[0]?.connectState == 2) {
                                    if (isSystemInDarkTheme()) Color(0xFF33691E) else Color(
                                        0xFFDCE5CD
                                    )
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(start = 22.dp, 12.dp, 12.dp, 12.dp)
            ) {

                Text(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, text = "设备名称：${records[0]?.name}"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    text = "设备别名：${records[0]?.alias}"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    text = "蓝牙地址：${records[0]?.mac}"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    text = "设备类型：${
                        when (records[0]?.deviceType) {
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙设备"
                            BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙设备"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "支持经典蓝牙和低功耗蓝牙的设备"
                            else -> "其他"
                        }
                    }"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    text = "绑定状态：${
                        when (records[0]?.bondState) {
                            BluetoothDevice.BOND_NONE -> "未配对"
                            BluetoothDevice.BOND_BONDING -> "正在配对"
                            BluetoothDevice.BOND_BONDED -> "已配对"
                            BluetoothDevice.ERROR -> "出错"
                            else -> "其他"
                        }
                    }"
                )

                Text(
                    text = "当前状态：${if (records[0]?.connectState == 2) "已连接" else "未连接"}"
                )

                Text(
                    text = "连接总时长：${getDurationString(records[0]?.totalConnectionTime ?: 0)}"
                )

                Text(
                    color = MaterialTheme.colorScheme.outline,
                    text = "断开总时长：${getDurationString(records[0]?.totalDisConnectionTime ?: 0)}"
                )

            }
            Text(
                modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outline,
                text = "历史记录"
            )
        }
        items(records) {
            // FIXME: 入场动画不起作用为何？
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


    ElevatedCard(modifier = modifier
        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        .fillMaxWidth()
        .wrapContentHeight()
        .clip(shape = RoundedCornerShape(20.dp))
        .padding(top = 4.dp)
        .combinedClickable(onLongClick = {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect =
                    VibrationEffect.createOneShot(1, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            showDialogDelItem.value = true
        }) {},
        colors = CardDefaults.cardColors(
            containerColor = if (record?.connectState == 2) {
                if (isSystemInDarkTheme()) Color(0xFF33691E) else Color(0xFFDCE5CD)
            } else MaterialTheme.colorScheme.surface,
        )

    ) {
        if (showDialogDelItem.value) {
            ShowDialog(
                openDialog = showDialogDelItem,
                title = "删除此条记录？",
                content = "确定要删除 ${record?.name} 在 ${TimeUtils.millis2String(record?.timestamp ?: 0)} 的【${if (record?.connectState == 2) "连接" else "断开"}】这条记录吗? 删除的数据将无法恢复，是否继续？",
                onConfirm = {
                    showDialogDelItem.value = false
                    viewModel.deleteRecordById(record?.id ?: -1)
                }) {
                showDialogDelItem.value = false

            }
        }

        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Text(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                text = "状态：${if (record?.connectState == 2) "连接" else "断开"}"
            )
            Text(
                color = MaterialTheme.colorScheme.outline,
                fontSize = 14.sp, text = "正在播放：${if (record?.isPlaying == 1) "是" else "否"}"
            )
            Text(
                color = MaterialTheme.colorScheme.outline,
                fontSize = 14.sp,
                text = "${if (record?.connectState == 2) "耳" else "手"}机音量：${record?.volume}%"
            )
            Text(
                color = MaterialTheme.colorScheme.outline,
                fontSize = 14.sp, text = "电池剩余：${record?.batteryLevel}"
            )

            Text(
                color = MaterialTheme.colorScheme.outline,
                fontSize = 14.sp,
                text = "记录时间：${TimeUtils.millis2String(record?.timestamp ?: 0)}"
            )
            Row {

                val annotatedString = buildAnnotatedString {
                    if (record?.timestamp == record?.lastRecordTime) {
                        withStyle(style = SpanStyle(fontSize = 14.sp)) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("这是第一条记录")
                            }
                        }
                        return@buildAnnotatedString
                    }
                    append(if (record?.connectState == 2) "上次断开：" else "连接计时：")

                    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                        append(
                            getDurationString(
                                (record?.timestamp ?: 0) - (record?.lastRecordTime ?: 0)
                            )
                        )
                    }

                    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                        append(if (record?.connectState == 2) "之前" else "")

                    }
                }

                Text(
                    color = if (record?.connectState == 2) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onBackground,
                    fontSize = if (record?.connectState == 2) 14.sp else 14.sp,
                    text = annotatedString
                )

            }
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

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(shape = RoundedCornerShape(20.dp))
            .padding(4.dp)
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
            containerColor = if (device?.connectState == 2) {
                if (isSystemInDarkTheme()) Color(0xFF33691E) else Color(0xFFDCE5CD)
            } else MaterialTheme.colorScheme.surface,

            )

    ) {

        Box(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.align(alignment = Alignment.CenterStart),
            ) {

                Row {
                    Text(fontSize = 18.sp, fontWeight = FontWeight.Bold, text = "设备名称：")
                    Text(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        text = "${device?.name}"
                    )
                }

                Text(
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 13.sp,
                    text = "Mac 地址：${device?.mac}"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                    text = "首次记录：${TimeUtils.millis2String(device?.firstRecordTime ?: 0)}"
                )
                Text(
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                    text = "最近记录：${TimeUtils.millis2String(device?.lastRecordTime ?: 0)}  "
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

// FIXME: 每条数据不能独立显示，待修复后放开
//                    Text(
//                        fontSize = 14.sp,
//                        text = "总连接时长：${getDurationString(viewModel.pairTimeDuration.value?.first ?: 0)}"
//                    )
//                    Text(
//                        color = MaterialTheme.colorScheme.outline,
//                        fontSize = 14.sp,
//                        text = "总断开时长：${getDurationString(viewModel.pairTimeDuration.value?.second ?: 0)}"
//                    )

                    Text(
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 14.sp,
                        text = "当前状态：${if (device?.connectState == 2) "已连接" else "已断开"}"
                    )
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




