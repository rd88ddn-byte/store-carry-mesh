package com.example.storecarrymesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

val MESH_SERVICE_UUID: UUID =
    UUID.fromString("8a4d5b20-6c91-4e7a-9b34-12f5e6d7c8b9")
const val MESH_SERVICE_NAME = "StoreCarryMeshRFCOMM"

class MeshDiskStore(context: Context) {
    private val dir = File(context.filesDir, "mesh_store").apply { mkdirs() }
    private val outboxFile = File(dir, "outbox.json")
    private val seenFile = File(dir, "seen_ids.json")

    fun loadOutbox(): MutableList<MeshPacket> {
        if (!outboxFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(outboxFile.readText())
            val list = mutableListOf<MeshPacket>()
            for (i in 0 until arr.length()) {
                MeshPacket.fromJson(arr.getString(i))?.let { list.add(it) }
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveOutbox(packets: List<MeshPacket>) {
        val arr = JSONArray()
        packets.forEach { arr.put(it.toJson()) }
        outboxFile.writeText(arr.toString())
    }

    fun loadSeenIds(): MutableSet<String> {
        if (!seenFile.exists()) return mutableSetOf()
        return try {
            val arr = JSONArray(seenFile.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun saveSeenIds(ids: Set<String>) {
        val arr = JSONArray()
        ids.toList().takeLast(2000).forEach { arr.put(it) }
        seenFile.writeText(arr.toString())
    }
}

class StoreCarryForwardEngine(
    private val store: MeshDiskStore,
    private val nodeId: String,
) {
    private val lock = Any()
    private val seenIds: MutableSet<String> = store.loadSeenIds()
    private val outbox: MutableList<MeshPacket> = store.loadOutbox()

    private val _messages = MutableStateFlow<List<MeshPacket>>(outbox.toList())
    val messages: StateFlow<List<MeshPacket>> = _messages.asStateFlow()

    fun createLocalMessage(body: String): MeshPacket {
        val trimmed = body.trim()
        val packet = MeshPacket(senderId = nodeId, body = trimmed)
        synchronized(lock) {
            seenIds.add(packet.id)
            outbox.add(packet)
            store.saveSeenIds(seenIds)
            store.saveOutbox(outbox)
            _messages.value = outbox.toList()
        }
        return packet
    }

    fun ingestRemotePacket(incoming: MeshPacket): Boolean = synchronized(lock) {
        if (!seenIds.add(incoming.id)) return false
        store.saveSeenIds(seenIds)
        if (incoming.ttl > 0) {
            outbox.add(incoming.forwardedCopy())
            store.saveOutbox(outbox)
            _messages.value = outbox.toList()
        }
        true
    }

    fun pendingOutboxSnapshot(): List<MeshPacket> = synchronized(lock) {
        outbox.toList()
    }
}

class BluetoothMeshTransport(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val engine: StoreCarryForwardEngine,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    private var serverJob: Job? = null
    private var syncJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            onStatus("Bluetooth недоступний на пристрої")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Увімкніть Bluetooth для обміну даними")
            return
        }

        serverJob?.cancel()
        syncJob?.cancel()

        serverJob = scope.launch(Dispatchers.IO) {
            onStatus("Очікування вхідних з'єднань RFCOMM...")
            while (isActive) {
                try {
                    val srv = adapter.listenUsingRfcommWithServiceRecord(
                        MESH_SERVICE_NAME,
                        MESH_SERVICE_UUID,
                    )
                    serverSocket = srv
                    val socket = srv.accept()
                    srv.close()
                    handleSocket(socket)
                } catch (_: Exception) {
                    try {
                        serverSocket?.close()
                    } catch (_: Exception) {
                    }
                    delay(1500)
                }
            }
        }

        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bonded: Set<BluetoothDevice> = try {
                    adapter.bondedDevices ?: emptySet()
                } catch (_: SecurityException) {
                    onStatus("Немає дозволу BLUETOOTH_CONNECT")
                    emptySet()
                }
                for (device in bonded) {
                    if (!isActive) break
                    trySyncWithDevice(device)
                }
                delay(8000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun trySyncWithDevice(device: BluetoothDevice) {
        val packets = engine.pendingOutboxSnapshot()
        if (packets.isEmpty()) return
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(MESH_SERVICE_UUID)
            socket.connect()
            val writer =
                BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            for (pkt in packets) {
                writer.write(pkt.toJson())
                writer.newLine()
            }
            writer.flush()
            onStatus("Передано пакетів до ${device.name ?: device.address}: ${packets.size}")
        } catch (_: Exception) {
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleSocket(socket: BluetoothSocket) {
        try {
            socket.use { sock ->
                val reader =
                    BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
                var addedCount = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    val pkt = MeshPacket.fromJson(line) ?: continue
                    if (engine.ingestRemotePacket(pkt)) {
                        addedCount++
                    }
                }
                if (addedCount > 0) {
                    onStatus("Отримано нових пакетів: $addedCount")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        serverJob?.cancel()
        syncJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}
