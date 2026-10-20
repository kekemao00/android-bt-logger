package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.service.earbud.EarbudReaderStatus
import com.xingkeqi.btlogger.service.earbud.SoundpeatsProtocol

/**
 * 详情页“读取左右耳电量”开关的状态。
 *
 * @param supported 当前设备是否按 SOUNDPEATS 私有协议读取；不支持时不显示开关
 * @param status 读取状态；未开启或设备未连接时为 null
 */
data class DualEarUiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val status: EarbudReaderStatus? = null
)

fun dualEarUiStateOf(
    device: DeviceInfo?,
    enabledDevices: Set<String>,
    statuses: Map<String, EarbudReaderStatus>
): DualEarUiState {
    if (device == null) return DualEarUiState()
    val enabled = device.mac in enabledDevices
    return DualEarUiState(
        // 已开启的设备即使改了名字也保留开关，便于关闭
        supported = enabled || SoundpeatsProtocol.isSupported(device.name, device.uuids),
        enabled = enabled,
        status = statuses[device.mac]
    )
}
