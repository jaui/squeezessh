# Roadmap

Fachliche Grundlage: [`reverse-engineering-notes.md`](reverse-engineering-notes.md) für
den Funktionsumfang, [`juicessh-datenformat.md`](juicessh-datenformat.md) für das
Speicherformat des Originals.

| | Meilenstein | Status |
|---|---|---|
| M0 | Projekt-Setup, Git-Repo, Referenz-Workspace | ✅ erledigt |
| M1 | Datenschicht: Room-Entities, Repositories, Keystore-Krypto | ✅ erledigt |
| M2 | Import bestehender JuiceSSH-Datenbanken | ✅ erledigt |
| M3 | SSH-Core auf SSHJ inkl. Known Hosts und Jump-Host-Chaining | ✅ erledigt |
| M4 | Terminal auf Termux-Emulator | ✅ erledigt |
| M5 | Address Book UI | ✅ erledigt |
| M6 | Port-Forwarding und SFTP | ✅ erledigt |
| M7 | Settings, Snippets, App-Lock, Widget | ✅ erledigt |
| M8 | EC2-Sync | offen |
| M9 | Plugin-API-Kompatibilität | offen |

## Abweichungen vom ursprünglichen Plan

**Termux' `TerminalSession` und `TerminalView` sind nicht verwendbar.** `TerminalSession`
ist `final` und startet in `initializeEmulator` fest einen lokalen Prozess über JNI;
`TerminalView.attachSession` verlangt genau diesen Typ. Für eine Shell am anderen Ende
einer SSH-Verbindung ist beides unbrauchbar.

Verwendet werden stattdessen die Bausteine darunter, die für genau diesen Zweck öffentlich
sind: `TerminalEmulator` als Parser, `TerminalOutput` als Rückkanal, `TerminalRenderer`
fürs Zeichnen und `KeyHandler` für die Umsetzung von Tasten in Escape-Sequenzen. Eigen
geschrieben sind nur Größenrechnung, Gesten und Tastatur-Anbindung
(`terminal/TerminalView.kt`). Die Geometrie folgt bewusst exakt Termux' Rechnung — eine
abweichende Zeilenzahl fällt sonst erst auf, wenn `vim` krumm zeichnet.

**Leisten liegen über dem Terminal, nicht daneben.** Tab- und Auswahlleiste sind Overlays
im selben `Box` wie die eingebettete View. Zwei Gründe, beide praktisch:

- Eine `AndroidView` wird als echte View **nach** dem Compose-Inhalt gezeichnet und
  verdeckt eine im `Column` davor liegende Leiste vollständig.
- Nähme eine Leiste Platz weg, änderte sich die Zeilenzahl, das Terminal vermäße sich neu
  — und verwürfe dabei genau die Auswahl, die die Leiste anzeigt.

**Adresse wird beim Speichern aufgelöst, nicht beim Tippen.** Das Adressfeld nimmt auch
`ssh://root@host:2222` entgegen. Live zu zerlegen wäre unbrauchbar: bei `10.0.2.2:2222`
wären Host und Port nach dem ersten Ziffernanschlag getrennt und die restlichen Ziffern
landeten im falschen Feld. Eine Zeile unter dem Feld zeigt vorher an, was verstanden wurde.

**SOCKS5 ist selbst geschrieben.** SSHJ kennt nur feste Ziel-Paare; ein dynamischer
Tunnel (`ssh -D`) bestimmt sein Ziel dagegen pro Verbindung. `Socks5Server` setzt den Teil
von RFC 1928 um, den Browser und `curl --socks5` benutzen: Anmeldung ohne
Authentifizierung und CONNECT. BIND und UDP ASSOCIATE fehlen — beides braucht über einen
SSH-Tunnel praktisch niemand und wäre ohne Gegenstelle nicht testbar.

**Jeder Abbau von SSH-Ressourcen läuft im Hintergrund.** Ein Kanalabbau schickt Pakete;
auf dem Hauptthread wirft Android dabei mitten im Schreiben, und der Server verwirft die
*gesamte* Verbindung als „Bad packet length" — samt aller anderen Kanäle darauf. Betrifft
`SshSessionManager.close`, `TerminalTabs.close` und den SFTP-Kanal beim Verlassen des
Browsers.

**Der PIN wird nicht gespeichert, auch nicht verschlüsselt.** Abgelegt wird ein Nachweis:
Salz plus PBKDF2 mit 120 000 Runden, zusätzlich mit dem Geräteschlüssel versiegelt. Beim
Prüfen wird neu abgeleitet und in konstanter Zeit verglichen. Bewusst strenger als bei
JuiceSSH — dort lag der PIN verschlüsselt vor, aber mit einem Schlüssel aus einer
Konstanten im Programmcode und dem Kontonamen; wer die Datei hatte, hatte den PIN.

**DataStore kommt als Abhängigkeit, nicht über `Context.preferencesDataStore`.** Das
Delegat hält eine verborgene, prozessweite Instanz — bequem, aber in Tests nicht
zurücksetzbar. Zwei Tests fielen darauf herein, bevor die Kopplung aufgelöst war.

**Noch nicht umgesetzt aus M4/M5/M6/M7:** umschaltbare Schriftarten in der Oberfläche (Themes und
Schnittstelle stehen), Umsortieren von Identitäten pro Verbindung (die Reihenfolge wird
gespeichert, ist aber noch nicht per Drag änderbar), Export privater Schlüssel,
Fortschritts-Notification für Übertragungen (Fortschritt, Pause, Fortsetzen und Abbruch
gibt es im Dateibrowser, aber noch nicht in der Statusleiste). Aus M7: die
Terminal-Einstellungen (Farbschema, Schriftgröße, Rand, TERM, Signalton,
Tastenleisten-Position, Wischverhalten) werden gespeichert, aber vom Terminal noch nicht
gelesen — es benutzt weiterhin seine Vorgaben. Snippets lassen sich verwalten, aber noch
nicht aus dem Terminal heraus einfügen.

## Offene Entscheidungen

- **M9 — eigene vs. JuiceSSH-Authority.** Die Übernahme von Authority und
  Permission-Namen macht eine Koexistenz mit installiertem JuiceSSH unmöglich.
  Vorschlag: eigene Authority als Standard, Kompatibilität als Build-Flavor.
- **Mosh.** Erfordert einen eigenen NDK-Build von `mosh-client`. Das Datenmodell führt
  `moshCommand` bereits mit, ausgewertet wird es vorerst nicht.
- **Lokale Shell.** Termux bringt die Bausteine mit; erst nach M4 sinnvoll zu bewerten.

## Integrationstests

Die SSH-Tests laufen gegen echte sshd-Container aus
[`tools/test-sshd`](../tools/test-sshd/README.md):

```powershell
.\tools\test-sshd\up.ps1
.\gradlew.bat testDebugUnitTest
```

Ohne laufende Container werden sie übersprungen, nicht rot. Nicht abgedeckt:
keyboard-interactive (bräuchte PAM, das Alpines OpenSSH nicht mitbringt).

## Verifikation

- Unit-Tests: DAOs, Import-Mapper, Quick-Connect-/`ssh://`-Parser.
- Integration gegen echten sshd (Docker `linuxserver/openssh-server` oder WSL-sshd,
  vom Emulator aus über `10.0.2.2`): Passwort-Auth, Ed25519-Key, Key mit Passphrase,
  keyboard-interactive, Host-Key-Wechsel muss warnen, Jump-Host-Kette,
  local/remote/dynamic Forward, SFTP mit Prüfsummenvergleich.
- Terminal: `vttest`, dazu `htop`, `vim`, `tmux`, `less`; Resize im laufenden Betrieb;
  UTF-8 und Wide-Chars; 256-Farben und True-Color.
- Lifecycle: Sessions überleben App-Wechsel, Doze und Netzwechsel WLAN↔Mobil.
