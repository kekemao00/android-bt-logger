package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.xingkeqi.btlogger.data.RecordInfo

/**
 * 电量趋势图表
 * @param records 记录列表（按时间排序）
 */
@Composable
fun BatteryTrendChart(
    records: List<RecordInfo>,
    modifier: Modifier = Modifier
) {
    if (records.size < 2) return

    val modelProducer = remember { CartesianChartModelProducer() }

    // 提取电量数据
    val batteryLevels = records.mapNotNull { it.batteryLevel.takeIf { level -> level > 0 } }
    if (batteryLevels.size < 2) return

    // 更新图表数据
    LaunchedEffect(records) {
        modelProducer.runTransaction {
            lineSeries { series(batteryLevels) }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom()
        ),
        modelProducer = modelProducer,
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
    )
}
