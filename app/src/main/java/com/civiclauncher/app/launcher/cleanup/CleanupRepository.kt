package com.civiclauncher.app.launcher.cleanup

import android.content.Context
import android.content.pm.PackageManager
import com.civiclauncher.app.diag.DiagPrefs
import com.civiclauncher.app.diag.util.Shell
import com.civiclauncher.app.diag.util.SysProp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ResolvedEntry(
    val entry: CleanupEntry,
    /** Paquetes de esta entrada que sí están instalados en la pantalla. */
    val installed: List<String>,
    val disabledCount: Int,
) {
    val isPresent: Boolean get() = installed.isNotEmpty()
    val allDisabled: Boolean get() = isPresent && disabledCount == installed.size
    val noneDisabled: Boolean get() = disabledCount == 0
}

enum class RootStatus { UNKNOWN, AVAILABLE, UNAVAILABLE }

/**
 * Desactiva y reactiva apps preinstaladas. "Desactivar" no borra nada: la app sigue en el sistema,
 * pero no corre ni aparece. Se revierte en cualquier momento.
 *
 * Una app normal no puede desactivar otras; hace falta root (la pantalla trae /system/xbin/su) o
 * mandar los mismos comandos desde la PC con adb.
 */
class CleanupRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: DiagPrefs,
) {
    private val _entries = MutableStateFlow<List<ResolvedEntry>>(emptyList())
    val entries: StateFlow<List<ResolvedEntry>> = _entries.asStateFlow()

    private val _root = MutableStateFlow(if (prefs.rootOk) RootStatus.AVAILABLE else RootStatus.UNKNOWN)
    val root: StateFlow<RootStatus> = _root.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh() {
        scope.launch { _entries.value = withContext(Dispatchers.IO) { resolve() } }
    }

    private fun resolve(): List<ResolvedEntry> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val all = pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS).map { it.packageName }.toSet()
        // El tema activo del launcher viejo se conserva por si vuelves a usarlo.
        val activeThemes = setOf(SysProp.get("persist.sys.launcher.theme.pkg"), SysProp.get("persist.sys.launcher.skin.pkg"))
        return CleanupCatalog.entries.map { e ->
            val pkgs = (e.packages.filter { it in all } + (e.prefix?.let { p -> all.filter { it.startsWith(p) } } ?: emptyList()))
                .filter { it !in CleanupCatalog.PROTECTED && it !in activeThemes }
                .distinct()
                .sorted()
            ResolvedEntry(e, pkgs, pkgs.count { isDisabled(pm, it) })
        }
    }

    private fun isDisabled(pm: PackageManager, pkg: String): Boolean = runCatching {
        when (pm.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> true
            else -> false
        }
    }.getOrDefault(false)

    /** Prueba root una vez. La primera vez puede aparecer un aviso de superusuario en la pantalla. */
    fun checkRoot() {
        scope.launch {
            _busy.value = true
            val ok = withContext(Dispatchers.IO) {
                val r = Shell.su("id", 20_000)
                r.ok && r.stdout.contains("uid=0")
            }
            prefs.rootOk = ok
            _root.value = if (ok) RootStatus.AVAILABLE else RootStatus.UNAVAILABLE
            _busy.value = false
        }
    }

    /** Desactiva (o reactiva) las entradas indicadas con root. Devuelve un mensaje para mostrar. */
    fun apply(targets: List<ResolvedEntry>, disable: Boolean, onDone: (String) -> Unit) {
        val pkgs = targets.flatMap { it.installed }.filter { it !in CleanupCatalog.PROTECTED }
        if (pkgs.isEmpty()) return
        scope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { Shell.su(script(pkgs, disable), 120_000) }
            _entries.value = withContext(Dispatchers.IO) { resolve() }
            _busy.value = false
            onDone(
                if (result.ok) {
                    if (disable) "Listo: ${pkgs.size} apps desactivadas" else "Listo: ${pkgs.size} apps reactivadas"
                } else {
                    "No se pudo (${result.summary()}). ${result.stderr.take(120)}"
                },
            )
        }
    }

    /** Los mismos comandos para correrlos desde la PC con `adb shell`. */
    fun adbCommands(targets: List<ResolvedEntry>, disable: Boolean): String =
        targets.flatMap { it.installed }.filter { it !in CleanupCatalog.PROTECTED }
            .joinToString("\n") { "adb shell ${command(it, disable)}" }

    private fun script(pkgs: List<String>, disable: Boolean) = pkgs.joinToString("\n") { command(it, disable) }

    private fun command(pkg: String, disable: Boolean) =
        if (disable) "pm disable-user --user 0 $pkg" else "pm enable $pkg"
}
