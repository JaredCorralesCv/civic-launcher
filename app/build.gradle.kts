import java.util.Properties

// AGP 9 trae Kotlin integrado: ya no se aplica el plugin org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.civiclauncher.app"
    // Compose 1.12 pide compilar contra la API 37. No cambia en qué Android corre la app (eso es minSdk).
    compileSdk = 37

    defaultConfig {
        // No cambiar nunca: el actualizador desde GitHub depende de este id.
        applicationId = "com.civiclauncher.app"
        // Tu pantalla tiene Android 8.1 (API 27). Dejamos 24 por margen; las librerías piden 23 o más.
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.1"
    }

    // Firma propia: el actualizador solo puede instalar versiones firmadas con la MISMA llave.
    // keystore.properties y civic-release.jks no se suben a git; respáldalos en un lugar seguro.
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // El "lint vital" de release falla en Windows con AGP 9.5 (abre dos veces el mismo .jar).
        // Hacemos la revisión completa con `gradlew lintDebug`, que sí funciona.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":obd"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
