package com.xingkeqi.btlogger

import android.bluetooth.BluetoothAdapter
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.receiver.BtLoggerReceiver
import com.xingkeqi.btlogger.ui.theme.BtLoggerTheme
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


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
                        .fillMaxSize()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.background
                ) {

                    Column(modifier = Modifier.fillMaxSize()) {

                        if (bluetoothPermissionGranted) {
                            // TODO: 获取权限成功
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

//        viewModel.getAll()

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

        viewModel.insertDevice(event.device)
        viewModel.insertRecord(event.record)
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
}

@Composable
fun MainScreen(viewModel: MainViewModel) {

    Column {

        Row(
            modifier = Modifier.height(
                96.dp
            )
        ) {
            Box(modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.getAll() }) {
                Text(text = "历史设备")
            }
        }

        MainContent(viewModel = viewModel)

    }
}

@Composable
fun MainContent(viewModel: MainViewModel) {

    val devicesWithConnectionRecordsUiModel by viewModel.deviceInfoList.observeAsState(
        listOf(
            DeviceInfo(
                "22:22:22:22:22:22",
                "Air3",
                0,
                "12312",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                1

            )
        )
    )

    DeviceList(devices = devicesWithConnectionRecordsUiModel)

}


@Composable
fun ConnectsLogItem(record: RecordInfo) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row {

            Column {
                Text(text = record.mac)
                Text(text = record.name)
                Text(text = TimeUtils.millis2String(record.timestamp))
            }

            Column {


            }
        }
    }

}

@Composable
fun DeviceList(devices: List<DeviceInfo?>) {
    LazyColumn {
        items(devices) {
            DeviceItem(device = it)
        }
    }
}

@Composable
fun DeviceItem(device: DeviceInfo?) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(shape = RoundedCornerShape(20.dp))
            .padding(4.dp)
//            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
    ) {

        Column(modifier = Modifier.padding(6.dp)) {
            Text(fontSize = 18.sp, fontWeight = FontWeight.Bold, text = "设备名称：${device?.name}")
            Text(fontSize = 14.sp, text = "设备地址：${device?.mac}")
            Text(
                fontSize = 14.sp,
                text = "第一次连接时间：${TimeUtils.millis2String(device?.firstRecordTime ?: 0)}"
            )
            Text(
                fontSize = 14.sp,
                text = "最后一次记录时间：${TimeUtils.millis2String(device?.lastRecordTime ?: 0)} (${if (device?.connectStatus == 2) "连接" else "断开"})"
            )
        }
    }

}

@Composable
@Preview
fun RecordCardPreview() {

    val recordInfo = RecordInfo("mac", "name", System.currentTimeMillis(), 0, 22, 0, 99)
    MaterialTheme {
        ConnectsLogItem(recordInfo)
    }
}

@Preview
@Composable
fun DeviceCardPreview() {
//    val device = Device(0, "SOUNDPEATS Air3", "33:33:33:33:33:33", "type", "uuids")
//    val record = DeviceConnectionRecord(0, device.id, System.currentTimeMillis(), null, 0, 99, 22, true)
    val device = DeviceInfo(
        "22:22:22:22:22:22",
        "Air3",
        0,
        "12312",
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        1
    )

    val devices =
        listOf(
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device,
            device
        )

    MaterialTheme {

        DeviceList(devices = devices)
//        DeviceItem(device)
    }
}



