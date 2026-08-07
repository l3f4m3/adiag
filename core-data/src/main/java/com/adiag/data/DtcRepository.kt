package com.adiag.data

import com.adiag.model.DefinitionSource
import com.adiag.model.Dtc
import com.adiag.model.Severity
import com.adiag.model.Vehicle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DtcRepository @Inject constructor(private val dao: DtcDao) {

    /**
     * Resuelve la descripcion en cascada: fabricante exacto -> familia
     * corporativa -> generico. Se conserva el nivel que resolvio y el texto en
     * ingles, para que la UI pueda mostrar ambos.
     */
    suspend fun describe(code: String, vehicle: Vehicle): Resolved {
        val rows = dao.definitionsFor(code, listOf("es", "en"))
        if (rows.isEmpty()) {
            return Resolved(code, DefinitionSource.UNKNOWN, null, needsReview = true)
        }
        val chain = vehicle.lookupChain
        val best = chain.firstNotNullOfOrNull { mfr ->
            rows.filter { it.manufacturer == mfr }.takeIf { it.isNotEmpty() }
                ?.let { mfr to it }
        } ?: return Resolved(code, DefinitionSource.UNKNOWN, null, needsReview = true)

        val (mfr, group) = best
        val es = group.firstOrNull { it.locale == "es" }
        val en = group.firstOrNull { it.locale == "en" }
        val source = when {
            mfr == "GENERIC" -> DefinitionSource.GENERIC
            mfr == vehicle.make.uppercase() -> DefinitionSource.MANUFACTURER
            else -> DefinitionSource.CORPORATE_FAMILY
        }
        val text = es?.description ?: en?.description ?: code
        val review = text.endsWith("[rev]")
        return Resolved(
            description = text.removeSuffix("[rev]").trim(),
            source = source,
            originalEn = en?.description,
            needsReview = review,
        )
    }

    /** Sistema y ancla 3D. Reglas de mas especifica a mas general. */
    fun locate(code: String): Location {
        val rules = LOCATION_RULES.firstOrNull { code.matches(it.pattern) }
            ?: return Location("Sin clasificar", "vehicle_center", Severity.WARNING)
        val anchor = when {
            rules.anchor == "engine_cyl" -> "engine_cyl_${code.takeLast(1)}"
            else -> rules.anchor
        }
        return rules.copy(anchor = anchor)
    }

    data class Resolved(
        val description: String,
        val source: DefinitionSource,
        val originalEn: String?,
        val needsReview: Boolean,
    )

    data class Location(
        val system: String,
        val anchor: String,
        val severity: Severity,
        val pattern: Regex = Regex(""),
    )

    private companion object {
        val LOCATION_RULES = listOf(
            Location("Encendido / fallo de chispa", "engine_cyl", Severity.CRITICAL, Regex("^P030[1-9]$")),
            Location("Encendido / fallo de chispa", "engine_bay", Severity.CRITICAL, Regex("^P0300$")),
            Location("Combustible y aire", "engine_bay", Severity.WARNING, Regex("^P0(0|1|2)\\d{2}$")),
            Location("Catalizador", "exhaust_cat", Severity.WARNING, Regex("^P04[23]\\d$")),
            Location("EVAP", "evap_canister", Severity.INFO, Regex("^P04[45]\\d$")),
            Location("Sensores de oxigeno", "exhaust_o2", Severity.WARNING, Regex("^P01[3-5]\\d$")),
            Location("EGR", "egr_valve", Severity.WARNING, Regex("^P040[0-9]$")),
            Location("Transmision", "transmission", Severity.WARNING, Regex("^P0[7-8]\\d{2}$")),
            Location("Frenos / ABS", "abs_module", Severity.CRITICAL, Regex("^C1\\d{3}$")),
            Location("Carroceria", "cabin_front", Severity.INFO, Regex("^B\\d{4}$")),
            Location("Red CAN", "bus_can", Severity.CRITICAL, Regex("^U0\\d{3}$")),
        )
    }
}
