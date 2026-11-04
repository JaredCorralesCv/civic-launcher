# Civic Launcher

Launcher para la pantalla Android del Civic 2008 (ZXDZ_01), con un estilo inspirado en la filosofía de diseño de los sistemas de infoentretenimiento modernos y con telemetría OBD2.

## Estado: Fase 2 terminada (launcher básico) · versión 0.2.0

Civic ya es un launcher completo:

| Pantalla | Qué tiene |
|---|---|
| **Inicio** | Reloj grande (cambia por minuto con TIME_TICK), fecha en español, velocímetro GPS y 6 accesos: Android Auto (AutoKit), Radio, Teléfono, Cámara, Vehículo y Apps. Aviso si la zona horaria sigue en China. |
| **Vehículo** | Medidores y datos en modo demo con el simulador (el OBD real llega en la Fase 3). |
| **Apps** | Todas las apps, con íconos cargados en segundo plano y en caché. Se actualiza al instalar o desinstalar. Mantener presionada = información de la app. |
| **Ajustes** | Tema (automático / noche / día), zona horaria, botones del volante (SWC), ajustes y CAN del fabricante, launcher predeterminado, WiFi, actualizaciones desde GitHub, diagnóstico y acerca de. |

El botón de inicio siempre regresa a Inicio y "atrás" nunca sale del launcher. Las barras del sistema se vuelven a esconder solas si el teclado las muestra.

### Actualizaciones desde GitHub

1. Crea un repositorio **público** en GitHub.
2. Publica un *release* con etiqueta `v0.3.0` (más nueva que la instalada) y adjunta el `.apk`.
3. En la pantalla: **Ajustes → Actualizaciones**, escribe `usuario/repositorio` y toca **Buscar**.

El APK debe estar firmado con la misma llave (`civic-release.jks`). Sin root, Android siempre pide confirmar la instalación.

### Firma de la app

La versión release se firma con `civic-release.jks`, y las contraseñas están en `keystore.properties`. **Ninguno de los dos se sube a git. Respáldalos fuera de la PC**: si se pierden, ya no se podrá actualizar la app sin desinstalarla.

## Instalar en la pantalla

1. **Solo la primera vez con la 0.2.0:** desinstala la versión anterior. La firma cambió y Android no deja instalar encima.
2. Copia `dist/CivicLauncher.apk` a una memoria USB, ábrelo con el administrador de archivos e instálalo.
3. Presiona el botón de inicio, elige **Civic Launcher** y luego **Siempre**.

> Usa siempre el APK de `dist/` (compilación release). La versión debug de Compose es mucho más lenta.

## Compilar y probar

Desde Android Studio, abre esta carpeta y espera a que sincronice Gradle. Desde la terminal:

```bash
./gradlew :obd:test
./gradlew lintDebug
./gradlew assembleRelease
```

El APK queda en `app/build/outputs/apk/release/app-release.apk`.

> El "lint vital" de release está desactivado porque AGP 9.5 falla en Windows con él. La revisión completa se hace con `lintDebug`.

## Tecnología

- Kotlin 2.4 y Jetpack Compose 1.12 (Material 3), con el Kotlin integrado de AGP 9
- `minSdk 24`; la pantalla tiene Android 8.1 real (API 27)
- Inyección de dependencias manual (`AppContainer` en `CivicApp.kt`)
- Módulo `:obd` en Kotlin puro: JUnit 6, Turbine y coroutines-test
- R8 en release
- Tipografía [Inter](https://rsms.me/inter/) (SIL OFL) e íconos [Phosphor](https://phosphoricons.com) (MIT). Licencias en `licenses/`.

## Reglas de diseño

- Colores **opacos**: nada de desenfoques, sombras ni capas semitransparentes encimadas (por la Mali-450).
- Al presionar cambia el color de fondo; no hay "ripple".
- Mínimo 64 dp para cualquier cosa que se toque.
- Números con ancho fijo (`tnum`) para que los medidores no "bailen".
- Medidores dibujados con `drawWithCache`: la aguja se anima en la fase de dibujo, sin recomponer.
- Paleta de noche por defecto; paleta de día de alto contraste para el sol.

## Estructura

```
obd/src/main/kotlin/com/civiclauncher/obd/
├── Pids.kt               PIDs del modo 01 y sus fórmulas
├── ElmParser.kt          Respuestas del ELM327 → valores (NO DATA, errores, varias computadoras)
├── ElmSession.kt         Configuración del adaptador y consultas (ObdTransport)
└── SimulatedElm.kt       Adaptador falso: ciclo de manejo y motor que se calienta

app/src/main/java/com/civiclauncher/app/
├── CivicApp.kt           App + AppContainer (inyección manual)
├── MainActivity.kt       Pantalla completa
├── ui/design/            Sistema de diseño (Fase 1)
│   ├── Tokens.kt         Paletas día/noche, espacios, tamaños, radios
│   ├── Type.kt           Inter y escala tipográfica
│   ├── CivicTheme.kt     CivicDesignTheme y acceso `Civic.colors/type/space…`
│   ├── Components.kt     Tarjeta, botones, accesos, dock, valores, avisos, barras
│   ├── Gauge.kt          Medidor circular
│   └── PhosphorIcons.kt  80 íconos (generado desde los SVG oficiales)
├── ui/                   Componentes de las pantallas de diagnóstico
├── launcher/             Fase 2: el launcher
│   ├── data/             Reloj, velocidad GPS, apps instaladas, ajustes, apps del fabricante
│   ├── update/           Actualizador desde GitHub Releases
│   └── ui/               Raíz con dock, Inicio, Vehículo, Apps y Ajustes
└── diag/                 Fase 0: diagnóstico + catálogo de diseño (en Ajustes → Diagnóstico)
```
## Hardware confirmado (reporte del 2026-11-04)

| | |
|---|---|
| Android | 8.1 real (API 27). Configuración muestra "11", que es falso. |
| Chip | AutoChips AC8227L, 4× Cortex-A7 a 1.1 GHz, 32 bits |
| RAM | 1 GB (unos 450 MB libres) |
| GPU | Mali-450 MP, OpenGL ES 2.0 |
| Pantalla | 1024×600, 160 dpi (1 dp = 1 px), 60 Hz |
| Bluetooth | Solo clásico, **sin BLE** (escaneo BLE: error 3). **SPP funciona**: la pantalla abrió una conexión sin emparejar y recibió respuestas tipo ELM327. |
| AutoKit | No comparte la canción ni acepta controles de forma confiable. Solo sirve abrirlo. Con Android Auto corriendo quedan ~340–420 MB libres. |
| USB | Host funcional |
| Fluidez (Compose, JIT) | 60/60 fps, 0 cuadros perdidos tras calentar. Compose es viable. |
| GPS | 13 satélites visibles, 5 en uso, precisión 4 m, reporta velocidad |
| Llave | Apagado corto: suspensión con arranque rápido; el sistema mata los servicios de las apps. Muchas horas apagado, o a veces al dar marcha (caída de voltaje): reinicio completo. El launcher debe arrancar rápido y reconectar el OBD en ambos casos. |
| Launcher | El fabricante sí deja poner Civic como inicio predeterminado. |
| Root | `/system/xbin/su` (no está en el PATH) |
| adb | Puerto WiFi 8555 de fábrica, con autorización |
| Uso diario | Maps y YouTube Music por **Android Auto con AutoKit** (`cn.manstep.phonemirrorBox`) |

## Plan

| Fase | Contenido |
|---|---|
| 0 | Diagnóstico ✅ terminado (2026-11-05) |
| 1 | Base: módulos `:app` y `:obd`, sistema de diseño (Inter + Phosphor), actualización de librerías ✅ terminado (2026-11-05) |
| 2 ✅ terminado (2026-11-05) | Launcher básico: reloj, accesos grandes (Android Auto, radio, cámara, Bluetooth), apps, velocímetro GPS y actualización desde GitHub (con WiFi de casa) |
| **3** ← *siguiente* | Motor OBD2 con simulador, por **Bluetooth clásico (SPP)**. Reconexión automática al despertar o reiniciar. |
| 4 | Pantalla del vehículo: medidores, códigos de falla, alertas, consumo y viajes |
| 5 | Alertas encima de Android Auto (burbuja flotante) |
| 6 | Pulido y Baseline Profiles (`:baselineprofile`) |
| 7 | Extras: botones del volante (`com.hcn.swc`), datos propios de Honda, temas |

Se descartó la tarjeta de música: AutoKit no comparte la canción y Android Auto ya la muestra.

Presupuesto de memoria: con Android Auto corriendo, el launcher y el servicio OBD tienen que caber en ~340 MB libres. Meta: menos de 80 MB entre los dos.

Adaptador OBD recomendado: uno de **Bluetooth clásico (SPP)**, por ejemplo el Vgate iCar Pro en su versión **Bluetooth 3.0**. No sirve uno solo-BLE como el iCar Pro 4.0.
