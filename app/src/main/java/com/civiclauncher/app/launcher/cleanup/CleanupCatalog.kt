package com.civiclauncher.app.launcher.cleanup

enum class Risk(val title: String, val description: String) {
    SAFE(
        "Seguras de desactivar",
        "No las usa nada importante de la pantalla. Desactivarlas libera memoria para Android Auto.",
    ),
    CAUTION(
        "Con precaución",
        "Funcionan bien desactivadas, pero quizás uses alguna. Lee la nota de cada una.",
    ),
}

/**
 * Una entrada de la lista. [packages] puede tener varias apps (por ejemplo, los 44 temas)
 * que se desactivan juntas. [prefix] incluye todas las instaladas que empiecen así.
 */
data class CleanupEntry(
    val id: String,
    val title: String,
    val note: String,
    val risk: Risk,
    val packages: List<String> = emptyList(),
    val prefix: String? = null,
)

/**
 * Lista revisada a mano con las apps de TU pantalla (reporte de la Fase 0). Todo se puede revertir.
 *
 * Criterio: solo entra lo que no forma parte de la comunicación con el carro, el audio, la cámara,
 * el Bluetooth, el arranque rápido o el sistema. Esas apps están en [PROTECTED] y la app se niega
 * a desactivarlas aunque alguien lo intente.
 */
object CleanupCatalog {
    val entries = listOf(
        // ---------- Seguras ----------
        CleanupEntry(
            "themes", "Temas del launcher del fabricante",
            "Más de 40 paquetes de temas que solo usa el launcher viejo. Se conserva el tema activo por si lo vuelves a usar.",
            Risk.SAFE, prefix = "com.hcn.auto.theme.",
        ),
        CleanupEntry(
            "fota", "Actualizador de sistema Adups (FOTA)",
            "Busca actualizaciones del sistema en servidores chinos. Adups tuvo problemas de privacidad conocidos y tu pantalla no recibe actualizaciones reales.",
            Risk.SAFE, packages = listOf("com.adups.fota", "com.adups.fota.sysoper"),
        ),
        CleanupEntry(
            "mtklogger", "Registro de depuración de MediaTek",
            "Herramientas para ingenieros que graban registros y gastan memoria y almacenamiento.",
            Risk.SAFE, packages = listOf("com.mediatek.mtklogger", "com.mediatek.mtklogger.proxy"),
        ),
        CleanupEntry(
            "wallpapers", "Fondos animados",
            "\"Agujero negro\", \"Espiral de holograma\" y el selector de fondos animados. Civic no los usa.",
            Risk.SAFE, packages = listOf("com.android.galaxy4", "com.android.wallpaper.holospiral", "com.android.wallpaper.livepicker"),
        ),
        CleanupEntry(
            "google_misc", "Extras de Google sin uso",
            "Lector de PDF, agente de comentarios del Market y recomendaciones de impresión.",
            Risk.SAFE, packages = listOf(
                "com.google.android.apps.pdfviewer",
                "com.google.android.feedback",
                "com.google.android.printservice.recommendation",
            ),
        ),
        CleanupEntry(
            "misc", "Extras del fabricante sin uso",
            "Guía de usuario (\"Nota\"), importador de calendarios y salvapantallas.",
            Risk.SAFE, packages = listOf("com.hcn.autouserguide", "com.mediatek.calendarimporter", "com.hcn.screensaver"),
        ),

        // ---------- Con precaución ----------
        CleanupEntry(
            "youtube", "YouTube (versión 2021)",
            "Muy vieja y no la usas: tu música va por Android Auto.",
            Risk.CAUTION, packages = listOf("com.google.android.youtube"),
        ),
        CleanupEntry(
            "maps", "Google Maps (versión 2019)",
            "Muy vieja; tus mapas van por Android Auto. Desactívala solo si nunca la abres en la pantalla.",
            Risk.CAUTION, packages = listOf("com.google.android.apps.maps"),
        ),
        CleanupEntry(
            "google_app", "App de Google y búsqueda por voz",
            "Corre en segundo plano y consume bastante memoria. El asistente de voz de Android Auto es el de tu celular, no este.",
            Risk.CAUTION, packages = listOf("com.google.android.googlequicksearchbox"),
        ),
        CleanupEntry(
            "chrome", "Chrome (versión 2018)",
            "Navegador viejo. Si lo desactivas, los enlaces no abrirán en la pantalla. No afecta a Civic.",
            Risk.CAUTION, packages = listOf("com.android.chrome"),
        ),
        CleanupEntry(
            "media", "Reproductor de música y galería del fabricante",
            "Solo los necesitas si pones música o fotos desde una memoria USB.",
            Risk.CAUTION, packages = listOf("com.hcn.AutoMediaPlayer", "com.hcn.gallery"),
        ),
        CleanupEntry(
            "vendor_tools", "Herramientas de personalización del fabricante",
            "Cambiar logo de arranque, selector de temas y prueba de fábrica.",
            Risk.CAUTION, packages = listOf("com.hcn.logochange", "com.hcn.themepicker", "com.hcn.autotest"),
        ),
    )

    /**
     * Nunca se desactivan desde Civic: sin ellas podrías quedarte sin sonido, sin cámara de reversa,
     * sin botones del volante, sin Bluetooth o con una pantalla que no arranca.
     */
    val PROTECTED = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
        "com.android.launcher3", // launcher de respaldo si Civic fallara
        "com.android.bluetooth", "com.autochips.bluetooth", "com.mediatek.bluetooth.dtt",
        "com.autochips.quickbootmanager", "com.autochips.android.atcLocationService",
        "com.hcn.autoradio", "com.hcn.autocan", "com.hcn.canupgrade", "com.hcn.mcuupgrade", "com.hcn.swc",
        "com.hcn.autosetting", "com.hcn.autosource", "com.hcn.audioinputsource", "com.hcn.automediaplayerservice",
        "com.hcn.autofloatwindow", "com.hcn.illuminationcolor", "com.hcn.changedapp", "com.hcn.autobarcode",
        "com.hcn.autoupdate", "com.hcn.appupdate",
        "com.auto.hcamera",
        "com.mediatek.filemanager", // lo usas para instalar APKs desde USB
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.packageinstaller", "com.android.inputmethod.latin",
        "com.mediatek.webview", "com.google.android.webview",
        "cn.manstep.phonemirrorBox", // AutoKit / Android Auto
    )
}
