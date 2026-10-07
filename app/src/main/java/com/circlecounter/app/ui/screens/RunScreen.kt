package com.circlecounter.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circlecounter.app.domain.DistanceFormatter
import com.circlecounter.app.ui.RunUiState

@Composable
fun RunScreen(
    state: RunUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.selectedTrack?.name ?: "Новый трек",
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = state.statusText,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        Text(
            text = DistanceFormatter.formatElapsed(state.elapsedMs),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${state.completedLaps}",
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("кругов", color = MaterialTheme.colorScheme.secondary)

        Spacer(Modifier.height(12.dp))
        Text(
            text = state.heartRate.bpm?.let { "$it" } ?: "—",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = when {
                state.heartRate.bpm != null -> "уд/мин · ${state.heartRate.deviceName ?: "пульсометр"}"
                else -> state.heartRate.statusText
            },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(20.dp))

        MetricBlock(title = "Темп") {
            Text("GPS: ${state.paceGps}")
            Text("Ручная длина: ${state.paceManual}")
            Text("Длина трека: ${state.paceComputed}")
        }

        Spacer(Modifier.height(12.dp))

        MetricBlock(title = "Расстояние") {
            Text("GPS: ${DistanceFormatter.formatKmM(state.distances.gpsMeters)}")
            if (state.autoCountActive) {
                Text("Ручная: ${DistanceFormatter.formatKmM(state.distances.manualMeters)}")
                Text("По треку: ${DistanceFormatter.formatKmM(state.distances.computedMeters)}")
            } else {
                Text(
                    "Варианты по кругам появятся после подтверждения (3-й круг)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
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
