package com.xingkeqi.btlogger.service.earbud

/**
 * SOUNDPEATS App 读取左耳、右耳与充电仓电量所用的 SPP 私有协议（纯 Kotlin，不依赖 Android）。
 *
 * Why:
 * 系统蓝牙（HFP）只上报一个电量，SOUNDPEATS 耳机报的是主耳（通常是左耳），右耳与充电仓的电量只有厂商协议里有。
 * 帧格式、指令与解析规则照搬 SOUNDPEATS App（spv2 仓库）中已在这些耳机上使用的实现，不做额外假设：
 * - 帧：`FF 04 00 LEN 00 0A CMD_H CMD_L payload`，LEN 为 payload 字节数，无校验；
 * - 回复的指令是请求指令最高位置 1（0x0306 → 0x8306）；
 * - 电量回复 payload 为 `[状态][百分比]`，App 只读百分比、原样显示，充电仓回复还要求 payload 恰好 2 字节；
 * - 百分比为 0 时 App 视为该部分不在线（耳机放回充电仓或未连接），不显示。
 */
object SoundpeatsProtocol {

    /** 标准串口服务 UUID，SOUNDPEATS App 用它建立 RFCOMM 通道 */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    const val CMD_LEFT_BATTERY = 0x0306
    const val CMD_RIGHT_BATTERY = 0x0307
    const val CMD_CASE_BATTERY = 0x0323

    /** 读取固件版本；App 建立通道后先发这一条作为握手，再查询电量 */
    const val CMD_FIRMWARE_VERSION = 0x0309

    /** 帧头 8 字节：起始、版本、标记、长度、厂商 ID（2 字节）、指令（2 字节） */
    internal const val HEADER_SIZE = 8
    internal const val START_BYTE = 0xFF.toByte()
    private const val RESPONSE_FLAG = 0x8000
    private val VENDOR_ID = byteArrayOf(0x00, 0x0A)

    /** 一轮轮询依次查询的部位，与 App 的顺序一致 */
    val BATTERY_QUERIES: List<Pair<EarbudPart, Int>> = listOf(
        EarbudPart.LEFT to CMD_LEFT_BATTERY,
        EarbudPart.RIGHT to CMD_RIGHT_BATTERY,
        EarbudPart.CASE to CMD_CASE_BATTERY
    )

    /** 不带参数的请求帧，如左耳电量 `FF 04 00 00 00 0A 03 06` */
    fun request(command: Int): ByteArray =
        byteArrayOf(START_BYTE, 0x04, 0x00, 0x00) + VENDOR_ID + byteArrayOf((command shr 8).toByte(), command.toByte())

    /**
     * 是否按 SOUNDPEATS 协议读取。
     *
     * Why: 向不认识的 SPP 设备（车机、其他品牌耳机）发送厂商指令后果不可控，只对名称带 SOUNDPEATS 的耳机开放；
     * 名称含 QCY、Capsule3 的型号在 App 里走另一套 SDK，协议不同，同样排除。
     * 服务列表已知但没有串口服务时不支持；服务列表为空（系统还没取到）时允许尝试，连不上会自动退避。
     *
     * @param uuids 设备服务 UUID，逗号分隔
     */
    fun isSupported(name: String, uuids: String): Boolean {
        if (!name.contains("SOUNDPEATS", ignoreCase = true)) return false
        if (EXCLUDED_NAME_MARKERS.any { name.contains(it, ignoreCase = true) }) return false
        return uuids.isBlank() || uuids.contains(SPP_UUID, ignoreCase = true)
    }

    private val EXCLUDED_NAME_MARKERS = listOf("QCY", "Capsule3")

    /**
     * 解析电量回复。
     *
     * @return 不是电量回复时为 null；是电量回复但数值无效或为 0（该部分不在线）时 [BatteryReply.level] 为 null
     */
    fun parseBatteryReply(frame: SoundpeatsFrame): BatteryReply? {
        val part = BATTERY_QUERIES.firstOrNull { (_, command) -> frame.command == (command or RESPONSE_FLAG) }?.first
            ?: return null
        val payload = frame.payload
        val raw = if (part == EarbudPart.CASE && payload.size != 2) null else payload.getOrNull(1)?.toInt()?.and(0xFF)
        return BatteryReply(part = part, level = raw?.takeIf { it in 1..100 })
    }
}

/** 电量所属部位 */
enum class EarbudPart { LEFT, RIGHT, CASE }

/**
 * 一条电量回复。
 *
 * @param level 1..100；null 表示该部分不在线或上报无效
 */
data class BatteryReply(val part: EarbudPart, val level: Int?)

/**
 * 一帧完整数据。payload 用 List 保存，便于比较与测试。
 */
data class SoundpeatsFrame(val command: Int, val payload: List<Byte>)

/**
 * 从 RFCOMM 字节流中切出完整帧，规则与 SOUNDPEATS App 一致：
 * - 跳过非 0xFF 的字节，直到找到帧头；
 * - 按帧头里的长度切帧，数据不够时等待下一次读取；
 * - 数据不够、但后面已经出现下一个帧头（距离不小于帧头长度）时，按下一个帧头重新对齐，避免一帧长度字段写错卡住后面所有回复。
 *
 * 非线程安全，只在读取协程里使用。
 *
 * @param maxBufferBytes 未切出的残留数据上限，超过即丢弃，防止异常数据持续累积
 */
class SoundpeatsFrameParser(private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES) {

    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray, length: Int = chunk.size): List<SoundpeatsFrame> {
        if (length <= 0) return emptyList()
        buffer += chunk.copyOf(length)
        val frames = mutableListOf<SoundpeatsFrame>()
        var start = 0
        while (start < buffer.size) {
            if (buffer[start] != SoundpeatsProtocol.START_BYTE) {
                start++
                continue
            }
            if (start + LENGTH_INDEX >= buffer.size) break
            val expected = SoundpeatsProtocol.HEADER_SIZE + (buffer[start + LENGTH_INDEX].toInt() and 0xFF)
            val remaining = buffer.size - start
            if (remaining < expected) {
                val nextHeader = (start + 1 until buffer.size).firstOrNull { buffer[it] == SoundpeatsProtocol.START_BYTE }
                if (nextHeader != null && nextHeader - start >= SoundpeatsProtocol.HEADER_SIZE) {
                    frames += frameOf(start, nextHeader)
                    start = nextHeader
                    continue
                }
                break
            }
            frames += frameOf(start, start + expected)
            start += expected
        }
        buffer = buffer.copyOfRange(start, buffer.size)
        if (buffer.size > maxBufferBytes) buffer = ByteArray(0)
        return frames
    }

    private fun frameOf(from: Int, to: Int): SoundpeatsFrame {
        val command = ((buffer[from + 6].toInt() and 0xFF) shl 8) or (buffer[from + 7].toInt() and 0xFF)
        return SoundpeatsFrame(command = command, payload = buffer.copyOfRange(from + SoundpeatsProtocol.HEADER_SIZE, to).toList())
    }

    private companion object {
        const val LENGTH_INDEX = 3
        const val DEFAULT_MAX_BUFFER_BYTES = 1024
    }
}

/**
 * 左耳、右耳与充电仓电量；null 表示未知（未读到、该部分不在线或未开启读取）。
 */
data class EarbudLevels(val left: Int? = null, val right: Int? = null, val case: Int? = null) {

    val isEmpty: Boolean get() = left == null && right == null && case == null

    fun with(reply: BatteryReply): EarbudLevels = when (reply.part) {
        EarbudPart.LEFT -> copy(left = reply.level)
        EarbudPart.RIGHT -> copy(right = reply.level)
        EarbudPart.CASE -> copy(case = reply.level)
    }

    companion object {
        val UNKNOWN = EarbudLevels()
    }
}
