package com.xingkeqi.btlogger.service

import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN

const val ACTION_A2DP_CODEC_CONFIG_CHANGED =
    "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

data class CodecSnapshot(
    val phoneSupportedCodecs: String = CODEC_LIST_UNAVAILABLE,
    val negotiableCodecs: String = CODEC_LIST_UNAVAILABLE,
    val activeCodec: String = CODEC_UNKNOWN,
    val updatedAt: Long = 0L
)

/**
 * 统一把系统蓝牙编解码对象降维成可持久化文本，避免 UI 和数据库层直接依赖平台细节。
 */
object BluetoothCodecFormatter {

    fun emptySnapshot(): CodecSnapshot = CodecSnapshot()

    @RequiresApi(Build.VERSION_CODES.P)
    fun parseCodecStatus(intent: Intent): CodecSnapshot? {
        val codecStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                BluetoothCodecStatus.EXTRA_CODEC_STATUS,
                BluetoothCodecStatus::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS) as? BluetoothCodecStatus
        }

        return codecStatus?.let(::fromCodecStatus)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun fromCodecStatus(codecStatus: BluetoothCodecStatus): CodecSnapshot {
        return CodecSnapshot(
            phoneSupportedCodecs = formatCodecList(codecStatus.codecsLocalCapabilities),
            negotiableCodecs = formatCodecList(codecStatus.codecsSelectableCapabilities),
            activeCodec = formatActiveCodec(codecStatus.codecConfig)
        )
    }

    fun formatActiveCodec(codecConfig: BluetoothCodecConfig?): String {
        return codecConfig?.let { formatCodecType(it.codecType) } ?: CODEC_UNKNOWN
    }

    fun formatCodecList(codecConfigs: List<BluetoothCodecConfig>?): String {
        val codecNames = codecConfigs.orEmpty()
            .map { formatCodecType(it.codecType) }
            .distinct()
            .filter { it.isNotBlank() }

        return codecNames.takeIf { it.isNotEmpty() }?.joinToString() ?: CODEC_LIST_UNAVAILABLE
    }

    fun formatCodecType(codecType: Int): String {
        return when (codecType) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID -> CODEC_UNKNOWN
            else -> "Unknown($codecType)"
        }
    }
}
