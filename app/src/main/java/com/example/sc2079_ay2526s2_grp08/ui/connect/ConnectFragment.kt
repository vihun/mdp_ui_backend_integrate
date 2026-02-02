package com.example.sc2079_ay2526s2_grp08.ui.connect

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sc2079_ay2526s2_grp08.MainViewModel
import com.example.sc2079_ay2526s2_grp08.R
import kotlinx.coroutines.launch

class ConnectFragment : Fragment(R.layout.fragment_connect) {

    private val vm: MainViewModel by activityViewModels()

    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button
    private lateinit var spDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSendTest: Button
    private lateinit var tvLog: TextView

    private var paired: List<BluetoothDevice> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            refreshPairedDevices()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tvStatus)
        btnRefresh = view.findViewById(R.id.btnRefreshPaired)
        spDevices = view.findViewById(R.id.spDevices)
        btnConnect = view.findViewById(R.id.btnConnect)
        btnDisconnect = view.findViewById(R.id.btnDisconnect)
        btnSendTest = view.findViewById(R.id.btnSendTest)
        tvLog = view.findViewById(R.id.tvLog)

        btnRefresh.setOnClickListener {
            ensureBtPermissionsThen { refreshPairedDevices() }
        }

        btnConnect.setOnClickListener {
            ensureBtPermissionsThen {
                val idx = spDevices.selectedItemPosition
                val device = paired.getOrNull(idx)
                if (device == null) {
                    toast("No device selected")
                    return@ensureBtPermissionsThen
                }
                vm.connectToDevice(device)
            }
        }

        btnDisconnect.setOnClickListener {
            vm.disconnectAndReturnToListening()
        }

        btnSendTest.setOnClickListener {
            // quick sanity test for C.1 (outgoing)
            vm.sendRaw("MOVE,F")
        }

        // Observe state (Status + Log)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    val statusLine = buildString {
                        append("Status: ")
                        append(s.conn.name)
                        s.statusText?.let {
                            append(" — ")
                            append(it)
                        }
                    }
                    tvStatus.text = statusLine

                    // Show last ~200 log lines to avoid UI lag
                    val logs = s.log.takeLast(200).joinToString("\n") { e ->
                        "[${e.kind}] ${e.text}"
                    }
                    tvLog.text = "Log:\n$logs"
                }
            }
        }

        // Initial load
        ensureBtPermissionsThen { refreshPairedDevices() }
    }

    private fun refreshPairedDevices() {
        if (!vm.isBluetoothSupported()) {
            toast("Bluetooth not supported on this device")
            return
        }
        if (!vm.isBluetoothEnabled()) {
            toast("Bluetooth is OFF — turn it on first")
            // We can still show paired list on some phones, but connect will fail
        }

        paired = try {
            vm.getPairedDevices()
        } catch (e: SecurityException) {
            // If user denied permissions
            emptyList()
        }

        val labels = if (paired.isEmpty()) {
            listOf("(no paired devices)")
        } else {
            paired.map { d -> "${d.name ?: "Unknown"}\n${d.address}" }
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spDevices.adapter = adapter
    }

    private fun ensureBtPermissionsThen(onGranted: () -> Unit) {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires BLUETOOTH_CONNECT for paired devices + connect
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }

        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
            return
        }

        onGranted()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
