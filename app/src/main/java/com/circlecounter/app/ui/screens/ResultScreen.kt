package com.circlecounter.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                else -> "Готово"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                finished.detectionFailed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.height(12.dp))
        Text("Время: ${DistanceFormatter.formatElapsed(run.elapsedMs)}")
        Text("Круги: ${run.completedLaps}")
        Text("GPS: ${DistanceFormatter.formatKmM(run.gpsDistanceMeters)}")
        Text("Ручная: ${DistanceFormatter.formatKmM(run.manualDistanceMeters)}")
        Text("По треку: ${DistanceFormatter.formatKmM(run.computedDistanceMeters)}")
        if (run.avgHeartRateBpm != null || run.maxHeartRateBpm != null) {
            Text("Пульс ср.: ${run.avgHeartRateBpm ?: "—"} · макс.: ${run.maxHeartRateBpm ?: "—"}")
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
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Трек при старте не был назван. Выберите действие.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    )
                }
                DecisionMode.ADD -> {
                    Text("Выберите трек", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    knownTracks.forEach { track ->
                        val selected = selectedExistingId == track.id
                        Text(
                            text = buildString {
                                append(track.name)
                                if (track.autoNamed) append(" · авто")
                            },
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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

private enum class DecisionMode { ADD, NEW }

private fun formatHint(startedAtMs: Long): String =
    com.circlecounter.app.data.TrackRepository.autoNameForStart(startedAtMs)
