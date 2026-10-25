/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.screens.hosteditor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.connectbot.R
import org.connectbot.transport.ble.BleStreamLink
import timber.log.Timber

/** A ble-term server seen while scanning. */
data class BleServer(val address: String, val name: String?, val rssi: Int)

private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasBlePermissions(context: Context): Boolean = blePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private sealed interface ScanState {
    data object NeedsPermission : ScanState

    data object Denied : ScanState

    data object Unavailable : ScanState

    data object Scanning : ScanState

    data class Failed(val code: Int) : ScanState
}

/**
 * Scans for servers advertising the ble-term GATT service and lets the user
 * pick one. Requests the Nearby devices (or location, before Android 12)
 * permission first.
 */
@Composable
fun BleScanDialog(
    onDismiss: () -> Unit,
    onSelect: (BleServer) -> Unit,
) {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf<ScanState>(if (hasBlePermissions(context)) ScanState.Scanning else ScanState.NeedsPermission)
    }
    val servers = remember { mutableStateListOf<BleServer>() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state = if (hasBlePermissions(context)) ScanState.Scanning else ScanState.Denied
    }

    LaunchedEffect(state) {
        if (state == ScanState.NeedsPermission) permissionLauncher.launch(blePermissions())
    }

    if (state == ScanState.Scanning) {
        DisposableEffect(Unit) {
            val stop = startScan(
                context,
                onResult = { server ->
                    val i = servers.indexOfFirst { it.address == server.address }
                    if (i >= 0) servers[i] = server else servers.add(server)
                },
                onError = { state = it },
            )
            onDispose { stop() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ble_scan_title)) },
        text = {
            Column {
                when (val s = state) {
                    ScanState.Denied -> Text(stringResource(R.string.ble_scan_permission_denied))

                    ScanState.Unavailable -> Text(stringResource(R.string.ble_scan_unavailable))

                    is ScanState.Failed -> Text(stringResource(R.string.ble_scan_failed, s.code))

                    ScanState.Scanning, ScanState.NeedsPermission -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                        if (servers.isEmpty()) Text(stringResource(R.string.ble_scan_searching))
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(servers, key = { it.address }) { server ->
                        ListItem(
                            headlineContent = { Text(server.name ?: server.address) },
                            supportingContent = { Text("${server.address}  ${server.rssi} dBm") },
                            modifier = Modifier.clickable { onSelect(server) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

/** Starts a filtered LE scan; returns a function that stops it. */
@SuppressLint("MissingPermission")
private fun startScan(
    context: Context,
    onResult: (BleServer) -> Unit,
    onError: (ScanState) -> Unit,
): () -> Unit {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    if (scanner == null) {
        onError(ScanState.Unavailable)
        return {}
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
            onResult(BleServer(result.device.address, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.w("BLE scan failed: %d", errorCode)
            onError(ScanState.Failed(errorCode))
        }
    }
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleStreamLink.SERVICE_UUID)).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try {
        scanner.startScan(filters, settings, callback)
    } catch (e: SecurityException) {
        onError(ScanState.Denied)
        return {}
    }
    return {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Timber.d(e, "stopScan failed")
        }
    }
}
