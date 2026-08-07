# Notas de la primera compilacion

Este codigo **no ha sido compilado**: se escribio sin acceso a Android SDK ni a
Google Maven, asi que el primer `gradle assembleDebug` puede pedir ajustes
menores. Lo que hay que revisar, en orden de probabilidad:

1. **Versiones del catalogo** (`gradle/libs.versions.toml`). AGP 8.7.3, Kotlin
   2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Hilt 2.52. La pareja
   Kotlin/KSP debe coincidir exactamente; si Actions se queja, sube ambas.

2. **`tools:node="remove"` en el manifest**. El namespace `tools` esta
   declarado en la etiqueta `<provider>`; si el merger protesta, muevelo a la
   etiqueta `<manifest>`.

3. **`BluetoothGattDescriptorEnableNotify`** en `BleGattTransport` es un
   `ByteArray` en el companion. En API 33+ `writeDescriptor(desc, value)` es la
   firma nueva; la vieja esta deprecada pero funciona.

4. **`onCharacteristicChanged` deprecado**. Compila con warning en API 33+.
   Cuando toque, agrega la sobrecarga de tres parametros.

5. **Icono**. Usa `@android:drawable/ic_menu_manage` como placeholder para no
   depender de recursos que no existen todavia.

Lo que si esta verificado:

- El esquema y el contenido del `.db` en assets (37.610 filas totales, 10.654
  con locale `es`), abierto y consultado con SQLite.
- El algoritmo de `DtcParser`, prototipado y probado contra cinco vectores
  (single frame, multiframe ISO-TP, multiples ECUs, sin codigos, modo 07) antes
  de portarlo a Kotlin. Esos mismos casos son los tests de `DtcParserTest`.
- Los headers UDS del Fiesta 2016 (`720/728`, `726/72E`) salen del signalset
  publicado en OBDb, no de memoria.
