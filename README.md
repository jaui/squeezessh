# SqueezeSSH

[![Release](https://img.shields.io/github/v/release/jaui/squeezessh?label=Release&color=blue)](https://github.com/jaui/squeezessh/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jaui/squeezessh/total?label=Downloads&color=blue)](https://github.com/jaui/squeezessh/releases)
[![Lizenz](https://img.shields.io/github/license/jaui/squeezessh?label=Lizenz)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#technik)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#technik)

Ein moderner SSH-Client für Android in Kotlin — als Nachfolger für das eingestellte JuiceSSH.

JuiceSSH (Sonelli Ltd) wurde seit 2021 nicht mehr gepflegt und am **11.12.2025 aus dem Play
Store entfernt**; die Infrastruktur unter `juicessh.com` ist ebenfalls nicht mehr erreichbar.
Quellcode wurde nie veröffentlicht. SqueezeSSH ist deshalb ein **Neubau**, kein Fork.

## Status

Frühe Entwicklung. Siehe [`docs/roadmap.md`](docs/roadmap.md) für den Stand der Meilensteine.

## Installation

Fertige APKs liegen unter [Releases](https://github.com/jaui/squeezessh/releases/latest).
Sie sind selbst signiert — Android fragt beim Installieren aus einer Fremdquelle nach. Ein
Play-Store-Eintrag existiert nicht.

Die Versionsnummer folgt den Meilensteinen: M9 ist `0.9.0`, eine Nachbesserung daran `0.9.1`.

## Verhältnis zu JuiceSSH

SqueezeSSH ist **nicht** von Sonelli Ltd und steht in keiner Verbindung zu JuiceSSH.
„JuiceSSH" ist eine Marke von Sonelli Ltd.

Aus der JuiceSSH-APK wurden ausschließlich **Schnittstellen- und Verhaltensinformationen**
abgeleitet, die zur Interoperabilität nötig sind — insbesondere das Datenbankschema, damit
Nutzer ihre bestehenden Verbindungen importieren können, sowie die Plugin-API. Es wurde
**kein dekompilierter Code und keine Ressource** (Layouts, Grafiken, Texte, Icons) übernommen.
Details: [`docs/reverse-engineering-notes.md`](docs/reverse-engineering-notes.md).

## Technik

| | |
|---|---|
| Sprache / UI | Kotlin, Jetpack Compose, Material 3 |
| SSH | [SSHJ](https://github.com/hierynomus/sshj) (Apache-2.0) |
| Terminal | [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) (GPLv3) |
| Persistenz | Room (SQLite), Secrets via AES-GCM über Android Keystore |
| DI | Hilt |
| Min / Target SDK | 26 / 37 |

## Bauen

Voraussetzung: JDK 17+ und Android SDK (Platform 37, Build-Tools 37.x).

```bash
./gradlew assembleDebug
./gradlew test           # Unit-Tests
./gradlew installDebug   # auf angeschlossenes Gerät
```

`local.properties` mit `sdk.dir=...` wird nicht eingecheckt und muss lokal vorhanden sein —
Android Studio legt sie beim ersten Öffnen automatisch an.

### Release

```bash
./gradlew :app:publishRelease         # dist/squeezessh-<version>.apk, signiert
./gradlew :app:publishGithubRelease   # dasselbe, plus GitHub-Release via gh
```

Signiert wird ausschließlich lokal. Das Signaturmaterial steht in `keystore.properties` im
Projektstamm und ist nicht eingecheckt; fehlt die Datei, bleibt der Release-Build unsigniert,
statt fehlzuschlagen — bauen kann also auch, wer den Schlüssel nicht hat. Bei GitHub liegen
keine Signaturgeheimnisse.

`publishGithubRelease` braucht eine angemeldete [GitHub CLI](https://cli.github.com/) und
verweigert die Arbeit bei einem schmutzigen Arbeitsbaum. Die Release-Notizen kommen aus
`docs/releases/v<version>.md`, sonst erzeugt `gh` sie aus den Commits.

## Lizenz

**GPLv3** — siehe [`LICENSE`](LICENSE).

Die Lizenzwahl ist nicht frei: der eingebundene Terminal-Emulator von Termux steht unter
GPLv3 und färbt auf das Gesamtwerk ab. Alle übrigen Abhängigkeiten (SSHJ, BouncyCastle,
AndroidX, AWS SDK) sind Apache-2.0 und damit GPLv3-kompatibel.
