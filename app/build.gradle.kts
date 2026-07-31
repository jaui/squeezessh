// Innerhalb des Gradle-Kotlin-DSL verdeckt die `java`-Extension das Paket `java`,
// java.util.Properties laesst sich deshalb nicht voll qualifiziert ansprechen.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Die Version folgt den Meilensteinen der Roadmap: M9 ist 0.9.0, eine Nachbesserung
 * daran 0.9.1. Hier wird sie gepflegt, `versionCode` faellt daraus ab, damit beide
 * nicht auseinanderlaufen koennen.
 */
val APP_VERSION_NAME = "0.9.0"

/** 0.9.0 -> 900, 0.9.1 -> 901, 1.0.0 -> 10000. Monoton, solange minor und patch < 100 bleiben. */
val APP_VERSION_CODE = APP_VERSION_NAME.split(".").map(String::toInt)
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

/**
 * Signaturmaterial fuer den Release-Build, bewusst ausserhalb der Versionsverwaltung.
 *
 * Fehlt die Datei — auf einem frischen Klon der Normalfall —, bleibt der Release-Build
 * unsigniert, statt fehlzuschlagen. Bauen und Debuggen kann dann trotzdem jeder.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.jaui.squeezessh"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.jaui.squeezessh"
        minSdk = 26
        targetSdk = 37
        versionCode = APP_VERSION_CODE
        versionName = APP_VERSION_NAME

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // Die Plugin-API von JuiceSSH laesst sich nicht mit ihren Originalnamen anbieten und
    // gleichzeitig neben JuiceSSH installieren: gleiche Provider-Authority und gleiche
    // Permission-Namen fuehren zu INSTALL_FAILED_CONFLICTING_PROVIDER bzw.
    // DUPLICATE_PERMISSION. Deshalb zwei Varianten, die sich nur darin unterscheiden.
    flavorDimensions += "pluginApi"

    productFlavors {
        create("standalone") {
            dimension = "pluginApi"
            isDefault = true
            manifestPlaceholders["pluginApiAuthority"] = "dev.jaui.squeezessh.api.v1"
            buildConfigField(
                "String",
                "PLUGIN_API_AUTHORITY",
                "\"dev.jaui.squeezessh.api.v1\"",
            )
        }
        create("juicesshcompat") {
            dimension = "pluginApi"
            versionNameSuffix = "-juicesshcompat"
            manifestPlaceholders["pluginApiAuthority"] = "com.sonelli.juicessh.api.v1"
            buildConfigField(
                "String",
                "PLUGIN_API_AUTHORITY",
                "\"com.sonelli.juicessh.api.v1\"",
            )
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Seit AGP 8 standardmaessig aus. Die Plugin-API besteht aus AIDL-Schnittstellen,
        // ohne die es keine Sitzungsuebergabe an Plugins gibt.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Ab AGP 9 gehoert der kotlin-Block in den android-Block (built-in Kotlin).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            // SSHJ und BouncyCastle bringen ueberlappende Metadaten mit.
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    // Exportiertes Room-Schema wird eingecheckt: so werden Schema-Aenderungen im
    // Review sichtbar und Migrationstests koennen dagegen laufen.
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Legt das signierte Release-APK unter seinem Auslieferungsnamen in `dist/` ab:
 * `.\gradlew :app:publishRelease`.
 *
 * Gebaut wird die Variante `standalone` — die mit eigener Plugin-API-Authority, die sich
 * neben JuiceSSH installieren laesst. `juicesshcompat` traegt einen `versionNameSuffix`
 * und wuerde als `squeezessh-0.9.0-juicesshcompat.apk` herauskommen; sie ist ein
 * Testwerkzeug und wird nicht ausgeliefert.
 */
tasks.register<Copy>("publishRelease") {
    group = "distribution"
    description = "Kopiert das signierte Release-APK der Variante standalone nach dist/."
    dependsOn("assembleStandaloneRelease")

    // Lokale Kopie statt Zugriff auf den Script-Wert: eine Lambda, die den Script-Wert
    // liest, faengt das ganze Script-Objekt ein, und das kann der Configuration Cache
    // nicht serialisieren.
    val version = APP_VERSION_NAME

    from(layout.buildDirectory.file("outputs/apk/standalone/release/app-standalone-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "squeezessh-$version.apk" }

    doLast {
        logger.lifecycle("dist/squeezessh-$version.apk")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // BiometricPrompt verlangt eine FragmentActivity — deshalb erbt MainActivity davon.
    implementation(libs.androidx.biometric)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // SSH-Stack (Apache-2.0). SSHJ verlangt SLF4J 2.x; slf4j-simple schreibt nach stderr -> logcat.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Terminal-Emulator (GPLv3) — macht die gesamte App GPLv3.
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
