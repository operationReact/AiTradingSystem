// A stable release key is mandatory for distribution builds. PR-only APKs
// retain Flutter's transient debug signer and are never an installable update.
val minttickStableSigning = System.getenv("MINTTICK_REQUIRE_STABLE_SIGNING") == "true"
fun requiredMinttickSigningValue(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("MintTick Android release signing is missing: $name")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "in.aitradingsystem.trading_client"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }

    defaultConfig {
        applicationId = "in.aitradingsystem.trading_client"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (minttickStableSigning) {
            create("minttickRelease") {
                val keyStore = file(requiredMinttickSigningValue("MINTTICK_ANDROID_KEYSTORE_PATH"))
                check(keyStore.isFile) { "MintTick permanent signing keystore is missing" }
                storeFile = keyStore
                storePassword = requiredMinttickSigningValue("MINTTICK_ANDROID_STORE_PASSWORD")
                keyAlias = requiredMinttickSigningValue("MINTTICK_ANDROID_KEY_ALIAS")
                keyPassword = requiredMinttickSigningValue("MINTTICK_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Only pull-request CI validation may use the ephemeral debug key.
            signingConfig = if (minttickStableSigning) {
                signingConfigs.getByName("minttickRelease")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

flutter { source = "../.." }
