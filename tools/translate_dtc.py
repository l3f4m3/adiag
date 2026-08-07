"""Traduce descripciones DTC de ingles a espanol con un glosario cerrado.

Las descripciones SAE J2012 usan vocabulario tecnico restringido (~1100 tokens
unicos para casi 10.000 codigos), asi que una traduccion por frases + tokens es
determinista y auditable: no hay alucinacion posible, y cada descripcion reporta
que porcentaje de sus tokens quedo cubierto por el glosario.
"""
import re
import sqlite3
import sys
from pathlib import Path

# --- Frases multi-palabra. Se aplican primero, mas largas primero. -----------
# Aqui es donde se resuelve el orden de palabras del espanol.
PHRASES = {
    "engine coolant temperature": "temperatura del refrigerante del motor",
    "intake air temperature": "temperatura del aire de admision",
    "mass or volume air flow": "flujo masico o volumetrico de aire",
    "manifold absolute pressure": "presion absoluta del multiple",
    "barometric pressure": "presion barometrica",
    "throttle position": "posicion del acelerador",
    "accelerator pedal position": "posicion del pedal del acelerador",
    "crankshaft position": "posicion del cigüenal",
    "camshaft position": "posicion del arbol de levas",
    "vehicle speed": "velocidad del vehiculo",
    "wheel speed": "velocidad de rueda",
    "engine speed": "regimen del motor",
    "fuel rail pressure": "presion del riel de combustible",
    "fuel tank": "tanque de combustible",
    "fuel level": "nivel de combustible",
    "fuel pump": "bomba de combustible",
    "fuel injector": "inyector de combustible",
    "fuel trim": "ajuste de combustible",
    "air fuel ratio": "relacion aire-combustible",
    "air-fuel ratio": "relacion aire-combustible",
    "oxygen sensor": "sensor de oxigeno",
    "heated oxygen sensor": "sensor de oxigeno calefactado",
    "catalyst system": "sistema del catalizador",
    "catalytic converter": "convertidor catalitico",
    "exhaust gas recirculation": "recirculacion de gases de escape",
    "evaporative emission": "emisiones evaporativas",
    "positive crankcase ventilation": "ventilacion positiva del carter",
    "secondary air injection": "inyeccion de aire secundario",
    "idle air control": "control de aire en ralenti",
    "idle speed control": "control de velocidad de ralenti",
    "cooling fan": "ventilador de enfriamiento",
    "coolant thermostat": "termostato del refrigerante",
    "engine oil temperature": "temperatura del aceite del motor",
    "engine oil pressure": "presion del aceite del motor",
    "ignition coil": "bobina de encendido",
    "glow plug": "bujia incandescente",
    "spark plug": "bujia",
    "knock sensor": "sensor de detonacion",
    "misfire detected": "fallo de encendido detectado",
    "random/multiple cylinder misfire": "fallo de encendido aleatorio o en multiples cilindros",
    "cylinder misfire": "fallo de encendido en cilindro",
    "torque converter": "convertidor de par",
    "transmission fluid": "fluido de transmision",
    "transmission control": "control de transmision",
    "transmission range": "rango de transmision",
    "gear ratio": "relacion de marcha",
    "shift solenoid": "solenoide de cambio",
    "clutch position": "posicion del embrague",
    "transfer case": "caja de transferencia",
    "brake switch": "interruptor del freno",
    "brake pedal position": "posicion del pedal del freno",
    "brake fluid": "liquido de frenos",
    "brake booster": "servofreno",
    "steering angle": "angulo de direccion",
    "steering wheel": "volante",
    "power steering": "direccion asistida",
    "tire pressure": "presion de neumaticos",
    "battery voltage": "tension de bateria",
    "battery temperature": "temperatura de la bateria",
    "battery pack": "paquete de baterias",
    "hybrid/ev battery": "bateria del sistema hibrido/electrico",
    "hybrid/ev": "hibrido/electrico",
    "control module": "modulo de control",
    "control unit": "unidad de control",
    "powertrain control module": "modulo de control del tren motriz",
    "body control module": "modulo de control de carroceria",
    "engine control module": "modulo de control del motor",
    "internal control module": "modulo de control interno",
    "lost communication with": "comunicacion perdida con",
    "lost communication": "comunicacion perdida",
    "invalid data received from": "datos invalidos recibidos de",
    "invalid data received": "datos invalidos recibidos",
    "no communication": "sin comunicacion",
    "bus off": "bus desconectado",
    "can communication bus": "bus de comunicacion CAN",
    "communication bus": "bus de comunicacion",
    "serial communication": "comunicacion serial",
    "range/performance problem": "problema de rango o rendimiento",
    "range/performance": "rango o rendimiento",
    "circuit range/performance": "rango o rendimiento del circuito",
    "circuit/open": "circuito abierto",
    "circuit low": "circuito en bajo",
    "circuit high": "circuito en alto",
    "circuit open": "circuito abierto",
    "circuit short to ground": "circuito en corto a masa",
    "circuit short to battery": "circuito en corto a bateria",
    "circuit intermittent": "circuito intermitente",
    "intermittent/erratic": "intermitente o erratico",
    "open circuit": "circuito abierto",
    "short to ground": "corto a masa",
    "short to battery": "corto a bateria",
    "short circuit": "cortocircuito",
    "signal stuck low": "senal atascada en bajo",
    "signal stuck high": "senal atascada en alto",
    "stuck open": "atascado abierto",
    "stuck closed": "atascado cerrado",
    "stuck off": "atascado apagado",
    "stuck on": "atascado encendido",
    "biased/stuck": "desviado o atascado",
    "performance/stuck": "rendimiento o atascado",
    "system too lean": "mezcla demasiado pobre",
    "system too rich": "mezcla demasiado rica",
    "too lean": "demasiado pobre",
    "too rich": "demasiado rica",
    "too low": "demasiado bajo",
    "too high": "demasiado alto",
    "below threshold": "por debajo del umbral",
    "above threshold": "por encima del umbral",
    "efficiency below threshold": "eficiencia por debajo del umbral",
    "malfunction indicator lamp": "testigo de averia",
    "check engine light": "testigo de motor",
    "not learned": "no aprendido",
    "not programmed": "no programado",
    "not plausible": "no plausible",
    "not detected": "no detectado",
    "signal missing": "senal ausente",
    "software incompatibility": "incompatibilidad de software",
    "incorrect response": "respuesta incorrecta",
    "correlation error": "error de correlacion",
    "implausible signal": "senal inverosimil",
    "over temperature": "sobretemperatura",
    "cold start": "arranque en frio",
    "closed loop": "lazo cerrado",
    "open loop": "lazo abierto",
    "purge control valve": "valvula de control de purga",
    "purge valve": "valvula de purga",
    "vent valve": "valvula de venteo",
    "leak detected": "fuga detectada",
    "small leak": "fuga pequena",
    "gross leak": "fuga grande",
    "very small leak": "fuga muy pequena",
    "intake manifold": "multiple de admision",
    "exhaust manifold": "multiple de escape",
    "turbocharger/supercharger": "turbocompresor o sobrealimentador",
    "boost pressure": "presion de sobrealimentacion",
    "diesel particulate filter": "filtro de particulas diesel",
    "particulate matter": "material particulado",
    "reductant injection": "inyeccion de reductor",
    "air conditioning": "aire acondicionado",
    "a/c refrigerant": "refrigerante de A/C",
    "front left": "delantero izquierdo",
    "front right": "delantero derecho",
    "rear left": "trasero izquierdo",
    "rear right": "trasero derecho",
    "left front": "delantero izquierdo",
    "right front": "delantero derecho",
    "left rear": "trasero izquierdo",
    "right rear": "trasero derecho",
    "driver side": "lado del conductor",
    "passenger side": "lado del pasajero",
    "seat belt": "cinturon de seguridad",
    "occupant classification": "clasificacion del ocupante",
    "restraints control": "control de sujecion",
    "supplemental restraint": "sujecion suplementaria",
    "self-test": "autodiagnostico",
    "keep alive memory": "memoria persistente",
    "ignition switch": "interruptor de encendido",
    "starter motor": "motor de arranque",
    "charging system": "sistema de carga",
    "electric/auxiliary": "electrica/auxiliar",
    "drive motor": "motor de traccion",
    "power inverter": "inversor de potencia",
    "dc/dc converter": "convertidor CC/CC",
    "dc/ac converter": "convertidor CC/CA",
    "high voltage": "alta tension",
    "low voltage": "baja tension",
    "isolation fault": "fallo de aislamiento",
    "state of charge": "estado de carga",
    "coolant level": "nivel de refrigerante",
    "oil level": "nivel de aceite",
    "sensor/switch": "sensor o interruptor",
    "aerodynamic feature": "elemento aerodinamico",
    "grille shutter": "persiana de parrilla",
    "obd system readiness test not complete": "prueba de disponibilidad del sistema OBD incompleta",
    "koer test cannot be completed": "prueba KOER no se puede completar",
    "koeo test cannot be completed": "prueba KOEO no se puede completar",
}

# --- Tokens sueltos ----------------------------------------------------------
TOKENS = {
    "circuit": "circuito", "sensor": "sensor", "control": "control", "high": "alto",
    "low": "bajo", "bank": "banco", "module": "modulo", "battery": "bateria",
    "position": "posicion", "temperature": "temperatura", "pressure": "presion",
    "cylinder": "cilindro", "fuel": "combustible", "voltage": "tension",
    "valve": "valvula", "communication": "comunicacion", "system": "sistema",
    "with": "con", "performance": "rendimiento", "motor": "motor", "lost": "perdida",
    "coolant": "refrigerante", "actuator": "actuador", "pump": "bomba", "air": "aire",
    "current": "corriente", "data": "datos", "from": "de", "solenoid": "solenoide",
    "invalid": "invalidos", "received": "recibidos", "exhaust": "escape",
    "transmission": "transmision", "engine": "motor", "drive": "traccion",
    "stuck": "atascado", "too": "demasiado", "shift": "cambio", "injector": "inyector",
    "sense": "deteccion", "brake": "freno", "heater": "calefactor",
    "switch": "interruptor", "camshaft": "arbol de levas", "reductant": "reductor",
    "speed": "velocidad", "intake": "admision", "clutch": "embrague",
    "pack": "paquete", "supply": "alimentacion", "alternative": "alternativo",
    "charger": "cargador", "signal": "senal", "power": "potencia",
    "injection": "inyeccion", "intermittent": "intermitente", "input": "entrada",
    "phase": "fase", "bypass": "derivacion", "fluid": "fluido", "flow": "flujo",
    "off": "apagado", "gear": "marcha", "open": "abierto", "range": "rango",
    "vehicle": "vehiculo", "converter": "convertidor", "bus": "bus",
    "generator": "generador", "wheel": "rueda", "on": "encendido", "charge": "carga",
    "charging": "carga", "tank": "tanque", "cooler": "enfriador", "at": "en",
    "glow": "incandescente", "seat": "asiento", "start": "arranque",
    "driver": "conductor", "plug": "bujia", "inverter": "inversor", "evap": "EVAP",
    "timing": "sincronizacion", "door": "puerta", "throttle": "acelerador",
    "rear": "trasero", "level": "nivel", "output": "salida", "closed": "cerrado",
    "cold": "frio", "over": "sobre", "detected": "detectado",
    "contactor": "contactor", "manifold": "multiple", "fan": "ventilador",
    "particulate": "particulas", "gas": "gas", "not": "no", "catalyst": "catalizador",
    "electronics": "electronica", "left": "izquierdo", "right": "derecho",
    "boost": "sobrealimentacion", "learning": "aprendizaje",
    "incompatibility": "incompatibilidad", "compressor": "compresor",
    "software": "software", "of": "de", "profile": "perfil",
    "deployment": "despliegue", "relay": "rele", "front": "delantero",
    "interlock": "enclavamiento", "mode": "modo", "booster": "servo",
    "internal": "interno", "turbocharger": "turbocompresor", "pedal": "pedal",
    "oil": "aceite", "incorrect": "incorrecto", "filter": "filtro", "coil": "bobina",
    "regulator": "regulador", "cell": "celda", "leak": "fuga", "park": "estacionamiento",
    "malfunction": "averia", "refrigerant": "refrigerante", "unit": "unidad",
    "ratio": "relacion", "reference": "referencia", "cooling": "enfriamiento",
    "steering": "direccion", "belt": "correa", "interface": "interfaz",
    "aerodynamic": "aerodinamico", "feature": "elemento", "torque": "par",
    "select": "seleccion", "negative": "negativo", "side": "lado",
    "hydraulic": "hidraulico", "in": "en", "lean": "pobre", "outlet": "salida",
    "inlet": "entrada", "offset": "desviacion", "restraints": "sujecion",
    "balancing": "balanceo", "rich": "rica", "shaft": "eje", "row": "fila",
    "ion": "ion", "fork": "horquilla", "detection": "deteccion", "or": "o",
    "positive": "positivo", "exceeded": "excedido", "trim": "ajuste",
    "grille": "parrilla", "shutter": "persiana", "fault": "fallo",
    "coupler": "acoplador", "out": "fuera", "error": "error", "primary": "primario",
    "purge": "purga", "request": "solicitud", "aftertreatment": "postratamiento",
    "lock": "bloqueo", "indicator": "testigo", "failure": "fallo",
    "runner": "conducto", "response": "respuesta", "rocker": "balancin",
    "arm": "brazo", "short": "corto", "idle": "ralenti", "vacuum": "vacio",
    "lever": "palanca", "learned": "aprendido", "abs": "ABS", "cruise": "crucero",
    "threshold": "umbral", "element": "elemento", "imbalance": "desbalance",
    "metering": "dosificacion", "disconnect": "desconexion", "time": "tiempo",
    "rail": "riel", "friction": "friccion", "calibration": "calibracion",
    "vibration": "vibracion", "pulse": "pulso", "diesel": "diesel",
    "differential": "diferencial", "transfer": "transferencia",
    "feedback": "retroalimentacion", "slow": "lento", "pilot": "piloto",
    "wastegate": "valvula de descarga", "monitor": "monitor",
    "distribution": "distribucion", "max": "maximo", "min": "minimo",
    "electrical": "electrico", "secondary": "secundario", "volume": "volumen",
    "shutoff": "corte", "processing": "procesamiento", "case": "caja",
    "starter": "arranque", "port": "puerto", "image": "imagen", "below": "por debajo de",
    "during": "durante", "misfire": "fallo de encendido", "can": "CAN",
    "pretensioner": "pretensor", "pumping": "bombeo", "than": "que",
    "second": "segundo", "washer": "lavaparabrisas", "incompatible": "incompatible",
    "forced": "forzado", "switching": "conmutacion", "volt": "voltio",
    "passenger": "pasajero", "variation": "variacion", "enable": "habilitacion",
    "group": "grupo", "expected": "esperado", "load": "carga", "and": "y",
    "no": "sin", "barometric": "barometrica", "center": "central",
    "third": "tercero", "fill": "llenado", "matter": "material", "lamp": "testigo",
    "variable": "variable", "neutral": "neutro", "efficiency": "eficiencia",
    "stop": "parada", "overspeed": "sobrevelocidad", "crankcase": "carter",
    "intermediate": "intermedio", "shorted": "en corto", "unable": "incapaz",
    "disable": "deshabilitacion", "frontal": "frontal", "occupant": "ocupante",
    "acceleration": "aceleracion", "tuning": "sintonizacion", "mass": "masico",
    "higher": "mayor", "regeneration": "regeneracion", "crankshaft": "cigüenal",
    "loop": "lazo", "underspeed": "subvelocidad", "management": "gestion",
    "gate": "compuerta", "precharge": "precarga", "excessive": "excesivo",
    "mechanical": "mecanico", "camera": "camara", "status": "estado",
    "mil": "testigo de averia", "absolute": "absoluta", "stage": "etapa",
    "processor": "procesador", "shutdown": "apagado", "effort": "esfuerzo",
    "heat": "calor", "exchanger": "intercambiador", "body": "cuerpo",
    "lighting": "iluminacion", "head": "culata", "turbine": "turbina",
    "deterioration": "deterioro", "heated": "calefactado", "ground": "masa",
    "electronic": "electronico", "vent": "venteo", "quantity": "cantidad",
    "vaporizer": "vaporizador", "hydrogen": "hidrogeno", "condition": "condicion",
    "above": "por encima de", "insufficient": "insuficiente", "inductive": "inductivo",
    "unlock": "desbloqueo", "quality": "calidad", "missing": "ausente",
    "piston": "piston", "ventilation": "ventilacion", "accessory": "accesorio",
    "digital": "digital", "water": "agua", "memory": "memoria",
    "restricted": "restringido", "reverse": "reversa",
    "classification": "clasificacion", "requested": "solicitado",
    "delayed": "retardado", "auto": "automatico", "auxiliary": "auxiliar",
    "overtemperature": "sobretemperatura", "selector": "selector", "lack": "falta",
    "angle": "angulo", "reduction": "reduccion", "reservoir": "deposito",
    "illumination": "iluminacion", "engage": "acoplamiento", "limits": "limites",
    "window": "ventana", "lower": "menor", "oxygen": "oxigeno", "manual": "manual",
    "ambient": "ambiente", "radiator": "radiador", "balance": "balance",
    "combustion": "combustion", "pcv": "PCV", "serial": "serial",
    "energy": "energia", "direct": "directo", "ozone": "ozono", "line": "linea",
    "pcm": "PCM", "engagement": "acoplamiento", "resistance": "resistencia",
    "multiple": "multiples", "vapor": "vapor", "retarded": "retrasado",
    "advanced": "adelantado", "leaking": "con fuga", "cap": "tapon",
    "display": "pantalla", "down": "abajo", "four": "cuatro",
    "isolation": "aislamiento", "adjustment": "ajuste", "compression": "compresion",
    "stroke": "carrera", "audio": "audio", "radar": "radar", "test": "prueba",
    "restriction": "restriccion", "selection": "seleccion", "failed": "fallido",
    "long": "largo", "direction": "direccion", "for": "para",
    "component": "componente", "master": "maestro", "thermostat": "termostato",
    "enter": "entrada", "distance": "distancia", "inhibit": "inhibicion",
    "disengagement": "desacoplamiento", "signals": "senales",
    "indicates": "indica", "cam": "leva", "traction": "traccion",
    "limiter": "limitador", "monitoring": "monitoreo", "driving": "conduccion",
    "resolution": "resolucion", "unstable": "inestable", "up": "arriba",
    "pawl": "trinquete", "proximity": "proximidad", "sound": "sonido",
    "gateway": "pasarela", "anode": "anodo", "adaptive": "adaptativo",
    "rpm": "RPM", "hose": "manguera", "slip": "deslizamiento", "alert": "alerta",
    "installed": "instalado", "small": "pequeno", "single": "unico",
    "assist": "asistencia", "excitation": "excitacion", "lift": "elevacion",
    "medium": "medio", "upstream": "aguas arriba", "evaporative": "evaporativo",
    "emission": "emisiones", "programmed": "programado", "powertrain": "tren motriz",
    "inflatable": "inflable", "mirror": "espejo", "expansion": "expansion",
    "plausible": "plausible", "dam": "deflector", "warning": "advertencia",
    "swapped": "intercambiado", "limited": "limitado", "measurement": "medicion",
    "activity": "actividad", "pulses": "pulsos", "discharge": "descarga",
    "locked": "bloqueado", "apply": "aplicacion", "thickness": "espesor",
    "automated": "automatizado", "headlamp": "faro", "disc": "disco",
    "entertainment": "entretenimiento", "obstacle": "obstaculo",
    "special": "especial", "purpose": "proposito", "alternator": "alternador",
    "is": "esta", "downstream": "aguas abajo", "loss": "perdida",
    "conditioning": "acondicionado", "parking": "estacionamiento",
    "return": "retorno", "tire": "neumatico", "problems": "problemas",
    "downshift": "reduccion de marcha", "contact": "contacto", "plate": "placa",
    "restraint": "sujecion", "pedestrian": "peaton", "manufacturer": "fabricante",
    "controlled": "controlado", "dtc": "codigo de falla", "engaged": "acoplado",
    "relief": "alivio", "the": "el", "a/c": "A/C", "egr": "EGR", "nox": "NOx",
    "scr": "SCR", "ecm/pcm": "ECM/PCM", "tcm": "TCM", "imt": "IMT", "pto": "toma de fuerza",
    "wd/awd": "traccion total", "wd": "traccion", "pds": "PDS", "ho": "HO",
    "self": "auto", "ignition": "encendido", "limit": "limite", "active": "activo",
    "correlation": "correlacion", "to": "a", "sistema": "sistema",
}

# Letras de designacion SAE ("Sensor A", "Banco B") que van en mayuscula.
KEEP = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n",
        "p", "r", "t", "u", "v", "w", "x", "y", "z", "nh", "koer", "koeo"}

# Siglas que nunca se traducen y siempre van en mayuscula.
ACRONYMS = {"obd", "obdii", "evap", "egr", "abs", "can", "pcm", "ecm", "tcm",
            "bcm", "srs", "tpms", "nox", "scr", "dpf", "maf", "map", "iat",
            "ect", "vvt", "pcv", "cvt", "hvac", "lin", "rpm", "mil", "vin",
            "dtc", "imt", "pds", "ho", "pto", "sae", "gps", "a/c", "dc", "ac"}

# Sigla + sustantivo en ingles va al reves en espanol: "EVAP sistema" -> "sistema EVAP".
SWAP_RE = re.compile(
    r"\b(OBD|OBDII|EVAP|EGR|ABS|CAN|PCM|ECM|TCM|BCM|SRS|TPMS|SCR|DPF|MAF|MAP|PCV|VVT|A/C) "
    r"(sistema|circuito|sensor|valvula|modulo|bomba|monitor|bus|fuga|senal|prueba|testigo)\b")


# --- Segunda pasada: tokens detectados como no cubiertos en la primera corrida.
TOKENS.update({
    "regulating": "de regulacion", "large": "grande", "random": "aleatorio",
    "o2": "O2", "signature": "firma", "idm": "IDM", "multi-function": "multifuncion",
    "input/turbine": "entrada o turbina", "multi-axis": "multieje",
    "kickdown": "reduccion forzada", "wake-up": "despertar",
    "stuck/stalled": "atascado o detenido", "stuck/open": "atascado abierto",
    "shifted": "desplazado", "diagnostic": "diagnostico", "dual": "doble",
    "rotor": "rotor", "lpds": "LPDS", "command": "comando",
    "available": "disponible", "clockwise": "horario",
    "contribution/balance": "contribucion o balance", "key": "llave",
    "first": "primero", "reversed": "invertido", "sensors": "sensores",
    "disabled": "deshabilitado", "between": "entre", "link": "enlace",
    "solenoid/actuator": "solenoide o actuador", "flow/pressure": "flujo o presion",
    "on/start": "encendido o arranque", "but": "pero", "demand": "demanda",
    "faulty": "defectuoso", "fans": "ventiladores", "normal": "normal",
    "improper": "inadecuado", "delivery": "entrega",
    "recirculation": "recirculacion", "movement": "movimiento",
    "connection": "conexion", "present": "presente",
    "over-advanced": "excesivamente adelantada",
    "over-retarded": "excesivamente retrasada", "stop-start": "arranque-parada",
    "throttle/fuel": "acelerador o combustible",
    "performance/too": "rendimiento o demasiado", "exceedence": "exceso",
    "player/changer": "reproductor o cambiador", "change": "cambio",
    "activated": "activado", "pull": "tiro", "bleed": "purga",
    "check": "verificacion", "coast": "inercia", "immobilizer": "inmovilizador",
    "fuse": "fusible", "adsorber": "adsorbedor", "maximum": "maximo",
    "deceleration": "desaceleracion", "sample": "muestra",
    "lock/pawl": "bloqueo o trinquete", "isolation/voltage": "aislamiento o tension",
    "stabilization": "estabilizacion", "remote": "remoto", "column": "columna",
    "airbag": "bolsa de aire", "cleaning": "limpieza", "blower": "soplador",
    "knock/combustion": "detonacion o combustion",
    "current/temperature": "corriente o temperatura",
    "cam/rotor/injector": "leva, rotor o inyector",
    "starter/generator": "arranque/generador", "a/b": "A/B", "u-v-w": "U-V-W",
    "deactivation/intake": "desactivacion o admision",
    "air-fuel": "aire-combustible", "s": "s",
})

TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z'/-]*")


def _apply_phrases(text: str) -> str:
    for src in sorted(PHRASES, key=len, reverse=True):
        text = re.sub(r"\b" + re.escape(src) + r"\b", PHRASES[src], text, flags=re.I)
    return text


def translate(desc: str):
    """Devuelve (traduccion, cobertura 0..1)."""
    out = _apply_phrases(desc.lower())
    total = hit = 0
    spanish_values = set()
    for v in list(PHRASES.values()) + list(TOKENS.values()):
        spanish_values.update(v.lower().split())

    def repl(m):
        nonlocal total, hit
        w = m.group(0).lower()
        if w in ACRONYMS:
            return w.upper()
        if w in KEEP:
            return m.group(0).upper()
        total += 1
        if w in TOKENS:
            hit += 1
            return TOKENS[w]
        if w in spanish_values:
            hit += 1
            return w
        return m.group(0)

    out = TOKEN_RE.sub(repl, out)
    out = SWAP_RE.sub(lambda m: f"{m.group(2)} {m.group(1)}", out)
    out = re.sub(r"\bde el\b", "del", out)
    out = re.sub(r"\(S\)", "(s)", out)
    out = re.sub(r"\bel el\b", "el", out)
    out = re.sub(r"\bde los el\b", "de los", out)
    out = re.sub(r"\s+", " ", out).strip()
    out = out[:1].upper() + out[1:] if out else out
    return out, (hit / total if total else 1.0)


def main(src_db: str, out_db: str):
    Path(out_db).write_bytes(Path(src_db).read_bytes())
    con = sqlite3.connect(out_db)
    con.execute("PRAGMA journal_mode=DELETE")
    rows = con.execute(
        "SELECT code, manufacturer, description, type, is_generic, source_file "
        "FROM dtc_definitions WHERE locale='en' AND manufacturer IN "
        "('GENERIC','FORD','LINCOLN','MERCURY')"
    ).fetchall()

    payload, full, partial = [], 0, 0
    for code, mfr, desc, typ, gen, srcf in rows:
        es, cov = translate(desc)
        if cov >= 0.999:
            full += 1
        else:
            partial += 1
            es = es + "  [rev]"
        payload.append((code, mfr, es, typ, "es", gen, srcf))

    con.executemany(
        "INSERT OR REPLACE INTO dtc_definitions "
        "(code, manufacturer, description, type, locale, is_generic, source_file) "
        "VALUES (?,?,?,?,?,?,?)", payload)
    con.execute("CREATE INDEX IF NOT EXISTS idx_dtc_lookup "
                "ON dtc_definitions(code, locale)")
    con.commit()
    con.execute("VACUUM")
    con.close()

    tot = full + partial
    print(f"traducidas: {tot}")
    print(f"  cobertura total  : {full} ({full/tot:.1%})")
    print(f"  requieren revision: {partial} ({partial/tot:.1%})  -> marcadas [rev]")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
