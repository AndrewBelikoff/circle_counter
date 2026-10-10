package com.circlecounter.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circlecounter.app.domain.DistanceFormatter
import com.circlecounter.app.ui.GpsQuality
import com.circlecounter.app.ui.RunUiState

@Composable
fun RunScreen(
    state: RunUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val secondary = MaterialTheme.colorScheme.secondary
    val metricFont = 34.sp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.selectedTrack?.name ?: "Новый трек",
                    color = secondary,
                )
                Text(
                    text = state.statusText,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            GpsSatelliteIcon(quality = state.gpsQuality)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "${state.completedLaps}",
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("кругов", color = secondary)

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconMetric(
                modifier = Modifier.weight(1f),
                value = DistanceFormatter.formatElapsed(state.elapsedMs),
                caption = "время",
                fontSize = metricFont,
                contentColor = onBg,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = onBg.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            IconMetric(
                modifier = Modifier.weight(1f),
                value = state.heartRate.bpm?.toString() ?: "—",
                caption = if (state.heartRate.bpm != null) "уд/мин" else "пульс",
                fontSize = metricFont,
                contentColor = onBg,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = onBg.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconMetric(
                modifier = Modifier.weight(1f),
                value = state.paceMetricText,
                caption = "мин/км",
                fontSize = metricFont,
                contentColor = onBg,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        tint = onBg.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            IconMetric(
                modifier = Modifier.weight(1f),
                value = formatCompactKm(state.distances.gpsMeters),
                caption = "км",
                fontSize = metricFont,
                contentColor = onBg,
                icon = {
                    TrackOvalIcon(
                        color = onBg.copy(alpha = 0.28f),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        MetricBlock(title = "Темп") {
            Text("GPS: ${state.paceGps}")
            Text("Ручная длина: ${state.paceManual}")
            Text("Длина трека: ${state.paceComputed}")
        }

        if (state.autoCountActive) {
            Spacer(Modifier.height(12.dp))
            MetricBlock(title = "Расстояние по кругам") {
                Text("Ручная: ${DistanceFormatter.formatKmM(state.distances.manualMeters)}")
                Text("По треку: ${DistanceFormatter.formatKmM(state.distances.computedMeters)}")
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isPaused) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Продолжить") }
            } else {
                Button(
                    onClick = onPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Пауза") }
            }
            Button(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Стоп") }
        }
    }
}

@Composable
private fun GpsSatelliteIcon(quality: GpsQuality) {
    val tint = when (quality) {
        GpsQuality.GOOD -> Color(0xFF52B788)
        GpsQuality.POOR -> Color(0xFFE9C46A)
        GpsQuality.ABSENT -> Color(0xFFE76F51)
    }
    Icon(
        imageVector = Icons.Filled.SatelliteAlt,
        contentDescription = when (quality) {
            GpsQuality.GOOD -> "GPS хороший"
            GpsQuality.POOR -> "GPS слабый"
            GpsQuality.ABSENT -> "GPS нет"
        },
        tint = tint,
        modifier = Modifier.size(28.dp),
    )
}

@Composable
private fun IconMetric(
    value: String,
    caption: String,
    fontSize: TextUnit,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.height(fontSize.value.dp * 2.4f),
        contentAlignment = Alignment.Center,
    ) {
        val iconSide = maxHeight * 0.92f
        Box(
            modifier = Modifier.size(iconSide),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = caption,
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TrackOvalIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // Stadium oval (capsule): two straights + semicircle ends, with track width.
        val pad = size.minDimension * 0.06f
        val outerW = size.width - pad * 2
        val outerH = size.height * 0.72f
        val outerLeft = pad
        val outerTop = (size.height - outerH) / 2f
        // Band between outer and inner borders (~lane width).
        val band = (outerH * 0.22f).coerceAtLeast(size.minDimension * 0.1f)
        val strokeW = (size.minDimension * 0.055f).coerceAtLeast(1.5f)

        fun stadiumPath(left: Float, top: Float, w: Float, h: Float): Path {
            val r = h / 2f
            return Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = left + w,
                        bottom = top + h,
                        cornerRadius = CornerRadius(r, r),
                    ),
                )
            }
        }

        val outer = stadiumPath(outerLeft, outerTop, outerW, outerH)
        val inner = stadiumPath(
            outerLeft + band,
            outerTop + band,
            outerW - band * 2,
            outerH - band * 2,
        )
        val midInset = band / 2f
        val mid = stadiumPath(
            outerLeft + midInset,
            outerTop + midInset,
            outerW - midInset * 2,
            outerH - midInset * 2,
        )

        val borderStroke = Stroke(width = strokeW, cap = StrokeCap.Round)
        drawPath(path = outer, color = color, style = borderStroke)
        drawPath(path = inner, color = color, style = borderStroke)
        drawPath(
            path = mid,
            color = color,
            style = Stroke(
                width = strokeW * 0.85f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(size.minDimension * 0.09f, size.minDimension * 0.07f),
                    0f,
                ),
            ),
        )
    }
}

private fun formatCompactKm(meters: Double): String {
    if (meters < 1.0) return "0"
    val km = meters / 1000.0
    return when {
        km < 10.0 -> "%.2f".format(km)
        else -> "%.1f".format(km)
    }
}

@Composable
private fun MetricBlock(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
