package com.xingkeqi.btlogger.service

import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import java.lang.reflect.InvocationTargetException

const val ACTION_A2DP_CODEC_CONFIG_CHANGED =
    "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

private const val BLUETOOTH_CODEC_LOG_TAG = "BtCodecFormatter"

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

    /**
     * 统一归一化编解码文本，避免系统广播顺序抖动导致的误判。
     */
    fun normalizeSnapshot(snapshot: CodecSnapshot): CodecSnapshot {
        return snapshot.copy(
            phoneSupportedCodecs = snapshot.phoneSupportedCodecs.normalizeCodecListValue(),
            negotiableCodecs = snapshot.negotiableCodecs.normalizeCodecListValue(),
            activeCodec = snapshot.activeCodec.normalizeCodecValue()
        )
    }

    fun hasCodecSnapshotChanged(previous: CodecSnapshot?, current: CodecSnapshot): Boolean {
        val normalizedCurrent = current.asComparableSnapshot()
        val normalizedPrevious = previous?.asComparableSnapshot() ?: return normalizedCurrent != emptySnapshot().asComparableSnapshot()
        return normalizedPrevious != normalizedCurrent
    }

    /**
     * 仅在已知旧编解码且当前使用格式真实切换时，才落一条历史记录。
     */
    fun shouldPersistCodecHistory(previous: CodecSnapshot?, current: CodecSnapshot): Boolean {
        val normalizedPrevious = previous?.asComparableSnapshot() ?: return false
        val normalizedCurrent = current.asComparableSnapshot()
        return normalizedPrevious.activeCodec != CODEC_UNKNOWN &&
            normalizedCurrent.activeCodec != CODEC_UNKNOWN &&
            normalizedPrevious.activeCodec != normalizedCurrent.activeCodec
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun parseCodecStatus(intent: Intent): CodecSnapshot? {
        val codecStatus = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    BluetoothCodecStatus.EXTRA_CODEC_STATUS,
                    BluetoothCodecStatus::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS) as? BluetoothCodecStatus
            }
        } catch (e: RuntimeException) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] parseCodecStatus -> codec status extra parse failed",
                e
            )
            null
        } catch (e: LinkageError) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] parseCodecStatus -> codec status extra API unavailable",
                e
            )
            null
        }

        return codecStatus?.let(::fromCodecStatus)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun fromCodecStatus(codecStatus: BluetoothCodecStatus): CodecSnapshot {
        return normalizeSnapshot(
            CodecSnapshot(
                phoneSupportedCodecs = formatCodecList(
                    readCodecConfigList(
                        codecStatus = codecStatus,
                        methodName = "getCodecsLocalCapabilities"
                    )
                ),
                negotiableCodecs = formatCodecList(
                    readCodecConfigList(
                        codecStatus = codecStatus,
                        methodName = "getCodecsSelectableCapabilities"
                    )
                ),
                activeCodec = formatActiveCodec(readCodecConfig(codecStatus))
            )
        )
    }

    fun formatActiveCodec(codecConfig: BluetoothCodecConfig?): String {
        return codecConfig?.let { formatCodecType(it.codecType) } ?: CODEC_UNKNOWN
    }

    fun formatCodecList(codecConfigs: List<BluetoothCodecConfig>?): String {
        val codecNames = codecConfigs.orEmpty()
            .sortedBy { it.codecType }
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

    private fun CodecSnapshot.asComparableSnapshot(): CodecSnapshot {
        return normalizeSnapshot(copy(updatedAt = 0L))
    }

    private fun String.normalizeCodecListValue(): String {
        val codecNames = split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != CODEC_LIST_UNAVAILABLE }
            .distinct()
            .sortedBy { it.lowercase() }
        return codecNames.takeIf { it.isNotEmpty() }?.joinToString() ?: CODEC_LIST_UNAVAILABLE
    }

    private fun String.normalizeCodecValue(): String {
        return trim().takeIf { it.isNotBlank() } ?: CODEC_UNKNOWN
    }

    /**
     * Why:
     * 部分厂商 ROM 会发送 codec 广播，但运行时框架缺少新 SDK 中的读取方法。
     * 这里反射读取可选字段，缺失时只降级附加编解码信息，不能影响连接记录。
     */
    private fun readCodecConfig(codecStatus: BluetoothCodecStatus): BluetoothCodecConfig? {
        return invokeCodecStatusMethod(
            codecStatus = codecStatus,
            methodName = "getCodecConfig"
        ) as? BluetoothCodecConfig
    }

    private fun readCodecConfigList(
        codecStatus: BluetoothCodecStatus,
        methodName: String
    ): List<BluetoothCodecConfig>? {
        val rawValue = invokeCodecStatusMethod(
            codecStatus = codecStatus,
            methodName = methodName
        )
        return (rawValue as? List<*>)
            ?.filterIsInstance<BluetoothCodecConfig>()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun invokeCodecStatusMethod(
        codecStatus: BluetoothCodecStatus,
        methodName: String
    ): Any? {
        return try {
            val method = codecStatus.javaClass.methods.firstOrNull { candidate ->
                candidate.name == methodName && candidate.parameterTypes.isEmpty()
            } ?: run {
                Log.w(
                    BLUETOOTH_CODEC_LOG_TAG,
                    "[BluetoothCodecFormatter] invokeCodecStatusMethod -> method unavailable: $methodName"
                )
                return null
            }
            method.invoke(codecStatus)
        } catch (e: InvocationTargetException) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] invokeCodecStatusMethod -> method failed: $methodName",
                e.targetException ?: e
            )
            null
        } catch (e: ReflectiveOperationException) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] invokeCodecStatusMethod -> reflection failed: $methodName",
                e
            )
            null
        } catch (e: RuntimeException) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] invokeCodecStatusMethod -> runtime failed: $methodName",
                e
            )
            null
        } catch (e: LinkageError) {
            Log.e(
                BLUETOOTH_CODEC_LOG_TAG,
                "[BluetoothCodecFormatter] invokeCodecStatusMethod -> API unavailable: $methodName",
                e
            )
            null
        }
    }
}
