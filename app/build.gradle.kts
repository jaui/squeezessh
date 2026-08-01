// Innerhalb des Gradle-Kotlin-DSL verdeckt die `java`-Extension das Paket `java`,
// java.util.Properties laesst sich deshalb nicht voll qualifiziert ansprechen.
import com.android.build.api.variant.BuildConfigField
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
/** Namensraum der Plugin-API. Ausgeschrieben nur hier — siehe [pluginApiPackage]. */
val PLUGIN_API_PACKAGE = "dev.jaui.squeezessh"

/** Der fremde Namensraum, den JuiceSSH-Plugins suchen. Nicht unserer, also unveraenderlich. */
val PLUGIN_API_PACKAGE_COMPAT = "com.sonelli.juicessh"

/** Haelt Debug- und Release-Build auseinander, damit beide nebeneinander installierbar sind. */
val PLUGIN_API_DEBUG_SUFFIX = ".debug"

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

        // Der Namensraum der Plugin-API fuer das Manifest von "standalone". Bewusst ueber
        // die DSL und nicht ueber die Variant-API: nur so erreichen die Werte auch den
        // Manifest-Merge der Unit-Tests, der sonst mit „no value provided" abbricht.
        //
        // Das Manifest von "juicesshcompat" benutzt diese Platzhalter nicht — es schreibt
        // den fremden JuiceSSH-Namensraum aus. Der `debug`-Block unten kann sie deshalb
        // gefahrlos ueberschreiben, ohne diese Variante zu beruehren.
        manifestPlaceholders["pluginApiPackage"] = PLUGIN_API_PACKAGE
        manifestPlaceholders["pluginApiAuthority"] = "$PLUGIN_API_PACKAGE.api.v1"
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
        }
        create("juicesshcompat") {
            dimension = "pluginApi"
            versionNameSuffix = "-juicesshcompat"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false

            // Passend zum applicationIdSuffix: Permissions, Permission-Gruppen und
            // Provider-Authorities fuehrt Android geraeteweit, jeder Name gehoert genau
            // einem Paket. Ohne diesen Suffix liesse sich der Release-Build nicht neben
            // dem Debug-Build installieren — der zweite scheiterte an DUPLICATE_PERMISSION.
            manifestPlaceholders["pluginApiPackage"] =
                PLUGIN_API_PACKAGE + PLUGIN_API_DEBUG_SUFFIX
            manifestPlaceholders["pluginApiAuthority"] =
                "$PLUGIN_API_PACKAGE$PLUGIN_API_DEBUG_SUFFIX.api.v1"
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

/**
 * Der Namensraum der Plugin-API, an einer Stelle festgelegt.
 *
 * Er steckt in der Provider-Authority, in elf Permission-Namen, in der Permission-Gruppe und
 * in den Intent-Actions der Dienste. Frueher stand er in beiden Varianten-Manifesten
 * ausgeschrieben; die `manifestPlaceholders`, die es dafuer schon gab, waren gesetzt, aber
 * von keinem Manifest benutzt.
 *
 * Der Debug-Suffix gehoert dazu, weil Android Permissions, Permission-Gruppen und
 * Provider-Authorities **geraeteweit** fuehrt: jeder Name gehoert genau einem Paket.
 * `applicationIdSuffix = ".debug"` aendert nur die applicationId, nicht diese Namen — Debug-
 * und Release-Build derselben Variante liessen sich deshalb nicht nebeneinander
 * installieren, der zweite scheiterte mit DUPLICATE_PERMISSION.
 *
 * Fuer `juicesshcompat` gilt das ausdruecklich **nicht**: fremde JuiceSSH-Plugins suchen
 * exakt `com.sonelli.juicessh.api.v1`, ein Suffix daran macht sie blind. Diese Variante
 * bleibt deshalb auf einen installierten Build beschraenkt — sie wird ohnehin nicht
 * ausgeliefert und ist ein Testwerkzeug.
 */
fun pluginApiPackage(flavorName: String?, buildTypeName: String?): String = when (flavorName) {
    "juicesshcompat" -> PLUGIN_API_PACKAGE_COMPAT
    else -> PLUGIN_API_PACKAGE + if (buildTypeName == "debug") PLUGIN_API_DEBUG_SUFFIX else ""
}

/**
 * `BuildConfig.PLUGIN_API_AUTHORITY` muss dasselbe sagen wie das Manifest.
 *
 * Die Platzhalter dafuer stehen in der DSL oben, dieses Feld hier: die DSL kennt Variante und
 * Build-Typ nicht gemeinsam — ein `buildConfigField` im `debug`-Block ueberschriebe auch das
 * von `juicesshcompat` und haenge dessen Plugins ab. Die Variant-API kennt beides, erreicht
 * dafuer aber den Manifest-Merge der Unit-Tests nicht. Deshalb der Umweg ueber zwei Wege mit
 * einer gemeinsamen Quelle in [pluginApiPackage].
 */
androidComponents {
    onVariants { variant ->
        val authority = pluginApiPackage(variant.flavorName, variant.buildType) + ".api.v1"

        // Nullable, weil es ohne `buildFeatures.buildConfig` kein BuildConfig gibt. Hier
        // waere das kein Schoenheitsfehler, sondern der Ausfall der Plugin-API:
        // PluginContract leitet Authority, Permission-Namen und MIME-Typen daraus ab.
        val buildConfigFields = requireNotNull(variant.buildConfigFields) {
            "buildFeatures.buildConfig muss eingeschaltet bleiben, PluginContract braucht " +
                "PLUGIN_API_AUTHORITY"
        }
        buildConfigFields.put(
            "PLUGIN_API_AUTHORITY",
            BuildConfigField("String", "\"$authority\"", "Namensraum der Plugin-API"),
        )
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

/**
 * Haengt das APK an ein GitHub-Release: `.\gradlew :app:publishGithubRelease`.
 *
 * Signiert wird lokal — der Keystore verlaesst diesen Rechner nicht, es gibt keine
 * Signaturgeheimnisse bei GitHub. Hochgeladen wird nur das fertige APK.
 *
 * Die Release-Notizen kommen aus `docs/releases/v<version>.md`, wenn es die Datei gibt.
 * Sonst laesst `gh` sie aus den Commits seit dem letzten Tag erzeugen.
 *
 * Verweigert die Arbeit bei einem schmutzigen Arbeitsbaum: ein Tag zeigt sonst auf einen
 * Commit, der das veroeffentlichte APK nicht enthaelt — hinterher ist das nicht mehr zu sehen.
 */
val gitIsDirty = providers.exec {
    commandLine("git", "status", "--porcelain")
}.standardOutput.asText.map { it.isNotBlank() }

tasks.register("publishGithubRelease") {
    group = "distribution"
    description = "Legt ein GitHub-Release an und laedt das signierte APK hoch."
    dependsOn("publishRelease")

    val version = APP_VERSION_NAME
    val dirty = gitIsDirty
    val distDir = rootProject.layout.projectDirectory.dir("dist")
    val notesFile = rootProject.layout.projectDirectory.file("docs/releases/v$version.md").asFile

    doLast {
        check(!dirty.get()) {
            "Der Arbeitsbaum ist schmutzig. Erst committen — sonst zeigt das Tag v$version auf " +
                "einen Commit, der dieses APK nicht enthaelt."
        }

        val apk = distDir.file("squeezessh-$version.apk").asFile
        require(apk.exists()) { "Nicht gebaut: ${apk.path}" }

        val args = mutableListOf(
            "gh", "release", "create", "v$version", apk.path,
            "--title", "v$version",
        )
        if (notesFile.exists()) {
            args += listOf("--notes-file", notesFile.path)
        } else {
            args += "--generate-notes"
        }

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) {
            "gh release create ist mit Code $code fehlgeschlagen:\n$output\n\n" +
                "Ist die GitHub CLI installiert und angemeldet? `gh auth status`"
        }
        logger.lifecycle(output.trim())
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
