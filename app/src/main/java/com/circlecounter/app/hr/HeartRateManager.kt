package com.circlecounter.app.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class HrDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

enum class HrConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    NO_PERMISSION,
    BT_OFF,
}

data class HeartRateState(
    val connection: HrConnectionState = HrConnectionState.IDLE,
    val bpm: Int? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val scanned: List<HrDevice> = emptyList(),
    val statusText: String = "Пульсометр не выбран",
)

class HeartRateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(HeartRateState())
    val state: StateFlow<HeartRateState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var preferredAddress: String? = null
    private var preferredName: String? = null
    private val found = ConcurrentHashMap<String, HrDevice>()
    private var autoReconnect = true

    fun setPreferred(address: String?, name: String?) {
        preferredAddress = address
        preferredName = name
        _state.update {
            it.copy(
                deviceAddress = address,
                deviceName = name,
                statusText = when {
                    address == null -> "Пульсометр не выбран"
                    it.connection == HrConnectionState.CONNECTED -> "Пульс: ${it.bpm ?: "—"}"
                    else -> "Сохранён: ${name ?: address}"
                },
            )
        }
    }

    fun startScan() {
        if (!ensureReadyForScan()) return
        stopScanInternal()
        found.clear()
        _state.update {
            it.copy(
                connection = HrConnectionState.SCANNING,
                scanned = emptyList(),
                statusText = "Поиск датчиков…",
            )
        }

        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.update { it.copy(connection = HrConnectionState.BT_OFF, statusText = "Bluetooth выключен") }
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Broad scan: some firmwares (incl. HW9 variants) omit 0x180D in advertising.
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            _state.update {
                it.copy(connection = HrConnectionState.NO_PERMISSION, statusText = "Нет разрешения Bluetooth")
            }
            return
        }

        scanJob = scope.launch {
            delay(12_000)
            stopScan()
            if (_state.value.scanned.isEmpty() && preferredAddress == null) {
                _state.update {
                    it.copy(
                        connection = HrConnectionState.IDLE,
                        statusText = "Ничего не найдено. Включите HW9 и повторите поиск.",
                    )
                }
            } else if (_state.value.connection == HrConnectionState.SCANNING) {
                _state.update {
                    it.copy(
                        connection = HrConnectionState.IDLE,
                        statusText = if (preferredAddress != null) {
                            "Сохранён: ${preferredName ?: preferredAddress}"
                        } else {
                            "Выберите датчик из списка"
                        },
                    )
                }
            }
        }
    }

    fun stopScan() {
        stopScanInternal()
        if (_state.value.connection == HrConnectionState.SCANNING) {
            _state.update {
                it.copy(
                    connection = HrConnectionState.IDLE,
                    statusText = when {
                        preferredAddress != null -> "Сохранён: ${preferredName ?: preferredAddress}"
                        it.scanned.isNotEmpty() -> "Выберите датчик из списка"
                        else -> "Поиск остановлен"
                    },
                )
            }
        }
    }

    fun selectAndConnect(device: HrDevice) {
        setPreferred(device.address, device.name)
        connect(device.address, device.name)
    }

    fun connectPreferred() {
        val address = preferredAddress ?: return
        connect(address, preferredName)
    }

    fun disconnect(clearPreferred: Boolean = false) {
        autoReconnect = false
        reconnectJob?.cancel()
        stopScanInternal()
        closeGatt()
        if (clearPreferred) {
            preferredAddress = null
            preferredName = null
        }
        _state.update {
            it.copy(
                connection = HrConnectionState.IDLE,
                bpm = null,
                deviceAddress = preferredAddress,
                deviceName = preferredName,
                statusText = if (preferredAddress == null) {
                    "Пульсометр не выбран"
                } else {
                    "Отключён: ${preferredName ?: preferredAddress}"
                },
            )
        }
    }

    fun release() {
        autoReconnect = false
        reconnectJob?.cancel()
        stopScanInternal()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String, name: String?) {
        if (!hasConnectPermission()) {
            _state.update {
                it.copy(connection = HrConnectionState.NO_PERMISSION, statusText = "Нет разрешения Bluetooth")
            }
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _state.update { it.copy(connection = HrConnectionState.BT_OFF, statusText = "Bluetooth выключен") }
            return
        }

        autoReconnect = true
        stopScanInternal()
        closeGatt()
        preferredAddress = address
        preferredName = name ?: preferredName

        _state.update {
            it.copy(
                connection = HrConnectionState.CONNECTING,
                deviceAddress = address,
                deviceName = preferredName,
                bpm = null,
                statusText = "Подключение к ${preferredName ?: address}…",
            )
        }

        try {
            val device = bt.getRemoteDevice(address)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (_: SecurityException) {
            _state.update {
                it.copy(connection = HrConnectionState.NO_PERMISSION, statusText = "Нет разрешения Bluetooth")
            }
        } catch (_: IllegalArgumentException) {
            _state.update {
                it.copy(connection = HrConnectionState.DISCONNECTED, statusText = "Некорректный адрес датчика")
            }
        }
    }

    private fun ensureReadyForScan(): Boolean {
        if (!hasScanPermission() || !hasConnectPermission()) {
            _state.update {
                it.copy(connection = HrConnectionState.NO_PERMISSION, statusText = "Нужно разрешение Bluetooth")
            }
            return false
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _state.update { it.copy(connection = HrConnectionState.BT_OFF, statusText = "Bluetooth выключен") }
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun onDeviceFound(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val rawName = try {
            device.name
        } catch (_: SecurityException) {
            null
        } ?: result.scanRecord?.deviceName
        val name = rawName?.takeIf { it.isNotBlank() } ?: "HR $address"
        // Prefer names that look like HW9 / heart rate, but keep all with HR service.
        val hasHrUuid = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
        val looksLikeHr = name.contains("HW9", ignoreCase = true) ||
            name.contains("COOSPO", ignoreCase = true) ||
            name.contains("HEART", ignoreCase = true) ||
            name.startsWith("HRM", ignoreCase = true) ||
            hasHrUuid
        if (!looksLikeHr) return

        found[address] = HrDevice(address = address, name = name, rssi = result.rssi)
        val list = found.values.sortedWith(
            compareByDescending<HrDevice> { it.name.contains("HW9", ignoreCase = true) }
                .thenByDescending { it.rssi },
        )
        _state.update { it.copy(scanned = list) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onDeviceFound(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::onDeviceFound)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update {
                it.copy(
                    connection = HrConnectionState.IDLE,
                    statusText = "Ошибка сканирования ($errorCode)",
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.update {
                        it.copy(
                            connection = HrConnectionState.CONNECTING,
                            statusText = "Обнаружение сервисов…",
                        )
                    }
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        _state.update {
                            it.copy(
                                connection = HrConnectionState.NO_PERMISSION,
                                statusText = "Нет разрешения Bluetooth",
                            )
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    _state.update {
                        it.copy(
                            connection = HrConnectionState.DISCONNECTED,
                            bpm = null,
                            statusText = "Нет связи с пульсометром",
                        )
                    }
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update {
                    it.copy(connection = HrConnectionState.DISCONNECTED, statusText = "Сервисы не найдены")
                }
                scheduleReconnect()
                return
            }
            val characteristic = gatt.getService(HR_SERVICE)
                ?.getCharacteristic(HR_MEASUREMENT)
            if (characteristic == null) {
                _state.update {
                    it.copy(
                        connection = HrConnectionState.DISCONNECTED,
                        statusText = "Нет Heart Rate Service на устройстве",
                    )
                }
                return
            }
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
                _state.update {
                    it.copy(
                        connection = HrConnectionState.CONNECTED,
                        statusText = "Пульсометр подключён",
                    )
                }
            } catch (_: SecurityException) {
                _state.update {
                    it.copy(connection = HrConnectionState.NO_PERMISSION, statusText = "Нет разрешения Bluetooth")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onHrBytes(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT) return
            onHrBytes(value)
        }
    }

    private fun onHrBytes(value: ByteArray) {
        val bpm = HeartRateParser.parseBpm(value) ?: return
        _state.update {
            it.copy(
                connection = HrConnectionState.CONNECTED,
                bpm = bpm,
                statusText = "Пульс: $bpm",
            )
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val address = preferredAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2_500)
            if (autoReconnect && preferredAddress == address &&
                _state.value.connection != HrConnectionState.CONNECTED &&
                _state.value.connection != HrConnectionState.CONNECTING
            ) {
                connect(address, preferredName)
            }
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
