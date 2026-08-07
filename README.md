# ADiag

App Android de diagnostico OBD2 con base de codigos en español y ubicacion de la
falla sobre el vehiculo. Pensada para el adaptador NEXAS NexLink (NX230731) y,
por defecto, para un Ford Fiesta 2016 Titanium.

Estado: **Fase 1 + 2** — persistencia, sincronizacion y capa Bluetooth/ELM327
completas, con una UI minima que ya lee y borra codigos reales del auto.

## Compilar

No necesitas instalar nada en tu maquina. Haz push y GitHub Actions genera el
APK:

    Actions -> build -> ultimo run -> Artifacts -> adiag-debug-apk

El workflow corre primero los tests del parser OBD, asi que un push que rompa la
decodificacion falla antes de empaquetar.

Si prefieres compilar en local con Android Studio, genera el wrapper una vez:

    gradle wrapper --gradle-version 8.11.1

## Uso

1. Empareja el adaptador en los ajustes de Bluetooth del telefono (PIN `1234`).
2. Conecta el adaptador al puerto OBD2 y pon el switch en contacto.
3. Abre ADiag, elige el adaptador, pulsa **Escanear**.

## Arquitectura

    app/          UI Compose, ViewModel, permisos
    core-obd/     Transportes BT, sesion ELM327, parser ISO-TP
    core-data/    Room, repositorio con cascada de resolucion, SyncWorker
    core-model/   Modelos de dominio puros

### Capa OBD

`ObdTransport` tiene dos implementaciones. Se intenta **Bluetooth Classic (SPP)**
primero porque es mas estable en Android, y se cae a **BLE GATT** si el bonding
falla. En BLE los UUID de servicio no estan hardcodeados: se descubren en
runtime, porque los clones ELM327 usan `FFF0`, `FFE0` o `18F0` segun el
fabricante.

El handshake fuerza `ATH1` (headers activos). Sin eso no se sabe que ECU
respondio, y ese dato es justamente lo que permite ubicar la falla en el
vehiculo.

`DtcParser` reensambla ISO-TP antes de decodificar: con mas de dos codigos la
respuesta llega partida en varios frames, y con `ATH1` el byte PCI queda entre
el header y el byte de servicio. Cubierto por tests unitarios con vectores
reales de single frame, multiframe y respuestas de varios modulos.

### Base de datos

Fuente: [`Wal33D/dtc-database`](https://github.com/Wal33D/dtc-database) (MIT),
28.220 codigos. El `.db` de `app/src/main/assets/` es esa base mas una columna
de descripciones en español generada por `tools/translate_dtc.py`.

La traduccion es **determinista, no generativa**: un glosario cerrado de frases
y terminos SAE aplicado con sustitucion. No puede alucinar. Cada descripcion
reporta su cobertura; las que no quedan cubiertas al 100% se marcan `[rev]` y la
UI muestra ademas el texto original en ingles.

    codigos traducidos (GENERIC + FORD + LINCOLN + MERCURY):  10.654
    cobertura total del glosario:                              9.092  (85,3%)
    marcados para revision:                                    1.562  (14,7%)

Para regenerar tras ampliar el glosario:

    python3 tools/translate_dtc.py ruta/al/dtc_codes.db app/src/main/assets/dtc_codes.db

### Resolucion de descripciones

En cascada, de mas especifica a mas general: fabricante exacto -> familia
corporativa (Ford/Lincoln/Mercury comparten set) -> generico SAE. El nivel que
resolvio se guarda y se muestra en la tarjeta del codigo, para que siempre se
sepa de donde salio el texto.

### Sincronizacion

`DtcSyncWorker` corre cada 7 dias con Wi-Fi y en carga. Consulta la API de
GitHub con `If-None-Match`: si el repo no cambio, responde 304 y no baja nada.
Antes de reemplazar la base valida que el archivo abra como SQLite y traiga mas
de 20.000 filas, asi que una descarga truncada no rompe la app. El `.db` de
assets es el piso: sin red, la app funciona igual.

## Pendiente (fases 3 a 8)

- Vista 3D con SceneView + glTF y marcadores anclados a la falla
- Datos en vivo, monitores I/M, freeze frame (modo 02)
- UDS modo 22 con los signalsets de [`OBDb/Ford-Fiesta`](https://github.com/OBDb/Ford-Fiesta)
- Historial y export CSV
- Revision manual de las 1.562 descripciones marcadas `[rev]`

## Advertencia

Las descripciones provienen de bases comunitarias y pueden contener errores. No
uses esta app como unica fuente para una reparacion. No borres codigos con el
motor encendido.
