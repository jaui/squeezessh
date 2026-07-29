# Das Datenformat von JuiceSSH 3.2.2

Grundlage für den Import in [`data/importer`](../app/src/main/java/dev/jaui/squeezessh/data/importer).
Alles hier wurde gegen eine laufende JuiceSSH 3.2.2 (Free) auf einem Emulator verifiziert,
nicht aus dem Bytecode geraten. Wo etwas aus dem Dekompilat stammt, steht es dabei.

Zweck ist ausschließlich Interoperabilität: Nutzer sollen ihre eigenen Daten mitnehmen
können, nachdem die App eingestellt wurde. Es wurde kein Code übernommen.

## Wo die Daten liegen

```
/data/data/com.sonelli.juicessh/
├── databases/<account_hash>          ← SQLite, unverschlüsselt
└── shared_prefs/com.sonelli.juicessh_preferences.xml
```

Die Datenbank trägt **keinen sprechenden Namen**, sondern heißt wie der `account_hash`,
etwa `19a093afcee2a1df8bb6ac14d79843`. Beide Dateien liegen im privaten App-Speicher und
sind nur mit Root oder auf einem Emulator erreichbar — die Free-Version hat keinen Export.

## Preferences

```xml
<string name="account">New User</string>
<string name="account_hash">19a093afcee2a1df8bb6ac14d79843</string>
<string name="19a093afcee2a1df8bb6ac14d79843">du4ampqmqck=#/eFCf…#dEBIoED4…</string>
<boolean name="g25_i" value="true" />
```

- `account_hash` = MD5 des Kontonamens als Hex — allerdings mit einem Fehler im Original:
  `Integer.toHexString(b & 0xFF)` füllt nicht auf zwei Stellen auf, weshalb der Hash je
  nach Kontoname kürzer als 32 Zeichen ist (hier 30). Für den Import spielt das keine
  Rolle, weil der Wert direkt aus den Preferences gelesen wird.
- Der Eintrag **mit genau diesem Namen** enthält den Master-Key, verschlüsselt mit dem
  `account_hash` als Passwort.
- Ältere Installationen legen ihn stattdessen unter `password` ab.

## Verschlüsselung

Ein Feld sieht immer so aus:

```
Base64(salt[8]) "#" Base64(iv[16]) "#" Base64(ciphertext)
```

| | |
|---|---|
| Schlüsselableitung | `PBKDF2WithHmacSHA1`, 1000 Runden, 256 Bit |
| Chiffre | `AES/CBC/PKCS5Padding` |

Zwei Stufen:

```
account_hash ──PBKDF2──► entschlüsselt prefs[account_hash] ──► Master-Key
Master-Key   ──PBKDF2──► entschlüsselt identity.password  ──► Klartext
```

Der Master-Key selbst ist ein Zufallswert (`BigInteger(130, SecureRandom).toString(32)`).

**Verschlüsselt sind nur drei Spalten:** `identity.password`, `identity.privatekey` und
`identity.privatekeyPassword`. Nicknames, Adressen, Ports, Gruppen und Snippets stehen im
Klartext.

### Der PIN-Lock schützt die Daten nicht

Naheliegende Annahme wäre, dass ein gesetzter PIN den Master-Key absichert. Das tut er
nicht. Der PIN liegt unter `md5(account_hash)` und ist mit `"925256861" + account_hash`
verschlüsselt — einer Konstanten aus dem Programmcode. Der Master-Key hängt unverändert
allein am `account_hash`.

Praktische Folge für uns: **der Import braucht kein Geheimnis vom Nutzer**, auch wenn auf
dem alten Gerät ein PIN gesetzt war. Sicherheitstechnisch heißt es, dass der PIN nur die
Oberfläche verriegelt hat. Genau deshalb legt SqueezeSSH seine Geheimnisse in den
Android-Keystore, wo der Schlüssel das Gerät nicht verlässt.

## Schema

ORMLite-Konventionen: Primärschlüssel sind **UUID-Strings**, Fremdschlüssel heißen
`<feld>_id`. Jede Tabelle trägt zusätzlich `modified`, `encrypted`, `isEncrypted`,
`owner_id`, `lastUpdatedBy_id` — die stammen aus CloudSync und Teams.

| Tabelle | Fachliche Spalten |
|---|---|
| `connection` | `nickname`, `address`, `port`, `type`, `backspaceMode`, `group_id`, `via_id`, `snippet_id`, `mosh_command` |
| `connectiongroup` | `name` |
| `connectiongroupmembership` | `group_id`, `connection_id` |
| `connectionidentity` | `connection_id`, `identity_id` |
| `connectioncounter` | `connection_id`, `count` |
| `identity` | `username`, `password`, `privatekey`, `privatekeyPassword`, `nickname`, `passUpdated`, `keyUpdated`, `snippet_id` |
| `host` | `hostname`, `fingerprint`, `key`, `type` |
| `portforward` | `name`, `mode`, `connection_id`, `host`, `localPort`, `remotePort`, `openInBrowser`, `useAsProxy` |
| `snippet` | `name`, `content`, `count` |
| `configfield` / `configvalue` | `name`, `defaultValue` / `field_id`, `value` |

### Enum-Ordinalzahlen

Auf dem Emulator durch Anlegen der jeweiligen Einträge verifiziert:

| Spalte | Werte |
|---|---|
| `connection.type` | 0 = SSH, 1 = Mosh, 2 = Local Device, 3 = Telnet |
| `connection.backspaceMode` | 0 = Default (DEL), 1 = Legacy (^H) |
| `portforward.mode` | 0 = local, 1 = remote, 2 = SOCKS *(aus `PortForward.MODE_*` im Dekompilat)* |

### Fallstricke

**`connection.group_id` bleibt leer.** Die Gruppenzugehörigkeit steht ausschließlich in
`connectiongroupmembership`. Wer nur `group_id` liest, importiert alle Gruppen als leer.

**`host.hostname` enthält den Port.** Format ist die OpenSSH-Schreibweise `[host]:port`.

**`host.type` wird nie gefüllt.** JuiceSSHs `HostKeyAdapter` setzt nur `hostname`,
`fingerprint` und `key`. Der Algorithmus lässt sich aber aus dem Key selbst lesen: SSH-Keys
beginnen im Wire-Format mit einer 4-Byte-Länge und danach dem Namen, etwa `ssh-ed25519`.

**Einstellungen stehen in der Datenbank**, nicht in den Preferences — als
`configfield`/`configvalue`-Paare. Die Preferences enthalten nur Konto und Master-Key.

## Wie das nachvollzogen wurde

```powershell
# Emulator mit google_apis-Image (rootfähig, im Gegensatz zu google_apis_playstore)
adb root
adb install -r --bypass-low-target-sdk-block <juicessh.apk>   # targetSdk 29
adb shell am start -n com.sonelli.juicessh/.activities.MainActivity
# Einträge über die Oberfläche anlegen, dann:
adb shell "sqlite3 /data/data/com.sonelli.juicessh/databases/<hash> 'SELECT * FROM identity;'"
adb pull /data/data/com.sonelli.juicessh/shared_prefs/com.sonelli.juicessh_preferences.xml
```

Das Ergebnis liegt als Testfixture in
[`app/src/test/resources/fixtures`](../app/src/test/resources/fixtures) und ist die
Grundlage von `JuiceSshCryptoTest` und `JuiceSshImporterTest`.
