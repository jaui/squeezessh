// Eigenstaendiges Projekt, damit dieses APK nicht bei jedem App-Build mitlaeuft.
// Siehe README.md — es erfuellt ausschliesslich die Versionspruefung der Plugin-Bibliothek.
plugins {
    id("com.android.application") version "8.7.3"
}

android {
    namespace = "com.sonelli.juicessh.stub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sonelli.juicessh"
        minSdk = 21
        targetSdk = 34
        // Muss ueber MINIMUM_JUICESSH_VERSION_CODE (89) liegen; 200 ist der versionCode
        // der letzten echten Fassung.
        versionCode = 200
        versionName = "3.2.2"
    }
}
