package com.xingkeqi.btlogger.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 1.5 线宽、圆头圆角的线性图标（路径取自 Lucide，ISC 许可）。
 *
 * Why:
 * 规范要求同一界面只用一套线宽一致、只描不填的图标；原来混用 Material 实心图标和 960 网格的填充矢量，
 * 粗细与风格都不统一。统一在 24×24 网格里描边，颜色由 Icon 的 tint 决定。
 */
object LineIcons {
    val Search by lazy { lineIcon("Search", "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0 -16z", "m21 21-4.3-4.3") }
    val Close by lazy { lineIcon("Close", "M18 6 6 18", "m6 6 12 12") }
    val ArrowLeft by lazy { lineIcon("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }
    val ChevronRight by lazy { lineIcon("ChevronRight", "m9 18 6-6-6-6") }
    val MoreVertical by lazy {
        lineIcon(
            "MoreVertical",
            "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0 -2z",
            "M12 4a1 1 0 1 0 0 2a1 1 0 1 0 0 -2z",
            "M12 18a1 1 0 1 0 0 2a1 1 0 1 0 0 -2z"
        )
    }
    val Share by lazy {
        lineIcon(
            "Share",
            "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",
            "m16 6-4-4-4 4",
            "M12 2v13"
        )
    }
    val Trash by lazy {
        lineIcon(
            "Trash",
            "M3 6h18",
            "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
            "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"
        )
    }
    val Refresh by lazy {
        lineIcon(
            "Refresh",
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5"
        )
    }
    val Info by lazy {
        lineIcon("Info", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20z", "M12 16v-4", "M12 8h.01")
    }
    val Bluetooth by lazy { lineIcon("Bluetooth", "m7 7 10 10-5 5V2l5 5L7 17") }
    val BluetoothOff by lazy {
        lineIcon("BluetoothOff", "m17 17-5 5V12l-5 5", "m2 2 20 20", "M14.5 9.5 17 7l-5-5v4.5")
    }
    val Volume by lazy {
        lineIcon(
            "Volume",
            "M11 5 6 9H2v6h4l5 4V5z",
            "M15.54 8.46a5 5 0 0 1 0 7.07",
            "M19.07 4.93a10 10 0 0 1 0 14.14"
        )
    }
    val Battery by lazy {
        lineIcon(
            "Battery",
            "M4 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z",
            "M22 11v2",
            "M6 11v2",
            "M10 11v2"
        )
    }
    val Waveform by lazy {
        lineIcon(
            "Waveform",
            "M2 13a2 2 0 0 0 2-2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0 1 2-2"
        )
    }
    val Spreadsheet by lazy {
        lineIcon(
            "Spreadsheet",
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z",
            "M14 2v4a2 2 0 0 0 2 2h4",
            "M8 13h2",
            "M14 13h2",
            "M8 17h2",
            "M14 17h2"
        )
    }
    val Check by lazy { lineIcon("Check", "M20 6 9 17l-5-5") }
}

private fun lineIcon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
    paths.forEach { data ->
        builder.addPath(
            pathData = addPathNodes(data),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
    return builder.build()
}
