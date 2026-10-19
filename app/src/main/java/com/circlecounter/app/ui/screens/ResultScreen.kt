package com.circlecounter.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.circlecounter.app.data.KnownTrack
import com.circlecounter.app.domain.DistanceFormatter
import com.circlecounter.app.domain.LapStat
import com.circlecounter.app.domain.LapStatsCalculator
import com.circlecounter.app.ui.FinishedRun
import java.io.File

@Composable
fun ResultScreen(
    finished: FinishedRun,
    knownTracks: List<KnownTrack>,
    onAddToExisting: (String) -> Unit,
    onSaveAsNew: (String) -> Unit,
    onCancelDecision: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<DecisionMode?>(null) }
    var newName by remember { mutableStateOf("") }
    var selectedExistingId by remember { mutableStateOf<String?>(null) }
    val run = finished.run
    val scroll = rememberScrollState()
    val onBg = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .verticalScroll(scroll),
    ) {
        Text(
            text = when {
                finished.needsTrackDecision -> "Круги определены"
                finished.detectionFailed -> "Круги не определены"
                else -> "Статистика"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                finished.detectionFailed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.height(12.dp))
        Text("Время: ${DistanceFormatter.formatElapsed(run.elapsedMs)}", color = onBg)
        Text("Круги: ${run.completedLaps}", color = onBg)
        Text("GPS: ${DistanceFormatter.formatKmM(run.gpsDistanceMeters)}", color = onBg)
        Text("Ручная: ${DistanceFormatter.formatKmM(run.manualDistanceMeters)}", color = onBg)
        Text("По треку: ${DistanceFormatter.formatKmM(run.computedDistanceMeters)}", color = onBg)
        if (run.avgHeartRateBpm != null || run.maxHeartRateBpm != null) {
            Text(
                "Пульс ср.: ${run.avgHeartRateBpm ?: "—"} · макс.: ${run.maxHeartRateBpm ?: "—"}",
                color = onBg,
            )
        }

        if (finished.lapStats.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "По кругам",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            finished.lapStats.forEach { lap ->
                LapStatCard(lap)
                Spacer(Modifier.height(10.dp))
            }
        } else if (run.completedLaps == 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Нет завершённых кругов для разбивки.",
                fontSize = 14.sp,
                color = onBg.copy(alpha = 0.75f),
            )
        }

        finished.decisionMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val file = File(context.cacheDir, "run_${run.id}.gpx")
                file.writeText(finished.gpx)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Экспорт GPX"))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Выгрузить GPX на комп / в приложение")
        }

        if (finished.needsTrackDecision && !finished.decisionDone) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Что сделать с кольцом?",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = onBg,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Трек при старте не был назван. Выберите действие.",
                fontSize = 14.sp,
                color = onBg.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))

            when (mode) {
                null -> {
                    Button(
                        onClick = { mode = DecisionMode.ADD },
                        enabled = knownTracks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Добавить к существующему") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { mode = DecisionMode.NEW },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Сохранить как новый") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancelDecision,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Отмена") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Отмена сохранит трек как ${formatHint(run.startedAtMs)} (макс. 10 таких).",
                        fontSize = 12.sp,
                        color = onBg.copy(alpha = 0.65f),
                    )
                }
                DecisionMode.ADD -> {
                    Text("Выберите трек", fontWeight = FontWeight.Medium, color = onBg)
                    Spacer(Modifier.height(8.dp))
                    knownTracks.forEach { track ->
                        val selected = selectedExistingId == track.id
                        Text(
                            text = buildString {
                                append(track.name)
                                if (track.autoNamed) append(" · авто")
                            },
                            color = onBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { selectedExistingId = track.id }
                                .padding(12.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Button(
                        onClick = { selectedExistingId?.let(onAddToExisting) },
                        enabled = selectedExistingId != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Добавить") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { mode = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Назад") }
                }
                DecisionMode.NEW -> {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Например: домашний стадион") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onSaveAsNew(newName) },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Сохранить") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { mode = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Назад") }
                }
            }
        } else if (finished.detectionFailed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Трек можно выгрузить, но сохранить как кольцо нельзя — круги не подтверждены.",
                fontSize = 14.sp,
                color = onBg.copy(alpha = 0.8f),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            enabled = !finished.needsTrackDecision || finished.decisionDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("На главную")
        }
    }
}

@Composable
private fun LapStatCard(lap: LapStat) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Круг ${lap.lapNumber}",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 16.sp,
        )
        Text("Время: ${DistanceFormatter.formatElapsed(lap.elapsedMs)}", color = onBg)
        Text(
            "Дистанция GPS: ${DistanceFormatter.formatKmM(lap.gpsDistanceMeters)}",
            color = onBg,
        )
        Text(
            "Дистанция ручная: ${DistanceFormatter.formatKmM(lap.manualDistanceMeters)}",
            color = onBg,
        )
        Text(
            "Скорость GPS: ${LapStatsCalculator.formatSpeedKmh(lap.speedGpsKmh)}",
            color = onBg,
        )
        Text(
            "Скорость по ручной: ${LapStatsCalculator.formatSpeedKmh(lap.speedManualKmh)}",
            color = onBg,
        )
        Text(
            "Пульс ср.: ${lap.avgHeartRateBpm ?: "—"} · макс.: ${lap.maxHeartRateBpm ?: "—"}",
            color = onBg,
        )
        Text("Каденс ср.: ${lap.avgCadenceSpm?.let { "$it шаг/мин" } ?: "—"}", color = onBg)
    }
}

private enum class DecisionMode { ADD, NEW }

private fun formatHint(startedAtMs: Long): String =
    com.circlecounter.app.data.TrackRepository.autoNameForStart(startedAtMs)
