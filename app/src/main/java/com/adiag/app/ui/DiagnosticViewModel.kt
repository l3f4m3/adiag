package com.adiag.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adiag.data.DtcRepository
import com.adiag.model.ConnectionState
import com.adiag.model.Dtc
import com.adiag.model.Vehicle
import com.adiag.obd.BleGattTransport
import com.adiag.obd.BondManager
import com.adiag.obd.ClassicSppTransport
import com.adiag.obd.DeviceScanner
import com.adiag.obd.ElmSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class UiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val vehicle: Vehicle = Vehicle.DEFAULT,
    val dtcs: List<Dtc> = emptyList(),
    val scanning: Boolean = false,
    val searchingAdapters: Boolean = false,
    val nearbyAdapters: List<BluetoothDevice> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
@SuppressLint("MissingPermission")
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DtcRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: ElmSession? = null
    private val scanner = DeviceScanner(context)

    /**
     * Busca adaptadores cercanos (emparejados o no). 12s de ventana: es lo
     * que tarda Android en completar un ciclo de discovery clasico.
     */
    fun findAdapters() = viewModelScope.launch {
        _state.value = _state.value.copy(searchingAdapters = true, nearbyAdapters = emptyList())
        val found = linkedMapOf<String, BluetoothDevice>()
        withTimeoutOrNull(12_000) {
            scanner.discover().collect { dev ->
                val n = dev.name?.uppercase().orEmpty()
                if (ADAPTER_HINTS.any { n.contains(it) }) {
                    found[dev.address] = dev
                    _state.value = _state.value.copy(nearbyAdapters = found.values.toList())
                }
            }
        }
        _state.value = _state.value.copy(searchingAdapters = false)
    }

    /**
     * Fuerza el emparejamiento con PIN inyectado (ver BondManager) antes de
     * abrir el socket. El NexLink no siempre completa el bonding iniciado
     * desde Ajustes del sistema, asi que este paso es obligatorio incluso si
     * el dispositivo ya aparece como "conocido" en Android.
     */
    fun connect(device: BluetoothDevice) = viewModelScope.launch {
        _state.value = _state.value.copy(connection = ConnectionState.Connecting("Emparejando"))
        val bond = BondManager.ensureBonded(context, device)
        if (bond.isFailure) {
            _state.value = _state.value.copy(
                connection = ConnectionState.Failed(
                    bond.exceptionOrNull()?.message ?: "No se pudo emparejar"
                )
            )
            return@launch
        }

        _state.value = _state.value.copy(connection = ConnectionState.Connecting("Enlazando"))
        var s = ElmSession(ClassicSppTransport(device))
        var result = s.open()
        if (result is ConnectionState.Failed) {
            s.close()
            _state.value = _state.value.copy(connection = ConnectionState.Connecting("Probando BLE"))
            s = ElmSession(BleGattTransport(context, device))
            result = s.open()
        }
        session = s.takeIf { result is ConnectionState.Connected }
        _state.value = _state.value.copy(connection = result)
    }

    fun scan() = viewModelScope.launch {
        val s = session ?: return@launch
        _state.value = _state.value.copy(scanning = true, message = null)
        val raw = s.readDtcs("03") + s.readDtcs("07") + s.readDtcs("0A")
        val vehicle = _state.value.vehicle
        val enriched = raw.distinctBy { it.code }.map { r ->
            val resolved = repo.describe(r.code, vehicle)
            val loc = repo.locate(r.code)
            Dtc(
                code = r.code,
                status = r.status,
                description = resolved.description,
                source = resolved.source,
                originalEn = resolved.originalEn,
                needsReview = resolved.needsReview,
                system = loc.system,
                anchorId = loc.anchor,
                severity = loc.severity,
                respondingEcu = r.ecu,
            )
        }.sortedByDescending { it.severity.ordinal }
        _state.value = _state.value.copy(
            scanning = false,
            dtcs = enriched,
            message = if (enriched.isEmpty()) "Sin codigos almacenados" else null,
        )
    }

    fun clear() = viewModelScope.launch {
        val s = session ?: return@launch
        val ok = s.clearDtcs()
        _state.value = _state.value.copy(
            dtcs = if (ok) emptyList() else _state.value.dtcs,
            message = if (ok) "Codigos borrados" else "El modulo rechazo el borrado",
        )
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }

    private companion object {
        val ADAPTER_HINTS = listOf("NEXAS", "NEXLINK", "NX230731", "OBD", "ELM327", "VLINK")
    }
}
