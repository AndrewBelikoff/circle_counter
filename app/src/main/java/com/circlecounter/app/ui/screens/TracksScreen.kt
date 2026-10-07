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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circlecounter.app.data.KnownTrack
import com.circlecounter.app.data.TrackRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TracksScreen(
    tracks: List<KnownTrack>,
    repository: TrackRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sorted = remember(tracks) { tracks.sortedByDescending { it.updatedAtMs } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Text(
            "Треки",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Сначала свежие. Авто-имена вроде 26-10-08_10:01 — до 10 штук.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sorted, key = { it.id }) { track ->
                TrackEditor(
                    track = track,
                    onRename = { name -> scope.launch { repository.renameTrack(track.id, name) } },
                    onManual = { meters ->
                        scope.launch { repository.updateManualLength(track.id, meters) }
                    },
                    onDelete = { scope.launch { repository.deleteTrack(track.id) } },
                )
            }
        }

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Назад")
        }
    }
}

@Composable
private fun TrackEditor(
    track: KnownTrack,
    onRename: (String) -> Unit,
    onManual: (Double?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(track.id, track.name) { mutableStateOf(track.name) }
    var manual by remember(track.id, track.manualLapLengthMeters) {
        mutableStateOf(track.manualLapLengthMeters?.toInt()?.toString() ?: "")
    }
    val updated = remember(track.updatedAtMs) {
        SimpleDateFormat("yy-MM-dd HH:mm", Locale.US).format(Date(track.updatedAtMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (track.autoNamed) {
            Text(
                "Авто · не назван",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = { Text("Ручная длина круга, м") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Text(
            "GPS-длина: ${track.computedLapLengthMeters?.toInt() ?: "—"} м · пробежек: ${track.runCount} · обновлён $updated",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row {
            TextButton(onClick = {
                onRename(name)
                onManual(manual.replace(',', '.').toDoubleOrNull())
            }) { Text("Сохранить") }
            TextButton(onClick = onDelete) { Text("Удалить") }
        }
    }
}
