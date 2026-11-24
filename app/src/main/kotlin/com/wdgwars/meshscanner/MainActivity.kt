package com.wdgwars.meshscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.text.InputType
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

private const val MESHTASTIC_SERVICE = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
private const val MESHTASTIC_FROM_RADIO = "2c55e69e-4993-11ed-b878-0242ac120002"
private const val MESHTASTIC_TO_RADIO = "f75c76d2-129e-4dad-a1dd-7866124401e7"
private const val MESHCORE_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
private const val MESHCORE_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
private const val MESHCORE_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
private const val UPLOAD_URL = "https://wdgwars.pl/endpoint/upload/"
private const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/sinXne0/MeshWarsScanner/releases/latest"

data class Observation(
    val nodeId: String,
    val name: String,
    val network: String,
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val seen: String,
    val publicKey: String? = null,
    val nodeType: String = "REPEATER",
    val pathHops: Int? = null,
    val snr: Double? = null,
    val decoded: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val observations = mutableStateListOf<Observation>()
    private var scanning by mutableStateOf(false)
    private var apiKey by mutableStateOf("")
    private var status by mutableStateOf("Ready")
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var scannerView: MeshWarsView
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanLimitMs = 60_000L
    private val meshGatts = mutableMapOf<String, BluetoothGatt>()
    private val meshRssi = mutableMapOf<String, Int>()
    private lateinit var usbManager: UsbManager
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    status = "USB radio detected: ${device?.productName ?: "Heltec V3"}"
                    scannerView.invalidate()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    status = "USB radio disconnected"
                    scannerView.invalidate()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 11, 18)
        window.navigationBarColor = android.graphics.Color.rgb(8, 11, 18)
        window.decorView.systemUiVisibility = 0
        apiKey = decrypt(prefs.getString("api_key", "")) ?: ""
        loadObservations()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        scannerView = MeshWarsView(this)
        setContentView(scannerView)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, usbFilter)
        }
        updateUsbStatus()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        meshGatts.values.forEach { it.close() }
        meshGatts.clear()
        super.onDestroy()
    }

    private fun updateUsbStatus() {
        val devices = usbManager.deviceList.values
        if (devices.isNotEmpty()) {
            val device = devices.first()
            status = "USB radio ready: ${device.productName ?: "Heltec V3"}"
        }
        scannerView.invalidate()
    }

    private fun loadObservations() {
        val raw = prefs.getString("observations", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                observations.add(Observation(
                    o.getString("nodeId"), o.getString("name"), o.getString("network"),
                    o.getInt("rssi"), o.getDouble("lat"), o.getDouble("lon"), o.getString("seen"),
                    o.optString("publicKey").ifBlank { null },
                    o.optString("nodeType", "REPEATER"),
                    if (o.has("pathHops")) o.optInt("pathHops") else null,
                    if (o.has("snr")) o.optDouble("snr") else null,
                    o.optBoolean("decoded", false)
                ))
            }
        } catch (_: Exception) {
            prefs.edit().remove("observations").apply()
        }
    }

    private fun saveObservations() {
        val array = JSONArray()
        observations.forEach {
            array.put(JSONObject().apply {
                put("nodeId", it.nodeId); put("name", it.name); put("network", it.network)
                put("rssi", it.rssi); put("lat", it.lat); put("lon", it.lon); put("seen", it.seen)
                it.publicKey?.let { key -> put("publicKey", key) }
                put("nodeType", it.nodeType)
                it.pathHops?.let { hops -> put("pathHops", hops) }
                it.snr?.let { value -> put("snr", value) }
                put("decoded", it.decoded)
            })
        }
        prefs.edit().putString("observations", array.toString()).apply()
    }

    private fun cryptoKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias("wdgwars-api-key")) {
            val generator = javax.crypto.KeyGenerator.getInstance(
                "AES", "AndroidKeyStore"
            )
            generator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    "wdgwars-api-key",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
        return store.getKey("wdgwars-api-key", null)
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cryptoKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, cryptoKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        } catch (_: Exception) { "" }
    }

    private fun permissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun startScan() {
        if (permissions().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions(), 10); return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        scanner = adapter.bluetoothLeScanner
        callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val serviceIds = (
                    record.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty() +
                        record.serviceData.keys.map { it.uuid.toString().lowercase() }
                    ).distinct()
                val name = result.device.name ?: record.deviceName ?: "BLE mesh device"
                val network = when {
                    serviceIds.contains(MESHTASTIC_SERVICE) ||
                        name.contains("meshtastic", true) ||
                        name.contains("heltec", true) ||
                        name.contains("t-beam", true) ||
                        name.contains("t-deck", true) -> "meshtastic"
                    serviceIds.contains(MESHCORE_SERVICE) || name.contains("meshcore", true) -> "meshcore"
                    else -> return
                }
                val loc = lastLocation
                val id = result.device.address.replace(":", "").lowercase()
                meshRssi[id] = result.rssi
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val item = Observation(id, name, network, result.rssi, loc?.latitude ?: 0.0,
                    loc?.longitude ?: 0.0, now)
                runOnUiThread {
                    observations.removeAll { it.nodeId == id }
                    observations.add(0, item)
                    saveObservations()
                    scannerView.invalidate()
                }
                if (network == "meshcore" && !meshGatts.containsKey(id)) {
                    connectMeshCore(result)
                } else if (network == "meshtastic" && !meshGatts.containsKey(id)) {
                    connectMeshtastic(result)
                }
            }
        }
        scanner?.startScan(callback)
        scanning = true
        status = "Scanning BLE advertisements"
        requestLocation()
        scannerView.invalidate()
        scanHandler.postDelayed({
            if (scanning) stopScan()
        }, scanLimitMs)
    }

    private fun stopScan() {
        callback?.let { scanner?.stopScan(it) }
        scanHandler.removeCallbacksAndMessages(null)
        scanning = false
        status = "Scan stopped • ${observations.size} observations"
        scannerView.invalidate()
    }

    private var lastLocation: Location? = null
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { lastLocation = it }
    }

    private fun connectMeshCore(result: ScanResult) {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val address = result.device.address.replace(":", "").lowercase()
        val gatt = result.device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    meshGatts[address] = gatt
                    gatt.requestMtu(247)
                    gatt.discoverServices()
                } else {
                    meshGatts.remove(address)
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(UUID.fromString(MESHCORE_SERVICE)) ?: return
                val tx = service.getCharacteristic(UUID.fromString(MESHCORE_TX)) ?: return
                val rx = service.getCharacteristic(UUID.fromString(MESHCORE_RX)) ?: return
                gatt.setCharacteristicNotification(tx, true)
                tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                // MeshCore companion handshake: APP_START + reserved bytes + app name.
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0) +
                    "MeshWars".toByteArray()
                gatt.writeCharacteristic(rx)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid.toString().equals(MESHCORE_TX, true)) {
                    parseMeshCorePacket(address, characteristic.value)
                }
            }
        })
        meshGatts[address] = gatt
    }

    private fun parseMeshCorePacket(address: String, packet: ByteArray) {
        if (packet.size < 44 || packet[0].toInt() != 0x05) return
        val key = packet.copyOfRange(4, 36).joinToString("") { "%02x".format(it) }
        val nodeId = key.take(16)
        val lat = littleInt(packet, 36) / 1_000_000.0
        val lon = littleInt(packet, 40) / 1_000_000.0
        val name = if (packet.size > 58) {
            packet.copyOfRange(58, packet.size).toString(Charsets.UTF_8)
                .trim('\u0000', ' ', '\n', '\r').ifBlank { "MeshCore $nodeId" }
        } else "MeshCore $nodeId"
        val location = lastLocation
        val finalLat = if (lat in -90.0..90.0 && lat != 0.0) lat else location?.latitude ?: 0.0
        val finalLon = if (lon in -180.0..180.0 && lon != 0.0) lon else location?.longitude ?: 0.0
        val seen = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val item = Observation(nodeId, name, "meshcore", meshRssi[address] ?: -100,
            finalLat, finalLon, seen, key, "REPEATER", decoded = true)
        runOnUiThread {
            observations.removeAll { it.nodeId == address || it.nodeId == nodeId }
            observations.add(0, item)
            saveObservations()
            status = "MeshCore node decoded: $nodeId"
            scannerView.invalidate()
        }
    }

    private fun connectMeshtastic(result: ScanResult) {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val address = result.device.address.replace(":", "").lowercase()
        val gatt = result.device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    meshGatts[address] = gatt
                    gatt.requestMtu(247)
                    gatt.discoverServices()
                } else {
                    meshGatts.remove(address)
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(UUID.fromString(MESHTASTIC_SERVICE)) ?: return
                val fromRadio = service.getCharacteristic(UUID.fromString(MESHTASTIC_FROM_RADIO)) ?: return
                val toRadio = service.getCharacteristic(UUID.fromString(MESHTASTIC_TO_RADIO)) ?: return
                gatt.setCharacteristicNotification(fromRadio, true)
                fromRadio.getDescriptor(UUID.fromString(
                    "00002902-0000-1000-8000-00805f9b34fb"
                ))?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                // Wait for descriptor completion before writing. Heltec V3
                // radios can silently ignore ToRadio writes sent earlier.
                if (fromRadio.getDescriptor(UUID.fromString(
                        "00002902-0000-1000-8000-00805f9b34fb"
                    )) == null) {
                    requestMeshtasticNodes(gatt)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
            ) {
                if (descriptor.characteristic.uuid.toString().equals(MESHTASTIC_FROM_RADIO, true)) {
                    requestMeshtasticNodes(gatt)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid.toString().equals(MESHTASTIC_FROM_RADIO, true)) {
                    parseMeshtasticFromRadio(address, characteristic.value)
                }
            }
        })
        meshGatts[address] = gatt
    }

    private fun requestMeshtasticNodes(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(MESHTASTIC_SERVICE)) ?: return
        val toRadio = service.getCharacteristic(UUID.fromString(MESHTASTIC_TO_RADIO)) ?: return
        // ToRadio.want_config_id = 0. The Heltec then streams FromRadio
        // NodeInfo protobuf records for the mesh it has heard.
        toRadio.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        toRadio.value = byteArrayOf(0x30, 0x00)
        gatt.writeCharacteristic(toRadio)
    }

    private data class ProtoField(val number: Int, val wire: Int, val value: Any)

    private fun protoFields(bytes: ByteArray): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        var p = 0
        while (p < bytes.size) {
            val tag = readVarint(bytes, p) ?: break
            p = tag.second
            val number = (tag.first shr 3).toInt()
            val wire = (tag.first and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(bytes, p) ?: break
                    result += ProtoField(number, wire, v.first); p = v.second
                }
                1 -> {
                    if (p + 8 > bytes.size) break
                    result += ProtoField(number, wire, bytes.copyOfRange(p, p + 8)); p += 8
                }
                2 -> {
                    val length = readVarint(bytes, p) ?: break
                    p = length.second
                    val end = (p + length.first.toInt()).coerceAtMost(bytes.size)
                    if (end < p) break
                    result += ProtoField(number, wire, bytes.copyOfRange(p, end)); p = end
                }
                5 -> {
                    if (p + 4 > bytes.size) break
                    result += ProtoField(number, wire, bytes.copyOfRange(p, p + 4)); p += 4
                }
                else -> break
            }
        }
        return result
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var p = start
        while (p < bytes.size && shift < 64) {
            val b = bytes[p++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return value to p
            shift += 7
        }
        return null
    }

    private fun parseMeshtasticFromRadio(address: String, packet: ByteArray) {
        val nodeFields = protoFields(packet).filter { it.number == 2 && it.wire == 2 }
        nodeFields.forEach { field ->
            val node = field.value as? ByteArray ?: return@forEach
            val fields = protoFields(node)
            val nodeNum = (fields.firstOrNull { it.number == 1 && it.wire == 0 }?.value as? Long) ?: 0L
            val user = fields.firstOrNull { it.number == 2 && it.wire == 2 }?.value as? ByteArray
            val position = fields.firstOrNull { it.number == 3 && it.wire == 2 }?.value as? ByteArray
            val userFields = user?.let { protoFields(it) }.orEmpty()
            val publicKey = userFields.firstOrNull { it.number == 7 && it.wire == 2 }?.value as? ByteArray
            val longName = userFields.firstOrNull { it.number == 2 && it.wire == 2 }?.value
                ?.let { (it as ByteArray).toString(Charsets.UTF_8) }?.trim().orEmpty()
            val shortName = userFields.firstOrNull { it.number == 3 && it.wire == 2 }?.value
                ?.let { (it as ByteArray).toString(Charsets.UTF_8) }?.trim().orEmpty()
            val role = (userFields.firstOrNull { it.number == 6 && it.wire == 0 }?.value as? Long)
                ?.let { meshtasticRole(it) } ?: "REPEATER"
            val positionFields = position?.let { protoFields(it) }.orEmpty()
            val lat = positionFields.firstOrNull { it.number == 1 && it.wire == 0 }
                ?.let { (it.value as Long).toInt() / 10_000_000.0 } ?: 0.0
            val lon = positionFields.firstOrNull { it.number == 2 && it.wire == 0 }
                ?.let { (it.value as Long).toInt() / 10_000_000.0 } ?: 0.0
            val nodeId = if (publicKey != null && publicKey.size >= 8) {
                publicKey.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
            } else "%08x".format(nodeNum)
            if (nodeId.length !in 8..16) return@forEach
            val phone = lastLocation
            val finalLat = if (lat != 0.0) lat else phone?.latitude ?: 0.0
            val finalLon = if (lon != 0.0) lon else phone?.longitude ?: 0.0
            val seen = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val item = Observation(nodeId, longName.ifBlank { shortName.ifBlank { "Meshtastic $nodeId" } },
                "meshtastic", meshRssi[address] ?: -100, finalLat, finalLon, seen,
                publicKey?.joinToString("") { "%02x".format(it) },
                role, decoded = true)
            runOnUiThread {
                observations.removeAll { it.nodeId == address || it.nodeId == nodeId }
                observations.add(0, item)
                saveObservations()
                status = "Meshtastic node decoded: $nodeId"
                scannerView.invalidate()
            }
        }
    }

    private fun meshtasticRole(value: Long): String = when (value.toInt()) {
        0 -> "CLIENT"
        1 -> "CLIENT_MUTE"
        2 -> "ROUTER"
        3 -> "ROUTER_CLIENT"
        4 -> "REPEATER"
        5 -> "TRACKER"
        6 -> "SENSOR"
        7 -> "TAK"
        8 -> "CLIENT_HIDDEN"
        9 -> "LOST_AND_FOUND"
        10 -> "TAK_TRACKER"
        11 -> "ROUTER_LATE"
        12 -> "MEDIA_PLAYER"
        else -> "CLIENT"
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
    }

    private suspend fun upload() {
        if (apiKey.isBlank()) { status = "Enter your WDGWARS API key first"; return }
        if (observations.isEmpty()) { status = "Nothing to upload"; return }
        val eligible = observations.filter {
            it.decoded &&
                it.nodeId.matches(Regex("[0-9a-f]{8,16}")) &&
                it.lat in -90.0..90.0 && it.lon in -180.0..180.0 &&
                it.lat != 0.0 && it.lon != 0.0
        }
        if (eligible.isEmpty()) {
            status = "No uploadable nodes: GPS fix is required by WDGWARS"
            return
        }
        status = "Uploading ${eligible.size} WDGWARS-ready nodes…"
        val result = withContext(Dispatchers.IO) {
            try {
                var uploaded = 0
                eligible.chunked(1000).forEach { batch ->
                    val nodes = JSONArray()
                    batch.forEach {
                        nodes.put(JSONObject().apply {
                            put("node_id", it.nodeId)
                            put("node_type", it.nodeType)
                            put("name", it.name)
                            put("lat", it.lat); put("lon", it.lon)
                            put("rssi", it.rssi); put("first_seen", it.seen)
                            put("type", "MESHCORE"); put("network", it.network)
                            val key = it.publicKey?.lowercase()
                            if (key != null && key.matches(Regex("[0-9a-f]{64}")) &&
                                key.startsWith(it.nodeId)) put("public_key", key)
                            it.pathHops?.let { hops -> put("path_hops", hops) }
                        })
                    }
                    val payload = JSONObject().apply {
                        put("networks", JSONArray()); put("aircraft", JSONArray())
                        put("meshcore_nodes", nodes)
                    }.toString()
                    val data = android.util.Base64.encodeToString(
                        payload.toByteArray(), android.util.Base64.NO_WRAP
                    )
                    val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
                    val sig = Mac.getInstance("HmacSHA256").run {
                        init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA256"))
                        doFinal("$nonce$data".toByteArray()).joinToString("") { "%02x".format(it) }
                    }
                    val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-API-Key", apiKey)
                    val body = JSONObject().apply {
                        put("data", data); put("nonce", nonce); put("sig", sig)
                    }.toString()
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    if (code !in 200..299) throw Exception("HTTP $code")
                    uploaded += batch.size
                }
                "Uploaded $uploaded WDGWARS-ready nodes"
            } catch (e: Exception) { "Upload failed: ${e.message}" }
        }
        status = result
        runOnUiThread { scannerView.invalidate() }
    }

    private fun editApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey)
            hint = "WDGWARS API key"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("WDGWARS uplink key")
            .setMessage("Stored encrypted on this device.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                apiKey = input.text.toString()
                prefs.edit().putString("api_key", encrypt(apiKey)).apply()
                status = "Uplink key saved"
                scannerView.invalidate()
            }.show()
    }

    private fun exportCapture() {
        val choices = arrayOf("WDGWARS JSON", "CSV")
        android.app.AlertDialog.Builder(this)
            .setTitle("Export capture")
            .setItems(choices) { _, which ->
                val mime = if (which == 0) "application/json" else "text/csv"
                val suffix = if (which == 0) "json" else "csv"
                val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_TITLE, "mesh_capture_${System.currentTimeMillis()}.$suffix")
                }
                startActivityForResult(intent, 44 + which)
             }.show()
    }

    private fun openSettings() {
        val choices = arrayOf(
            "Check for OTA update",
            "Set WDGWARS API key",
            "Export capture",
            "Clear local captures"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("MeshWars settings")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> checkForUpdate()
                    1 -> editApiKey()
                    2 -> exportCapture()
                    3 -> android.app.AlertDialog.Builder(this)
                        .setTitle("Clear captures?")
                        .setMessage("This removes locally saved observations.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("CLEAR") { _, _ ->
                            observations.clear()
                            saveObservations()
                            status = "Local captures cleared"
                            scannerView.invalidate()
                        }.show()
                }
            }.show()
    }

    private fun checkForUpdate() {
        status = "Checking GitHub for updates…"
        scannerView.invalidate()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(GITHUB_RELEASES_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.optString("tag_name").removePrefix("v")
                val latest = tag.split(".").mapNotNull { it.toIntOrNull() }
                val current = BuildConfig.VERSION_NAME.split(".").mapNotNull { it.toIntOrNull() }
                val newer = (0 until maxOf(latest.size, current.size)).any {
                    (latest.getOrElse(it) { 0 }) > (current.getOrElse(it) { 0 })
                } && !(0 until maxOf(latest.size, current.size)).any {
                    (current.getOrElse(it) { 0 }) > (latest.getOrElse(it) { 0 })
                }
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == "MeshWarsScanner.apk") {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                runOnUiThread {
                    if (newer && downloadUrl.isNotBlank()) {
                        status = "Update $tag available"
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("MeshWars Scanner $tag")
                            .setMessage("Download and install the latest release?")
                            .setNegativeButton("LATER", null)
                            .setPositiveButton("INSTALL") { _, _ -> downloadUpdate(downloadUrl) }
                            .show()
                    } else {
                        status = "Already up to date"
                        scannerView.invalidate()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status = "Update check failed"
                    scannerView.invalidate()
                }
            }
        }
    }

    private fun downloadUpdate(url: String) {
        status = "Downloading update…"
        scannerView.invalidate()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                val file = java.io.File(cacheDir, "MeshWarsScanner-update.apk")
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "${BuildConfig.APPLICATION_ID}.fileprovider", file
                )
                runOnUiThread {
                    status = "Opening Android installer…"
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status = "Update download failed"
                    scannerView.invalidate()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            val output = if (requestCode == 44) {
                val nodes = JSONArray()
                observations.forEach {
                    nodes.put(JSONObject().apply {
                        put("node_id", it.nodeId); put("node_type", it.nodeType)
                        put("name", it.name); put("lat", it.lat); put("lon", it.lon)
                        put("rssi", it.rssi); put("first_seen", it.seen)
                        put("type", "MESHCORE"); put("network", it.network)
                        it.publicKey?.let { key -> put("public_key", key) }
                        it.pathHops?.let { hops -> put("path_hops", hops) }
                    })
                }
                JSONObject().apply {
                    put("networks", JSONArray()); put("aircraft", JSONArray())
                    put("meshcore_nodes", nodes)
                }.toString(2)
            } else {
                buildString {
                    appendLine("node_id,name,network,rssi,lat,lon,first_seen")
                    observations.forEach {
                        appendLine("${it.nodeId},\"${it.name.replace("\"", "\"\"")}\",${it.network}," +
                            "${it.rssi},${it.lat},${it.lon},${it.seen}")
                    }
                }
            }
            contentResolver.openOutputStream(data.data!!)?.use {
                it.write(output.toByteArray(Charsets.UTF_8))
            }
            status = "Capture exported"
            scannerView.invalidate()
        } catch (e: Exception) {
            status = "Export failed"
            scannerView.invalidate()
        }
    }

    private inner class MeshWarsView(context: android.content.Context) : View(context) {
        private val art = BitmapFactory.decodeResource(resources, R.drawable.meshwars_scanner_background)
        private val panel = androidx.core.content.ContextCompat.getDrawable(
            context, R.drawable.meshwars_panel
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val olive = AndroidColor.rgb(158, 173, 105)
        private val amber = AndroidColor.rgb(194, 140, 76)
        private val paper = AndroidColor.rgb(215, 216, 207)
        private val muted = AndroidColor.rgb(154, 157, 145)
        private var sweep = 0f
        private var selectedNode: Observation? = null
        private var radarMode = "PROXIMITY"

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float,
                         color: Int = paper, bold: Boolean = false) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawBitmap(art, null, Rect(0, 0, width, height), paint)
            val sx = width / 941f
            val sy = height / 1672f
            canvas.save()
            canvas.scale(sx, sy)

            // The top plate is the device identity/status display.
            panel?.setBounds(48, 34, 893, 190)
            panel?.draw(canvas)
            text(canvas, "WDG // MESH SCANNER", 64f, 76f, 31f, paper, true)
            text(canvas, if (scanning) "RADIO ACTIVE" else "STANDBY", 66f, 119f, 20f,
                if (scanning) olive else muted, true)
            text(canvas, "MESHCORE  ${observations.count { it.network == "meshcore" }}",
                590f, 76f, 17f, amber, true)
            text(canvas, "MESHTASTIC  ${observations.count { it.network == "meshtastic" }}",
                590f, 106f, 17f, olive, true)
            text(canvas, "EXPORT", 790f, 151f, 14f, amber, true)
            text(canvas, "UPDATE", 670f, 151f, 14f, olive, true)
            text(canvas, "NODES ${observations.size}  //  ${status.take(34).uppercase()}",
                66f, 163f, 14f, muted)
            text(canvas, "RADAR: $radarMode", 360f, 163f, 14f, amber, true)

            // Live contacts appear as signal returns on the supplied radar.
            if (scanning) {
                val radians = sweep * Math.PI / 180.0
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = AndroidColor.argb(210, 158, 173, 105)
                canvas.drawLine(
                    470f, 650f,
                    470f + kotlin.math.cos(radians).toFloat() * 390f,
                    650f + kotlin.math.sin(radians).toFloat() * 390f,
                    paint
                )
                paint.style = Paint.Style.FILL
                sweep = (sweep + 3.5f) % 360f
                postInvalidateDelayed(35L)
            }
            observations.take(8).forEachIndexed { index, node ->
                val hashAngle = (node.nodeId.hashCode().ushr(1) % 360)
                val angle = (if (radarMode == "GPS") hashAngle else hashAngle + 18) *
                    Math.PI / 180.0
                val signalDistance = (((-node.rssi).coerceIn(30, 110) - 30) / 80f)
                val radius = if (radarMode == "GPS" && node.lat != 0.0 && node.lon != 0.0) {
                    70f + (index % 4) * 72f
                } else {
                    65f + signalDistance * 315f
                }
                val x = 470f + kotlin.math.cos(angle).toFloat() * radius
                val y = 650f + kotlin.math.sin(angle).toFloat() * radius
                paint.color = if (node.network == "meshcore") amber else olive
                paint.setShadowLayer(18f, 0f, 0f, paint.color)
                canvas.drawCircle(x, y, 7f, paint)
                paint.clearShadowLayer()
                if (selectedNode?.nodeId == node.nodeId) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = paper
                    canvas.drawCircle(x, y, 18f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            text(canvas, if (scanning) "SCANNING" else "TAP SCAN TO BEGIN",
                360f, 1040f, 18f, if (scanning) olive else amber, true)

            // Bottom plate is a native themed control/data console.
            panel?.setBounds(44, 1180, 897, 1590)
            panel?.draw(canvas)
            paint.color = AndroidColor.argb(72, 10, 13, 12)
            canvas.drawRoundRect(44f, 1190f, 897f, 1590f, 14f, 14f, paint)
            text(canvas, "CONTACT LOG", 70f, 1238f, 21f, paper, true)
            text(canvas, "WDGWARS FORMAT // GPS + RSSI + TIMESTAMP", 70f, 1267f, 13f, muted)
            observations.take(5).forEachIndexed { i, node ->
                val y = 1310f + i * 43f
                text(canvas, "${node.network.uppercase().take(4)}  ${node.nodeId}",
                    72f, y, 17f, if (node.network == "meshcore") amber else olive, true)
                text(canvas, "${node.rssi} dBm   ${node.lat.formatCoord()}, ${node.lon.formatCoord()}",
                    440f, y, 14f, paper)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (scanning) AndroidColor.rgb(165, 87, 68) else olive
            canvas.drawRoundRect(68f, 1510f, 300f, 1560f, 8f, 8f, paint)
            paint.color = amber
            canvas.drawRoundRect(320f, 1510f, 610f, 1560f, 8f, 8f, paint)
            paint.color = muted
            canvas.drawRoundRect(630f, 1510f, 870f, 1560f, 8f, 8f, paint)
            paint.style = Paint.Style.FILL
            text(canvas, if (scanning) "STOP SCAN" else "START SCAN", 112f, 1543f, 16f, paper, true)
            text(canvas, "UPLOAD", 420f, 1543f, 16f, paper, true)
            text(canvas, "SETTINGS", 688f, 1543f, 16f, paper, true)
            selectedNode?.let {
                text(canvas, "LOCK ${it.name.take(18)}", 70f, 1477f, 13f, paper, true)
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x / width * 941f
            val y = event.y / height * 1672f
            when {
                y in 1500f..1580f && x < 310f -> if (scanning) stopScan() else startScan()
                y in 1500f..1580f && x in 310f..620f ->
                    lifecycleScope.launch { upload() }
                y in 1500f..1580f && x > 620f -> openSettings()
                y in 120f..190f && x > 780f -> exportCapture()
                y in 120f..190f && x in 560f..780f -> checkForUpdate()
                y in 120f..190f && x in 300f..560f -> {
                    radarMode = if (radarMode == "PROXIMITY") "GPS" else "PROXIMITY"
                    status = if (radarMode == "GPS") "GPS radar selected" else "Proximity radar selected"
                    invalidate()
                }
                y in 190f..1130f -> {
                    if (observations.isNotEmpty()) {
                        val hit = observations.firstOrNull()
                        selectedNode = if (selectedNode?.nodeId == hit?.nodeId) null else hit
                        status = selectedNode?.let { "Selected ${it.name}" } ?: "Radar ready"
                        invalidate()
                    } else if (!scanning) {
                        startScan()
                    }
                }
            }
            return true
        }
    }

    @Composable
    private fun ScannerScreen() {
        val scope = rememberCoroutineScope()
        var filter by remember { mutableStateOf("ALL") }
        val filtered = observations.filter { filter == "ALL" || it.network.uppercase() == filter }
        val dark = darkColorScheme(
            background = Color(0xFF0B0D0D),
            surface = Color(0xFF171A19),
            surfaceVariant = Color(0xFF222624),
            primary = Color(0xFF9EAD69),
            secondary = Color(0xFFC28C4C),
            onBackground = Color(0xFFD7D8CF),
            onSurface = Color(0xFFD7D8CF)
        )
        MaterialTheme(colorScheme = dark) {
            Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("WDG", color = Color(0xFF9EAD69), fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.labelLarge)
                        Text("MESH SCANNER", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = if (scanning)
                        Color(0xFF303A27) else Color(0xFF222624)) {
                        Text(if (scanning) "● LIVE" else "● READY",
                            color = if (scanning) Color(0xFF83A56B) else Color(0xFF9A9D91),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontWeight = FontWeight.Bold)
                    }
                }
                Text("Meshtastic + MeshCore • WDGWARS uplink",
                    color = Color(0xFF9A9D91), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("NODES", observations.size.toString(), Color(0xFF9EAD69), Modifier.weight(1f))
                    StatCard("MESHCORE", observations.count { it.network == "meshcore" }.toString(),
                        Color(0xFFC28C4C), Modifier.weight(1f))
                    StatCard("MESHTASTIC", observations.count { it.network == "meshtastic" }.toString(),
                        Color(0xFF9EAD69), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171A19)),
                    border = BorderStroke(1.dp, Color(0xFF4B5049)),
                    shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (scanning) "Listening for mesh radios…" else "Ready to scan",
                            fontWeight = FontWeight.Bold)
                        Text(status, color = Color(0xFF9A9D91),
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { if (scanning) stopScan() else startScan() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (scanning) Color(0xFFA55744) else Color(0xFF9EAD69),
                                    contentColor = Color(0xFF0B0D0D))
                            ) { Text(if (scanning) "STOP SCAN" else "START SCAN",
                                fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { scope.launch { upload() } },
                                enabled = !scanning && observations.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("UPLOAD ${observations.size}") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; prefs.edit().putString("api_key", encrypt(it)).apply() },
                    label = { Text("WDGWARS API key") },
                    placeholder = { Text("Stored securely on this device", color = Color(0xFF9A9D91)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF9EAD69),
                        unfocusedBorderColor = Color(0xFF4B5049),
                        focusedLabelColor = Color(0xFF9EAD69),
                        unfocusedLabelColor = Color(0xFF9A9D91),
                        cursorColor = Color(0xFF9EAD69)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL", "MESHCORE", "MESHTASTIC").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option },
                            label = { Text(option) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("⌁", color = Color(0xFF9EAD69), style = MaterialTheme.typography.displayMedium)
                            Text("No mesh nodes found", fontWeight = FontWeight.Bold)
                            Text("Start a scan near an active radio", color = Color(0xFF91A0B5))
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(filtered, key = { it.nodeId }) { o -> ObservationCard(o) }
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier) {
        Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF222624)),
            border = BorderStroke(1.dp, Color(0xFF4B5049))) {
            Column(Modifier.padding(12.dp)) {
                Text(value, color = accent, fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.headlineSmall)
                Text(label, color = Color(0xFF9A9D91), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun ObservationCard(o: Observation) {
        val accent = if (o.network == "meshcore") Color(0xFFC28C4C) else Color(0xFF9EAD69)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171A19)),
            border = BorderStroke(1.dp, Color(0xFF4B5049)),
            shape = MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = accent.copy(alpha = .15f), shape = MaterialTheme.shapes.medium) {
                    Text(if (o.network == "meshcore") "MC" else "MT",
                        color = accent, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(o.name, fontWeight = FontWeight.Bold)
                    Text("${o.network.uppercase()}  •  ${o.nodeId}",
                        color = Color(0xFF9A9D91), style = MaterialTheme.typography.labelSmall)
                    Text("${o.lat.formatCoord()}, ${o.lon.formatCoord()}  •  ${o.seen}",
                        color = Color(0xFFD7D8CF), style = MaterialTheme.typography.bodySmall)
                }
                Text("${o.rssi} dBm", color = if (o.rssi > -70) Color(0xFF83A56B) else Color(0xFFC28C4C),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    private fun Double.formatCoord(): String = "%.5f".format(Locale.US, this)
}
