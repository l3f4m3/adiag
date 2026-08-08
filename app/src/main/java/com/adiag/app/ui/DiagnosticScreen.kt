package com.adiag.app.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adiag.model.ConnectionState
import com.adiag.model.Dtc
import com.adiag.model.Severity

@Composable
fun DiagnosticScreen(vm: DiagnosticViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Text("ADiag", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${state.vehicle.make} ${state.vehicle.model} ${state.vehicle.year} ${state.vehicle.trim.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val c = state.connection) {
            is ConnectionState.Disconnected -> AdapterPicker(
                devices = state.nearbyAdapters,
                searching = state.searchingAdapters,
                onSearch = vm::findAdapters,
                onPick = vm::connect,
            )
            is ConnectionState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(c.stage)
            }
            is ConnectionState.Connected -> {
                Text("${c.adapter} · ${c.protocol}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::scan, enabled = !state.scanning) { Text("Escanear") }
                    OutlinedButton(onClick = vm::clear) { Text("Borrar codigos") }
                }
            }
            is ConnectionState.Failed -> {
                Text("No se pudo conectar: ${c.reason}", color = MaterialTheme.colorScheme.error)
                AdapterPicker(
                    devices = state.nearbyAdapters,
                    searching = state.searchingAdapters,
                    onSearch = vm::findAdapters,
                    onPick = vm::connect,
                )
            }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (state.scanning) CircularProgressIndicator()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.dtcs, key = { it.code }) { DtcCard(it) }
        }
    }
}

@Composable
private fun AdapterPicker(
    devices: List<BluetoothDevice>,
    searching: Boolean,
    onSearch: () -> Unit,
    onPick: (BluetoothDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Enciende el contacto del auto (switch en posicion II) antes de buscar: " +
                "el NexLink solo transmite con corriente en el puerto OBD2.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onSearch, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
            Text(if (searching) "Buscando..." else "Buscar adaptador")
        }
        if (searching) CircularProgressIndicator(Modifier.padding(top = 4.dp))
        if (devices.isEmpty() && !searching) {
            Text("Ningun adaptador encontrado todavia.", style = MaterialTheme.typography.bodySmall)
        }
        devices.forEach { d ->
            OutlinedButton(onClick = { onPick(d) }, modifier = Modifier.fillMaxWidth()) {
                Text(d.name ?: d.address)
            }
        }
    }
}

@Composable
private fun DtcCard(dtc: Dtc) {
    val accent = when (dtc.severity) {
        Severity.CRITICAL -> Color(0xFFE24B4A)
        Severity.WARNING -> Color(0xFFEF9F27)
        Severity.INFO -> Color(0xFF888780)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dtc.code, style = MaterialTheme.typography.titleMedium, color = accent)
                Text(dtc.status.name, style = MaterialTheme.typography.labelSmall)
                dtc.respondingEcu?.let { Text("ECU $it", style = MaterialTheme.typography.labelSmall) }
            }
            Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(dtc.system ?: "Sin clasificar")
                    append(" · fuente: ${dtc.source.name.lowercase()}")
                    if (dtc.needsReview) append(" · traduccion por revisar")
                },
                style = MaterialTheme.typography.labelSmall
            )
            if (dtc.needsReview) {
                dtc.originalEn?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
