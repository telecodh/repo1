# Captura Pantalla (Android)

App mínima: muestra un **botón flotante con una cámara**. Al tocarlo hace una captura
de toda la pantalla y la guarda en **Imágenes › Capturas** (PNG).

- Android 10 o superior (minSdk 29, targetSdk 35).
- Botón arrastrable; se oculta solo durante la captura para no salir en la imagen.
- Se detiene desde la notificación («Detener») o desde la app.
- Sin librerías externas (solo Kotlin + SDK de Android).

## Cómo obtener el APK

**Opción A – GitHub (sin instalar nada):**
1. Sube esta carpeta a un repositorio nuevo de GitHub.
2. Se ejecuta automáticamente la acción «Compilar APK» (pestaña *Actions*).
3. El APK se publica en **Releases** (y como artefacto en Actions).

**Opción B – Android Studio:** abrir esta carpeta y pulsar *Run*, o
`Build › Build APK(s)`.

**Opción C – línea de comandos** (con Android SDK instalado):
```
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Uso
1. Abre la app → «Iniciar botón flotante».
2. Concede: notificaciones, «Mostrar sobre otras apps» y el permiso de grabar/compartir pantalla
   (en Android 14+ elige «Pantalla completa»).
3. Toca la burbuja cuando quieras capturar.

## Estructura
- `MainActivity.kt` – pide permisos y arranca el servicio.
- `CapturaService.kt` – servicio en primer plano (MediaProjection + ImageReader), burbuja flotante y guardado vía MediaStore.
