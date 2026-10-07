package com.circlecounter.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.circlecounter.app.ui.RunViewModel
import com.circlecounter.app.ui.screens.HomeScreen
import com.circlecounter.app.ui.screens.ResultScreen
import com.circlecounter.app.ui.screens.RunScreen
import com.circlecounter.app.ui.screens.TracksScreen
import com.circlecounter.app.ui.theme.CircleCounterTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RunViewModel by viewModels()

    private enum class PendingAction { NONE, START, SCAN_HR, CONNECT_HR }

    private var pendingAction = PendingAction.NONE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val action = pendingAction
        pendingAction = PendingAction.NONE
        when (action) {
            PendingAction.START -> {
                val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (fine && hasBluetoothPermissions()) {
                    maybeRequestBackground()
                    viewModel.start()
                }
            }
            PendingAction.SCAN_HR -> {
                if (hasBluetoothPermissions()) viewModel.startHrScan()
            }
            PendingAction.CONNECT_HR -> {
                if (hasBluetoothPermissions()) viewModel.connectPreferredHr()
            }
            PendingAction.NONE -> Unit
        }
    }

    private val backgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional; foreground still works */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CircleCounterTheme {
                val state by viewModel.ui.collectAsState()
                var showTracks by remember { mutableStateOf(false) }

                when {
                    state.finished != null -> {
                        ResultScreen(
                            finished = state.finished!!,
                            knownTracks = state.knownTracks,
                            onAddToExisting = viewModel::addFinishedToExisting,
                            onSaveAsNew = viewModel::saveFinishedAsNew,
                            onCancelDecision = viewModel::cancelFinishedTrackDecision,
                            onDone = {
                                viewModel.clearFinished()
                                showTracks = false
                            },
                        )
                    }
                    state.isRunning -> {
                        RunScreen(
                            state = state,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = viewModel::stop,
                        )
                    }
                    showTracks -> {
                        TracksScreen(
                            tracks = state.knownTracks,
                            repository = (application as CircleCounterApp).trackRepository,
                            onBack = { showTracks = false },
                        )
                    }
                    else -> {
                        HomeScreen(
                            state = state,
                            onSelectTrack = viewModel::selectTrack,
                            onManualLength = viewModel::setManualLapLengthInput,
                            onStart = { ensurePermissions(PendingAction.START) },
                            onOpenTracks = { showTracks = true },
                            onScanHr = { ensurePermissions(PendingAction.SCAN_HR) },
                            onStopHrScan = viewModel::stopHrScan,
                            onSelectHr = viewModel::selectHrDevice,
                            onClearHr = viewModel::clearHrDevice,
                            onConnectHr = { ensurePermissions(PendingAction.CONNECT_HR) },
                        )
                    }
                }
            }
        }
    }

    private fun ensurePermissions(action: PendingAction) {
        val need = mutableListOf<String>()

        if (action == PendingAction.START) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.ACCESS_FINE_LOCATION
                need += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        // BLE scan on Android < 12 still needs location.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (action == PendingAction.SCAN_HR || action == PendingAction.CONNECT_HR) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.BLUETOOTH_SCAN
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.BLUETOOTH_CONNECT
            }
        }

        if (need.isNotEmpty()) {
            pendingAction = action
            permissionLauncher.launch(need.distinct().toTypedArray())
            return
        }

        when (action) {
            PendingAction.START -> {
                maybeRequestBackground()
                viewModel.start()
            }
            PendingAction.SCAN_HR -> viewModel.startHrScan()
            PendingAction.CONNECT_HR -> viewModel.connectPreferredHr()
            PendingAction.NONE -> Unit
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun maybeRequestBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}
