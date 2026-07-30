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
| M8 | EC2-Sync | zurückgestellt (bleibt im Plan) |
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

**Umsortiert wird mit Pfeilen, nicht per Ziehen.** Die Probierreihenfolge der Identitäten
ist bedeutungstragend und wird deshalb als numerierte Liste mit Hoch/Runter geführt. Ein
Ziehen zwischen Chips ist auf einem Touchscreen schlechter zu treffen, und die Listen sind
kurz.

**`onScroll` liefert Teilstücke, keine Gesamtstrecke.** Der waagerechte Wisch hat
anfänglich nie ausgelöst, weil der Schwellwert auf `distanceX` eines einzelnen Aufrufs
angewandt wurde — das sind meist wenige Pixel. Die Bewegung wird jetzt über die ganze
Geste summiert und höchstens einmal ausgewertet; sobald genug senkrecht gewandert wurde,
bleibt es Scrollen.

**Fortschritt wird gedrosselt veröffentlicht.** `SftpSession` meldet nach jedem
32-KiB-Häppchen. Ungebremst wurden daraus bei 400 MiB über zwölftausend
Zustandsänderungen, die Liste und Benachrichtigung jedes Mal neu zeichneten — der Emulator
war daran nicht mehr bedienbar. Jetzt höchstens fünf Aktualisierungen je Sekunde, der
letzte Wert wird am Ende nachgereicht.

**`POST_NOTIFICATIONS` muss zur Laufzeit erfragt werden.** Der Manifest-Eintrag genügt seit
Android 13 nicht: ohne Zustimmung verwirft das System auch die Benachrichtigung eines
Vordergrunddienstes. Offene Sitzungen und Übertragungsfortschritt waren dadurch unsichtbar
(`numEnqueuedByApp=1578`, `numPostedByApp=0`).

**Ein `remember` im Terminal überlebt keinen Ausflug.** Navigation-Compose verwirft den
Bildschirm, wenn Dateien, Weiterleitungen oder die Snippet-Auswahl darüber liegen. Der
Merker „diese Verbindung ist schon offen" muss deshalb `rememberSaveable` sein — sonst
öffnete jede Rückkehr eine weitere Sitzung. Aus demselben Grund muss der eingefügte
Snippet-Text auf die wiederhergestellte View warten, statt beim ersten Durchlauf ins Leere
zu laufen.

**Noch nicht umgesetzt aus M4/M5/M6/M7:** nichts mehr — die zuletzt offenen Punkte
(Schriftartwahl, Terminal liest seine Einstellungen, Snippets aus dem Terminal, Export
privater Schlüssel, Umsortieren von Identitäten, Fortschritts-Notification) sind erledigt
und auf dem Emulator gegen die Testcontainer geprüft.

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
