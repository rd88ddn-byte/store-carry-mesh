package com.example.storecarrymesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var engine: StoreCarryForwardEngine
    private lateinit var transport: BluetoothMeshTransport
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        transport.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val nodeId = prefs.getString("node_id", null)
            ?: UUID.randomUUID().toString().take(8).also {
                prefs.edit().putString("node_id", it).apply()
            }

        engine = StoreCarryForwardEngine(MeshDiskStore(this), nodeId)
        val btManager = getSystemService(BluetoothManager::class.java)
        transport = BluetoothMeshTransport(
            btManager?.adapter,
            engine,
            lifecycleScope,
        ) { msg ->
            runOnUiThread { statusView.text = "Статус: $msg" }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val headerView = TextView(this).apply {
            text = "Вузол: $nodeId | Store & Carry Forward (BT)"
            textSize = 16f
        }
        statusView = TextView(this).apply {
            text = "Статус: ініціалізація..."
            setPadding(0, 12, 0, 16)
        }
        val input = EditText(this).apply {
            hint = "Введіть повідомлення для офлайн-сітки"
        }
        val sendBtn = Button(this).apply {
            text = "Зберегти у буфер та передавати"
            setOnClickListener {
                val txt = input.text.toString()
                if (txt.isNotBlank()) {
                    engine.createLocalMessage(txt)
                    input.text.clear()
                    transport.start()
                }
            }
        }
        logView = TextView(this).apply {
            setPadding(0, 16, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
        }

        root.addView(headerView)
        root.addView(statusView)
        root.addView(input)
        root.addView(sendBtn)
        root.addView(scroll)
        setContentView(root)

        lifecycleScope.launch {
            engine.messages.collect { list ->
                logView.text = if (list.isEmpty()) {
                    "Повідомлень у буфері ще немає."
                } else {
                    list.joinToString("\n\n") { packet ->
                        "[${packet.senderId} | hop=${packet.hopCount} | ttl=${packet.ttl}] ${packet.body}"
                    }
                }
            }
        }

        ensureBluetoothPermissions()
    }

    private fun ensureBluetoothPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            transport.start()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }
}
