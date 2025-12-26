package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shader.DynamicShader
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.ui.theme.BatteryHigh
import com.xingkeqi.btlogger.ui.theme.Dimens

/**
 * 电量趋势图表 - 现代化设计
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

    // 图表颜色
    val lineColor = BatteryHigh

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Text(
                text = "电量趋势",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(Dimens.spacingSm))

            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.Line(
                                fill = LineCartesianLayer.LineFill.single(fill(lineColor)),
                                areaFill = LineCartesianLayer.AreaFill.single(
                                    fill(
                                        DynamicShader.verticalGradient(
                                            lineColor.copy(alpha = 0.4f).toArgb(),
                                            lineColor.copy(alpha = 0.0f).toArgb()
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                modelProducer = modelProducer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        }
    }
}
