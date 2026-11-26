import java.io.File
import java.util.Properties

plugins {
    id("com.android.application") version "9.1.1"
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingProp(propKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingProp("storeFile", "PORTAL_KEYSTORE_PATH")
val releaseStorePassword = signingProp("storePassword", "PORTAL_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "PORTAL_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "PORTAL_KEY_PASSWORD")

android {
    namespace = "app.portaldrop"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.portaldrop"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.6.1"
    }

    signingConfigs {
        if (releaseStorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                val resolved = if (releaseStorePath.startsWith("~/")) {
                    File(System.getProperty("user.home"), releaseStorePath.removePrefix("~/"))
                } else {
                    File(releaseStorePath)
                }
                storeFile = resolved
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.commonmark:commonmark:0.32.0")
}
