package com.circlecounter.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circlecounter.app.data.KnownTrack
import com.circlecounter.app.hr.HrConnectionState
import com.circlecounter.app.hr.HrDevice
import com.circlecounter.app.ui.RunUiState

@Composable
fun HomeScreen(
    state: RunUiState,
    onSelectTrack: (KnownTrack?) -> Unit,
    onManualLength: (String) -> Unit,
    onStart: () -> Unit,
    onOpenTracks: () -> Unit,
    onScanHr: () -> Unit,
    onStopHrScan: () -> Unit,
    onSelectHr: (HrDevice) -> Unit,
    onClearHr: () -> Unit,
    onConnectHr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Text(
            text = "Circle Counter",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Счётчик кругов по повтору маршрута",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        Text("Трек", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        TrackChip(
            label = "Новый трек",
            selected = state.selectedTrack == null,
            onClick = { onSelectTrack(null) },
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.knownTracks, key = { it.id }) { track ->
                TrackChip(
                    label = track.name,
                    subtitle = buildString {
                        if (track.autoNamed) append("авто")
                        track.manualLapLengthMeters?.let {
                            if (isNotEmpty()) append(" · ")
                            append("ручн. ${it.toInt()} м")
                        }
                        track.computedLapLengthMeters?.let {
                            if (isNotEmpty()) append(" · ")
                            append("GPS ~${it.toInt()} м")
                        }
                    },
                    selected = state.selectedTrack?.id == track.id,
                    onClick = { onSelectTrack(track) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Пульсометр", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                HeartRateBlock(
                    state = state,
                    onScanHr = onScanHr,
                    onStopHrScan = onStopHrScan,
                    onSelectHr = onSelectHr,
                    onClearHr = onClearHr,
                    onConnectHr = onConnectHr,
                )
            }
        }

        OutlinedTextField(
            value = state.manualLapLengthInput,
            onValueChange = onManualLength,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Длина круга вручную, м (необязательно)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = state.statusText,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Старт", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onOpenTracks,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Управление треками")
        }
    }
}

@Composable
private fun HeartRateBlock(
    state: RunUiState,
    onScanHr: () -> Unit,
    onStopHrScan: () -> Unit,
    onSelectHr: (HrDevice) -> Unit,
    onClearHr: () -> Unit,
    onConnectHr: () -> Unit,
) {
    val hr = state.heartRate
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when {
                hr.bpm != null -> "${hr.bpm} уд/мин"
                else -> "—"
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(hr.statusText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        if (hr.deviceName != null || hr.deviceAddress != null) {
            Text(
                "Датчик: ${hr.deviceName ?: hr.deviceAddress}",
                fontSize = 13.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hr.connection == HrConnectionState.SCANNING) {
                OutlinedButton(onClick = onStopHrScan) { Text("Стоп поиска") }
            } else {
                OutlinedButton(onClick = onScanHr) { Text("Найти HW9") }
            }
            if (hr.deviceAddress != null &&
                hr.connection != HrConnectionState.CONNECTED &&
                hr.connection != HrConnectionState.CONNECTING &&
                hr.connection != HrConnectionState.SCANNING
            ) {
                OutlinedButton(onClick = onConnectHr) { Text("Подключить") }
            }
            if (hr.deviceAddress != null) {
                TextButton(onClick = onClearHr) { Text("Сбросить") }
            }
        }

        hr.scanned.forEach { device ->
            TrackChip(
                label = device.name,
                subtitle = "${device.address} · RSSI ${device.rssi}",
                selected = hr.deviceAddress == device.address,
                onClick = { onSelectHr(device) },
            )
        }
    }
}

@Composable
private fun TrackChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String = "",
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
