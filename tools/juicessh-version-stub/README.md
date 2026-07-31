# Versions-Attrappe für Plugin-Tests

Ein leeres APK mit dem Paketnamen `com.sonelli.juicessh` und `versionCode 200`. Es enthält
keinen Programmcode, keine Activity, keinen Dienst und keine Berechtigung.

## Wozu

`PluginClient.start()` aus Sonellis Plugin-Bibliothek bindet den Dienst erst, nachdem es
geprüft hat, dass ein Paket namens `com.sonelli.juicessh` installiert ist:

```java
PackageInfo info = pm.getPackageInfo("com.sonelli.juicessh", 0);
if (info.versionCode < MINIMUM_JUICESSH_VERSION_CODE) { showVersionWarning(context); return false; }
```

Ohne dieses Paket zeigt jedes darauf gebaute Plugin nur „This JuiceSSH plugin requires that
JuiceSSH 1.5.4 (or newer) be installed" und bindet nichts. Die Prüfung hängt am Paketnamen,
nicht an der Schnittstelle — mit einer eigenen `applicationId` ist sie nicht erfüllbar.

Diese Attrappe erfüllt genau diese Prüfung, damit sich der Rest der Kette testen lässt:
Bindung, `ping`, Sitzungsaufbau über `startActivityForResult` und die AIDL-Aufrufe.

## Was sie ausdrücklich nicht ist

Kein Ersatz für JuiceSSH und kein Versuch, sich dafür auszugeben. Sie tut nichts, sie wird
nicht ausgeliefert, und sie gehört auf einen Emulator und sonst nirgendwohin. Die
Provider-Authority und die Berechtigungen deklariert sie **nicht** — sonst liesse sie sich
nicht neben der Kompatibilitätsvariante installieren.

## Benutzung

```powershell
.\gradlew.bat -p tools\juicessh-version-stub assembleDebug
adb install -r tools\juicessh-version-stub\build\outputs\apk\debug\juicessh-version-stub-debug.apk
```

Danach findet ein Plugin die Kompatibilitätsvariante von SqueezeSSH und bindet ihren Dienst.
Zum Aufräumen: `adb uninstall com.sonelli.juicessh`.
