package com.civiclauncher.app.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class LaunchableApp(val pkg: String, val activity: String, val label: String)

/**
 * Apps que se pueden abrir, para la cuadrícula. Se actualiza sola al instalar o desinstalar.
 * Los íconos se cargan en segundo plano, a 96 px, y se guardan en caché: con 1 GB de RAM no
 * conviene tener 100 íconos grandes en memoria.
 */
class AppsRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()
    private val icons = LruCache<String, ImageBitmap>(160)

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                icons.evictAll()
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { LaunchableApp(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString().trim()) }
                .distinctBy { it.pkg to it.activity }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            _apps.value = list
        }
    }

    suspend fun icon(app: LaunchableApp): ImageBitmap? {
        val key = "${app.pkg}/${app.activity}"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val d = pm.getActivityIcon(android.content.ComponentName(app.pkg, app.activity))
                d.toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { icons.put(key, it) }
            }.getOrNull()
        }
    }

    fun launch(app: LaunchableApp): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        true
    }.getOrDefault(false)

    /** Abre una app por paquete (radio, AutoKit...). Falso si no está instalada. */
    fun launchPackage(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        }.getOrDefault(false)
    }

    /** Nombre visible de una app ("Spotify"), o null si no está instalada. */
    fun labelOf(pkg: String): String? = runCatching {
        context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
    }.getOrNull()

    fun isInstalled(pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun openAppInfo(pkg: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        const val ICON_PX = 96
    }
}

/** Apps del fabricante de tu pantalla (las vimos en el reporte de la Fase 0). */
object VendorApps {
    const val ANDROID_AUTO = "cn.manstep.phonemirrorBox"
    const val RADIO = "com.hcn.autoradio"
    const val BLUETOOTH = "com.autochips.bluetooth"
    const val CAMERA = "com.auto.hcamera"
    const val MUSIC = "com.hcn.AutoMediaPlayer"
    const val STEERING_WHEEL = "com.hcn.swc"
    const val CAR_SETTINGS = "com.hcn.autosetting"
    const val CAN = "com.hcn.autocan"
}
