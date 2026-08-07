package com.adiag.model

/** Estado de un codigo segun el modo OBD que lo devolvio. */
enum class DtcStatus { CONFIRMED, PENDING, PERMANENT }

enum class Severity { INFO, WARNING, CRITICAL }

/** De donde salio la descripcion. Se muestra en la UI para auditabilidad. */
enum class DefinitionSource { MANUFACTURER, CORPORATE_FAMILY, GENERIC, UNKNOWN }

data class Dtc(
    val code: String,
    val status: DtcStatus,
    val description: String,
    val source: DefinitionSource,
    val originalEn: String? = null,
    val needsReview: Boolean = false,
    val system: String? = null,
    val anchorId: String? = null,
    val severity: Severity = Severity.WARNING,
    val respondingEcu: String? = null,
)

data class Vehicle(
    val make: String,
    val model: String,
    val year: Int,
    val trim: String? = null,
    val vin: String? = null,
) {
    /** Cadena de resolucion de descripciones, de mas especifica a mas general. */
    val lookupChain: List<String>
        get() = when (make.uppercase()) {
            "FORD" -> listOf("FORD", "LINCOLN", "MERCURY", "GENERIC")
            "LINCOLN" -> listOf("LINCOLN", "FORD", "GENERIC")
            "CHEVROLET", "CHEVY" -> listOf("CHEVY", "BUICK", "GENERIC")
            else -> listOf(make.uppercase(), "GENERIC")
        }

    companion object {
        val DEFAULT = Vehicle("Ford", "Fiesta", 2016, "Titanium")
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val stage: String) : ConnectionState
    data class Connected(val protocol: String, val adapter: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
