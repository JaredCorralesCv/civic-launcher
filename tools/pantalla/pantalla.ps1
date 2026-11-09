<#
  Ajustes de la pantalla del Civic por adb, con respaldo y vuelta atrás.

  Uso (con la depuración activada en la pantalla y la PC en el mismo WiFi):
    .\pantalla.ps1 -Accion Respaldo                 # foto del estado actual (antes de tocar nada)
    .\pantalla.ps1 -Accion Restaurar                # regresa TODO al último respaldo
    .\pantalla.ps1 -Accion Restaurar -Archivo X     # regresa a un respaldo en particular
    .\pantalla.ps1 -Accion Estado                   # qué está cambiado respecto al respaldo
    .\pantalla.ps1 -Accion QuitarLauncherOriginal   # desactiva el launcher del fabricante CON SEGURO
    .\pantalla.ps1 -Accion Confirmar                # todo salió bien: quita el seguro

  El seguro: antes de desactivar el launcher original se deja corriendo EN LA PANTALLA un vigilante
  que, si en $Minutos minutos nadie confirma, lo vuelve a activar solo (aunque la PC ya no esté).

  Qué guarda el respaldo: apps desactivadas, escalas de animación, modo de pantalla completa,
  launcher predeterminado y cómo está compilada cada app. Restaurar:
    - reactiva lo que Civic desactivó (no toca lo que ya venía desactivado de fábrica),
    - regresa las animaciones y la pantalla completa a como estaban,
    - quita la compilación forzada (Android vuelve a optimizar solo),
    - y siempre reactiva el launcher original, por seguridad.
#>
param(
    [ValidateSet("Respaldo", "Restaurar", "Estado", "QuitarLauncherOriginal", "Confirmar")] [string] $Accion = "Estado",
    [string] $Paquete = "com.android.launcher3",
    [int] $Minutos = 15,
    [string] $Archivo,
    [string] $Pantalla = "192.168.100.78:8555"
)

# "Continue": en PowerShell 5.1 los avisos de adb por stderr no deben cortar el script; los errores reales se lanzan con throw.
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$dir = Join-Path $PSScriptRoot "respaldos"
New-Item -ItemType Directory -Force $dir | Out-Null

# Apps cuya compilación se fuerza (y se deshace al restaurar).
$compiladas = @("com.civiclauncher.app", "cn.manstep.phonemirrorBox", "com.google.android.apps.maps", "com.autochips.bluetooth", "com.android.chrome")
$etiqueta = $Pantalla -replace '[^\w]', '_'
$ajustes = @("window_animation_scale", "transition_animation_scale", "animator_duration_scale", "policy_control")

# El stderr se junta DENTRO de la pantalla (2>&1 en su shell): así los errores llegan como texto normal.
function Sh([string] $cmd) { (& $adb -s $Pantalla shell "$cmd 2>&1" | Out-String).Trim() }

function Conectar {
    & $adb connect $Pantalla | Out-Null
    $state = (& $adb -s $Pantalla get-state 2>$null | Out-String).Trim()
    if ($state -ne "device") { throw "No hay conexión con la pantalla ($Pantalla): $state. ¿Está activada la depuración?" }
}

function Desactivadas { (Sh "pm list packages -d") -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ } }

function Respaldo {
    Conectar
    $r = [ordered]@{
        fecha        = (Get-Date).ToString("s")
        desactivadas = @(Desactivadas)
        ajustes      = [ordered]@{}
        home         = Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    }
    foreach ($k in $ajustes) { $r.ajustes[$k] = Sh "settings get global $k" }
    $file = Join-Path $dir ("respaldo-" + $etiqueta + "-" + (Get-Date).ToString("yyyyMMdd-HHmmss") + ".json")
    $r | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 $file
    # Copia también en la pantalla, por si la PC no está a la mano.
    & $adb -s $Pantalla push $file "/sdcard/civic-respaldo.json" | Out-Null
    "Respaldo guardado: $file"
}

function Leer {
    $f = Ultimo
    # Si el respaldo no se puede leer o viene vacío, NO se restaura nada: restaurar a ciegas podría
    # reactivar apps que venían desactivadas de fábrica.
    $r = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $r -or -not $r.fecha -or $null -eq $r.desactivadas) { throw "El respaldo $($f.FullName) está dañado; no se cambió nada." }
    @($f, $r)
}

function Ultimo {
    if ($Archivo) { return Get-Item -LiteralPath $Archivo }
    # Solo los respaldos de ESTA pantalla (el del emulador nunca se usa en el carro).
    $f = Get-ChildItem $dir -Filter "respaldo-$etiqueta-*.json" | Sort-Object Name | Select-Object -First 1
    if (-not $f) { throw "No hay respaldos de $Pantalla en $dir" }
    # El PRIMERO es el estado original de fábrica (antes de cualquier cambio de Civic).
    $f
}

function Restaurar {
    Conectar
    $f, $r = Leer
    "Restaurando desde $($f.Name) ($($r.fecha))"
    # Siempre primero: el launcher original de vuelta (red de seguridad).
    Sh "pm enable com.android.launcher3" | Out-Null
    $ahora = @(Desactivadas)
    foreach ($p in $ahora) {
        if ($r.desactivadas -notcontains $p) { "  reactivando $p : " + (Sh "pm enable $p") }
    }
    # Y lo que estaba desactivado en el respaldo pero hoy está activo, se vuelve a desactivar
    # (salvo el launcher original, que siempre se deja activo).
    foreach ($p in $r.desactivadas) {
        if ($p -ne "com.android.launcher3" -and $ahora -notcontains $p) { "  desactivando otra vez $p : " + (Sh "pm disable-user --user 0 $p") }
    }
    foreach ($k in $ajustes) {
        $v = $r.ajustes.$k
        if ($v -eq "null" -or [string]::IsNullOrWhiteSpace($v)) { Sh "settings delete global $k" | Out-Null }
        else { Sh "settings put global $k '$v'" | Out-Null }
        "  $k = $v"
    }
    foreach ($p in $compiladas) { Sh "cmd package compile --reset $p" | Out-Null }
    "  compilación forzada quitada (Android vuelve a optimizar solo)"
    if ($r.home -match '([\w.]+)/([\w.$]+)') { Sh "cmd package set-home-activity $($Matches[0])" | Out-Null; "  launcher predeterminado: $($Matches[0])" }
    "Listo."
}

function Estado {
    Conectar
    $f, $r = Leer
    "Comparando con $($f.Name)"
    $ahora = @(Desactivadas)
    $ahora | Where-Object { $r.desactivadas -notcontains $_ } | ForEach-Object { "  desactivada por Civic: $_" }
    foreach ($k in $ajustes) { $v = Sh "settings get global $k"; if ($v -ne $r.ajustes.$k) { "  $k : $($r.ajustes.$k) -> $v" } }
}

function QuitarLauncherOriginal {
    Conectar
    $inicio = Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    if ($inicio -notmatch "com.civiclauncher.app") { throw "Civic no es el launcher predeterminado ($inicio): no se quita el original." }
    if (-not (Sh "pidof com.civiclauncher.app")) { throw "Civic no está corriendo: no se quita el original." }
    # Vigilante en la pantalla: revisa cada 10 s si se confirmó; si no, al acabar el tiempo reactiva el paquete.
    $vueltas = $Minutos * 6
    $script = "rm -f /data/local/tmp/civic_ok; i=0; while [ `$i -lt $vueltas ]; do [ -f /data/local/tmp/civic_ok ] && exit 0; sleep 10; i=`$((i+1)); done; pm enable $Paquete; echo reactivado > /data/local/tmp/civic_guard_fired"
    Sh "echo '$script' > /data/local/tmp/civic_guard.sh" | Out-Null
    Sh "rm -f /data/local/tmp/civic_guard_fired; nohup sh /data/local/tmp/civic_guard.sh > /dev/null 2>&1 &" | Out-Null
    Start-Sleep 1
    if (-not ((Sh "ps -A -o ARGS") -match "civic_guard")) { throw "No arrancó el vigilante: no se quita el original." }
    "Vigilante activo: si no confirmas en $Minutos min, $Paquete se reactiva solo."
    "  " + (Sh "pm disable-user --user 0 $Paquete")
    "Ahora prueba: apaga y enciende el carro. Si todo va bien: .\pantalla.ps1 -Accion Confirmar"
    "Si algo sale mal y hay conexión: .\pantalla.ps1 -Accion Restaurar (o espera $Minutos min)."
}

function Confirmar {
    Conectar
    Sh "touch /data/local/tmp/civic_ok" | Out-Null
    if ((Sh "cat /data/local/tmp/civic_guard_fired 2>/dev/null") -match "reactivado") { "Ojo: el vigilante YA había reactivado el launcher original (se acabó el tiempo)." }
    else { "Confirmado: el vigilante se detiene en los próximos 10 s y el cambio se queda." }
}

switch ($Accion) { "Respaldo" { Respaldo } "Restaurar" { Restaurar } "Estado" { Estado } "QuitarLauncherOriginal" { QuitarLauncherOriginal } "Confirmar" { Confirmar } }
