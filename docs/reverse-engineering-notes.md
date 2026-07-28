# Analyse der JuiceSSH-APK — Referenz-Spezifikation

Diese Datei hält fest, was aus der JuiceSSH-APK über deren **Schnittstellen und Verhalten**
ermittelt wurde. Sie ist die fachliche Spezifikation für den Neubau.

## Rahmen und Grenzen

Analysiert wurde `com.sonelli.juicessh_3.2.2-200_minAPI26(...).apk` (nicht im Repo,
siehe `.gitignore`), ausschließlich mit `aapt2 dump badging|xmltree` und einer Roh-Suche
über `classes.dex`. Verwendet wurden daraus **nur**:

- das SQLite-/ORMLite-Schema (nötig, damit Nutzer ihre Daten mitnehmen können),
- die Plugin-API v1 (nötig, damit vorhandene Plugins weiter funktionieren),
- der Funktionsumfang als Feature-Liste,
- die Liste der eingesetzten Fremdbibliotheken.

**Nicht** übernommen und ausdrücklich ausgeschlossen: dekompilierter Java-/Smali-Code,
Layouts, Grafiken, Strings, Icons, Fonts, Themes-Definitionen, der Name „JuiceSSH" und
jedes andere Markenmerkmal von Sonelli Ltd.

## Eckdaten des Originals

| | |
|---|---|
| Package | `com.sonelli.juicessh` |
| Version | 3.2.2 (versionCode 200) |
| minSdk / targetSdk / compileSdk | 26 / 29 / 29 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |
| App-Klassen im DEX | 427 (obfuskiert bis auf öffentliche Typen) |

### Technischer Stack des Originals

| Baustein | Umsetzung im Original | Ersatz in SqueezeSSH |
|---|---|---|
| SSH-Protokoll | libssh (LGPL) nativ, angebunden über JNA (`com.sonelli.libssh.SshLibrary` + `adapter/SSH*`) | SSHJ (reines JVM, Apache-2.0) |
| Krypto | mitgeliefertes OpenSSL (`libcrypto.so`, `libssl.so`), BouncyCastle für Keys | BouncyCastle + Android-Plattformkrypto |
| Terminal | jackpal `emulatorview` (Apache-2.0, seit ~2015 unmaintained) | Termux `terminal-emulator` / `terminal-view` |
| Persistenz | ORMLite über SQLite | Room |
| Netzwerk (CloudSync) | OkHttp + Gson | entfällt |
| EC2 | AWS Mobile SDK | AWS SDK for Java v2 |
| Zusatz-Binaries | `mosh-client`, `bash`, `telnet` (alle GPLv3) + `res/raw/terminfo.zip` | zunächst entfallen |
| Telemetrie | Crashlytics, Google Analytics, GCM-Push | entfällt |

## Datenmodell

Ermittelt aus `res/raw/ormlite_config.txt`. **Jede** Tabelle trägt zusätzlich die
Sync-Spalten `id`, `modified`, `encrypted`, `isEncrypted`, `owner`, `lastUpdatedBy` —
diese existieren nur wegen CloudSync/Teams und werden in SqueezeSSH nicht nachgebaut.

### Kern (wird importiert)

| Tabelle | Fachliche Spalten |
|---|---|
| `connection` | `group`, `via`, `nickname`, `address`, `snippet`, `moshCommand`, `port`, `type`, `backspaceMode` |
| `connectiongroup` | `name` |
| `connectiongroupmembership` | `group`, `connection` |
| `connectionidentity` | `connection`, `identity` |
| `identity` | `username`, `password`, `privatekey`, `privatekeyPassword`, `nickname`, `passUpdated`, `keyUpdated`, `snippet` |
| `host` | `hostname`, `fingerprint`, `key`, `type` — das sind die **Known Hosts** |
| `portforward` | `name`, `mode`, `connection`, `host`, `localPort`, `remotePort`, `openInBrowser`, `useAsProxy` |
| `snippet` | `name`, `content`, `count` |
| `connectioncounter` | `connection`, `count` — Grundlage für „Frequently Used" |
| `configfield` | `name`, `defaultValue` |
| `configvalue` | `field`, `value` |

Bemerkenswert: `connection.via` verweist auf eine andere Connection. Das ist das
**Bastion-/Jump-Host-Chaining** und muss im SSH-Core abgebildet werden.
Einstellungen liegen im Original nicht in SharedPreferences, sondern als
`configfield`/`configvalue`-Paare in der DB.

### EC2-Sync

| Tabelle | Fachliche Spalten |
|---|---|
| `ec2profile` | `name`, `region`, `key`, `secret`, `isEnabled`, `disabledReason`, `lastSyncTime`, `lastSyncItemCount` |
| `ec2mapping` | `profile`, `defaultType`, `defaultIdentity`, `defaultConnectVia`, `privateIps`, `defaultPort` |
| `ec2filter` | `mapping`, `key`, `pattern` |
| `ec2groupmapping` | `mapping`, `group` |
| `ec2connection` | `mapping`, `instanceId` + alle Spalten von `connection` |

### Nicht nachgebaut

`user`, `team`, `teammembership`, `teamencryption`, `share`, `purchase`, `deleteditem`,
`plugin_log` (letzteres kommt in M9 in eigener Form zurück).

## Einstellungen

Schlüssel aus `res/xml/settings.xml`, gruppiert wie im Original:

| Gruppe | Schlüssel |
|---|---|
| General | `theme:dark`, `action:manageSnippets`, `action:manageEc2SyncProfiles` |
| Account/Sicherheit | `pinlock:enabled`, `action:clear_known_hosts`, `ssh:agent` |
| Terminal | `terminal:theme`, `terminal:font`, `terminal:font_size`, `terminal:margin`, `terminal:horizontal_swipe_mode`, `terminal:utf8`, `terminal:type`, `terminal:bell_mode`, `terminal:keyboard_position` |
| Mosh | `mosh:mosh-server` |

## Plugin-API v1

Zwei Mechanismen, beide über eigene Permissions abgesichert.

**ContentProvider** — Authority `com.sonelli.juicessh.api.v1`:

| Pfad | Lese-Permission | Schreib-Permission |
|---|---|---|
| `/connections` | `…api.v1.permission.READ_CONNECTIONS` | `…WRITE_CONNECTIONS` |
| `/connectiongroups` | `…READ_CONNECTION_GROUPS` | `…WRITE_CONNECTION_GROUPS` |
| `/connectiongroupmemberships` | `…READ_CONNECTION_GROUPS` | `…WRITE_CONNECTION_GROUPS` |
| `/portforwards` | `…READ_PORT_FORWARDS` | `…WRITE_PORT_FORWARDS` |
| `/snippets` | `…READ_SNIPPETS` | `…WRITE_SNIPPETS` |
| `/pluginlog` | `…READ_PLUGIN_LOG` | — |

Der Provider selbst ist zusätzlich mit `…api.v1.permission.JUICESSH_ONLY` geschützt.

**AIDL-Services**:

| Service | Intent-Action | Permission |
|---|---|---|
| `PluginService` | `com.sonelli.juicessh.pluginservice` | `…OPEN_SESSIONS` |
| `ConnectionPluginService` | `com.sonelli.juicessh.connectionpluginservice` | `…OPEN_SESSIONS` |
| `SftpPluginService` | `com.sonelli.juicessh.sftppluginservice` | `…OPEN_SFTP_SESSIONS` |

Die AIDL-Definitionen sind **nicht** aus dem Dekompilat zu nehmen, sondern aus
[`Sonelli/juicessh-pluginlibrary`](https://github.com/Sonelli/juicessh-pluginlibrary)
(Apache-2.0, von Sonelli selbst veröffentlicht).

> **Installationskonflikt:** Wer Authority und Permission-Namen von JuiceSSH übernimmt,
> kann nicht neben einem installierten JuiceSSH koexistieren
> (`INSTALL_FAILED_CONFLICTING_PROVIDER` bzw. `DUPLICATE_PERMISSION`).
> Vorgesehen ist daher eine eigene Authority als Standard und ein separater
> Build-Flavor für JuiceSSH-Kompatibilität.

## Funktionsumfang (aus Activities und Layouts abgeleitet)

Address Book mit Gruppen und Suche · Frequently Used · Quick Connect · Verbindungs-,
Identitäts- und Gruppen-Editoren · Private-Key-Import/-Generierung/-Einfügen ·
Terminal mit Tabs, Hacker-Keyboard, Copy/Paste-Modus, Auto-Complete-Box, Themes und
Fonts · Snippets · Port-Forwards · SFTP mit Pause/Resume/Cancel · Known-Hosts-Verwaltung ·
PIN-Lock mit Lockout · EC2-Profile und -Mappings · Plugin-Verwaltung und Log-Viewer ·
Home-Screen-Widget · Changelog- und Lizenz-Anzeige.

Nicht nachgebaut: CloudSync, Teams/Sharing, Bookclub, Account-Verwaltung, Google-Billing.

## Reproduktion der Analyse

```powershell
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\aapt2.exe"
& $aapt dump badging <apk>
& $aapt dump xmltree --file AndroidManifest.xml <apk>
& $aapt dump xmltree --file res/xml/settings.xml <apk>
```

`res/raw/ormlite_config.txt` und `res/raw/licenses.json` lassen sich direkt als
ZIP-Einträge aus der APK lesen — dafür ist kein Dekompilat nötig.
