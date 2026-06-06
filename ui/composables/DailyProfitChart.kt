package com.example.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.RoundRecord
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.GoldAccent
import kotlin.math.max
import kotlin.math.min

@Composable
fun DailyProfitChart(records: List<RoundRecord>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📈 Daily profit evolution (all time)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val sorted = records.sortedBy { it.dateStr }
            val profits = sorted.map { it.netUSD }

            if (profits.isNotEmpty()) {
                ProfitChartCanvas(profits, Modifier.fillMaxWidth().height(200.dp))
            }
        }
    }
}

@Composable
private fun ProfitChartCanvas(profits: List<Double>, modifier: Modifier = Modifier) {
    val maxProfit = profits.maxOrNull() ?: 1.0
    val minProfit = profits.minOrNull() ?: -1.0
    val range = maxProfit - minProfit

    Canvas(modifier = modifier) {
        if (profits.size <= 1) return@Canvas

        val stepX = size.width / (profits.size - 1)
        val points = profits.mapIndexed { i, p ->
            val normalizedY = if (range > 0) {
                (p - minProfit) / range
            } else {
                0.5
            }
            val y = size.height * (1 - normalizedY)
            Offset(stepX * i, y)
        }

        // Draw line
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            drawPath(
                path,
                GoldAccent,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Draw points
        points.forEach { point ->
            drawCircle(color = GoldAccent, radius = 4f, center = point)
        }
    }
}
