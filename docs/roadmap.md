# Roadmap

Fachliche Grundlage: [`reverse-engineering-notes.md`](reverse-engineering-notes.md) für
den Funktionsumfang, [`juicessh-datenformat.md`](juicessh-datenformat.md) für das
Speicherformat des Originals.

| | Meilenstein | Status |
|---|---|---|
| M0 | Projekt-Setup, Git-Repo, Referenz-Workspace | ✅ erledigt |
| M1 | Datenschicht: Room-Entities, Repositories, Keystore-Krypto | ✅ erledigt |
| M2 | Import bestehender JuiceSSH-Datenbanken | ✅ erledigt |
| M3 | SSH-Core auf SSHJ inkl. Known Hosts und Jump-Host-Chaining | offen |
| M4 | Terminal auf Termux-Emulator | offen |
| M5 | Address Book UI | offen |
| M6 | Port-Forwarding und SFTP | offen |
| M7 | Settings, Snippets, App-Lock, Widget | offen |
| M8 | EC2-Sync | offen |
| M9 | Plugin-API-Kompatibilität | offen |

## Offene Entscheidungen

- **M9 — eigene vs. JuiceSSH-Authority.** Die Übernahme von Authority und
  Permission-Namen macht eine Koexistenz mit installiertem JuiceSSH unmöglich.
  Vorschlag: eigene Authority als Standard, Kompatibilität als Build-Flavor.
- **Mosh.** Erfordert einen eigenen NDK-Build von `mosh-client`. Das Datenmodell führt
  `moshCommand` bereits mit, ausgewertet wird es vorerst nicht.
- **Lokale Shell.** Termux bringt die Bausteine mit; erst nach M4 sinnvoll zu bewerten.

## Verifikation

- Unit-Tests: DAOs, Import-Mapper, Quick-Connect-/`ssh://`-Parser.
- Integration gegen echten sshd (Docker `linuxserver/openssh-server` oder WSL-sshd,
  vom Emulator aus über `10.0.2.2`): Passwort-Auth, Ed25519-Key, Key mit Passphrase,
  keyboard-interactive, Host-Key-Wechsel muss warnen, Jump-Host-Kette,
  local/remote/dynamic Forward, SFTP mit Prüfsummenvergleich.
- Terminal: `vttest`, dazu `htop`, `vim`, `tmux`, `less`; Resize im laufenden Betrieb;
  UTF-8 und Wide-Chars; 256-Farben und True-Color.
- Lifecycle: Sessions überleben App-Wechsel, Doze und Netzwechsel WLAN↔Mobil.
