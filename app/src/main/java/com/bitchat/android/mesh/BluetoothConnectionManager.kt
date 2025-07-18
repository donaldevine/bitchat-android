package com.bitchat.android.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages Bluetooth connections, advertising, and scanning
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class BluetoothConnectionManager(private val context: Context, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
        // Use exact same UUIDs as iOS version and original service
        private val SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        private val CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    
    // Connection tracking - FIXED to properly track both server and client connections
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceCharacteristics = ConcurrentHashMap<BluetoothDevice, BluetoothGattCharacteristic>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private val gattConnections = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>() // Track GATT client connections
    private val peripheralRSSI = ConcurrentHashMap<String, Int>() // Track RSSI by device address during discovery
    
    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Start all Bluetooth services
     */
    fun startServices(): Boolean {
        Log.i(TAG, "Starting Bluetooth services...")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleScanner == null || bleAdvertiser == null) {
            Log.e(TAG, "BLE scanner or advertiser not available")
            return false
        }
        
        try {
            setupGattServer()
            
            // Start services in sequence
            connectionScope.launch {
                delay(500) // Ensure GATT server is ready
                
                startAdvertising()
                delay(200)
                
                startScanning()
                delay(200)
                
                Log.i(TAG, "All Bluetooth services started successfully")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop all Bluetooth services
     */
    fun stopServices() {
        Log.i(TAG, "Stopping Bluetooth services")
        
        connectionScope.launch {
            // Cleanup all GATT client connections
            gattConnections.values.forEach { gatt ->
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing GATT connection: ${e.message}")
                }
            }
            
            // Stop advertising and scanning
            stopAdvertising()
            stopScanning()
            
            // Close GATT server
            gattServer?.close()
            
            // Clear all connection tracking
            connectedDevices.clear()
            deviceCharacteristics.clear()
            subscribedDevices.clear()
            gattConnections.clear()
            peripheralRSSI.clear()
            
            connectionScope.cancel()
        }
    }
    
    /**
     * Broadcast packet to all connected devices
     */
    fun broadcastPacket(packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        
        Log.d(TAG, "Broadcasting packet type ${packet.type} to ${subscribedDevices.size} server connections and ${gattConnections.size} client connections")
        
        // Send to devices connected to our GATT server
        subscribedDevices.forEach { device ->
            try {
                characteristic?.let { char ->
                    char.value = data
                    val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
                    if (success) {
                        Log.d(TAG, "Sent packet to server connection: ${device.address}")
                    } else {
                        Log.w(TAG, "Failed to send packet to server connection: ${device.address}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            }
        }
        
        // Send to devices we are connected to as a client
        gattConnections.forEach { (device, gatt) ->
            try {
                val characteristic = deviceCharacteristics[device]
                if (characteristic != null) {
                    characteristic.value = data
                    val success = gatt.writeCharacteristic(characteristic)
                    if (success) {
                        Log.d(TAG, "Sent packet to client connection: ${device.address}")
                    } else {
                        Log.w(TAG, "Failed to send packet to client connection: ${device.address}")
                    }
                } else {
                    Log.w(TAG, "No characteristic found for client connection: ${device.address}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to client connection ${device.address}: ${e.message}")
            }
        }
    }
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int {
        return connectedDevices.size
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager Debug Info ===")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${hasBluetoothPermissions()}")
            appendLine("BLE Scanner Available: ${bleScanner != null}")
            appendLine("BLE Advertiser Available: ${bleAdvertiser != null}")
            appendLine("GATT Server Active: ${gattServer != null}")
            appendLine()
            appendLine("Connected Devices: ${connectedDevices.size}")
            connectedDevices.forEach { (address, device) ->
                appendLine("  - $address")
            }
            appendLine()
            appendLine("GATT Client Connections: ${gattConnections.size}")
            gattConnections.keys.forEach { device ->
                appendLine("  - ${device.address}")
            }
            appendLine()
            appendLine("Subscribed Devices (server mode): ${subscribedDevices.size}")
            subscribedDevices.forEach { device ->
                appendLine("  - ${device.address}")
            }
            appendLine()
            appendLine("Peripheral RSSI: ${peripheralRSSI.size}")
            peripheralRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }
    
    /**
     * Check if we have the required Bluetooth permissions
     */
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
        
        return permissions.all { 
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    /**
     * Setup GATT server for peripheral mode
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Device connected to server: ${device.address}")
                        connectedDevices[device.address] = device
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Device disconnected from server: ${device.address}")
                        connectedDevices.remove(device.address)
                        deviceCharacteristics.remove(device)
                        subscribedDevices.remove(device)
                    }
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    Log.d(TAG, "Received write request from ${device.address}, ${value.size} bytes")
                    
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = String(packet.senderID).replace("\u0000", "")
                        delegate?.onPacketReceived(packet, peerID, device)
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.d(TAG, "Device ${device.address} subscribed to notifications")
                    if (!subscribedDevices.contains(device)) {
                        subscribedDevices.add(device)
                        
                        // Notify delegate about new connection
                        connectionScope.launch {
                            delay(100) // Ensure connection is stable
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // Clean up any existing GATT server
        gattServer?.close()
        clearAllConnections()
        
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic
        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // Add notification descriptor
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        // Create service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start BLE advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) {
            Log.e(TAG, "Cannot start advertising: missing permissions or advertiser unavailable")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), myPeerID.toByteArray(Charsets.UTF_8))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started successfully with peer ID: $myPeerID")
            }
            
            override fun onStartFailure(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Unknown error: $errorCode"
                }
                Log.e(TAG, "Advertising failed: $errorMessage")
                
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    startMinimalAdvertising()
                }
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Fallback minimal advertising
     */
    @Suppress("DEPRECATION")
    private fun startMinimalAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) return
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Minimal advertising started successfully")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Even minimal advertising failed: $errorCode")
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting minimal advertising: ${e.message}")
        }
    }
    
    /**
     * Stop BLE advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) return
        bleAdvertiser.stopAdvertising(object : AdvertiseCallback() {})
    }
    
    /**
     * Start BLE scanning
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null) {
            Log.e(TAG, "Cannot start scanning: missing permissions or scanner unavailable")
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(10)
            .build()
        
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }
            
            override fun onScanFailed(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    else -> "Unknown error: $errorCode"
                }
                Log.e(TAG, "Scan failed: $errorMessage")
            }
        }
        
        try {
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.i(TAG, "Started BLE scanning")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
        }
    }
    
    /**
     * Stop BLE scanning
     */
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null) return
        bleScanner.stopScan(object : ScanCallback() {})
    }
    
    /**
     * Handle scan result and connect to discovered devices
     */
    @Suppress("DEPRECATION")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        
        // Filter out weak signals
        if (rssi < -90) {
            return
        }
        
        // Check if already connected
        if (connectedDevices.values.any { it.address == device.address }) {
            return
        }
        
        // Store RSSI
        peripheralRSSI[device.address] = rssi
        
        Log.i(TAG, "Found bitchat service at ${device.address} (RSSI: $rssi), connecting...")
        
        // Connect to device
        connectToDevice(device)
    }
    
    /**
     * Connect to a discovered device
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) return
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to ${gatt.device.address} as client")
                        connectedDevices[gatt.device.address] = gatt.device
                        gattConnections[gatt.device] = gatt
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from ${gatt.device.address}")
                        connectedDevices.remove(gatt.device.address)
                        deviceCharacteristics.remove(gatt.device)
                        gattConnections.remove(gatt.device)
                        gatt.close()
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    
                    if (characteristic != null) {
                        deviceCharacteristics[gatt.device] = characteristic
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Enable notifications
                        val descriptor = characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        
                        // Notify delegate
                        connectionScope.launch {
                            delay(200)
                            delegate?.onDeviceConnected(gatt.device)
                        }
                    }
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = String(packet.senderID).replace("\u0000", "")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                }
            }
        }
        
        device.connectGatt(context, false, gattCallback)
    }
    
    /**
     * Clear all connection tracking
     */
    private fun clearAllConnections() {
        connectedDevices.clear()
        deviceCharacteristics.clear()
        subscribedDevices.clear()
        gattConnections.clear()
        peripheralRSSI.clear()
    }
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
}
