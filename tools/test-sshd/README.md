# sshd-Testumgebung

Zwei Container mit OpenSSH, gegen die die Integrationstests laufen. Ohne sie werden diese
Tests **übersprungen**, nicht rot — ein fehlgeschlagener Test soll bedeuten, dass der Code
kaputt ist, nicht dass Docker gerade nicht läuft.

```powershell
.\up.ps1              # bauen und starten, erzeugt beim ersten Mal die Client-Schlüssel
.\up.ps1 -Rebuild     # Image neu bauen (Achtung: neue Host-Keys)
.\up.ps1 -Down        # stoppen und aufräumen
```

## Was läuft

| | Erreichbar unter | Rolle |
|---|---|---|
| `squeezessh-target` | `127.0.0.1:2222` | Zielserver |
| `squeezessh-bastion` | `127.0.0.1:2223` | vorgelagerter Sprung-Host |

Der Bastion erreicht das Ziel im Docker-Netz als `target:22`. Von außen ist es dort
**nicht** erreichbar — genau deshalb beweist ein erfolgreicher Sprung-Test, dass die
Verbindung wirklich durch den Bastion lief und nicht daran vorbei.

Vom Android-Emulator aus ist der Host unter `10.0.2.2` zu sehen, das Ziel also unter
`10.0.2.2:2222`.

## Zugänge

| Nutzer | Anmeldung |
|---|---|
| `pwuser` | Passwort `testpass123` |
| `keyuser` | `keys/id_ed25519`, ohne Passphrase |
| `lockuser` | `keys/id_ed25519_locked`, Passphrase `keypass123` |
| `jumpuser` | Passwort `jumppass123` |

`keys/` wird lokal erzeugt und ist bewusst nicht eingecheckt.

## Bekannte Fallstricke

**Alpine sperrt neu angelegte Konten.** `adduser -D` setzt das Passwortfeld auf `!`, was
OpenSSH als gesperrtes Konto liest — dann scheitert auch die Public-Key-Anmeldung, ohne
dass im Log etwas von Schlüsseln steht. Das Dockerfile setzt deshalb `usermod -p '*'`.

**Host-Keys stecken im Image**, nicht in einem Volume. Damit zeigt derselbe Container nach
einem Neustart denselben Schlüssel, was die Known-Hosts-Tests erst verlässlich macht.
`-Rebuild` erzeugt neue — danach melden die Tests einmalig einen geänderten Host-Key.

**`-N ''` in PowerShell.** Nicht `-N '""'` schreiben; das setzt die Passphrase auf die
zwei Zeichen `""` statt auf leer, und die Schlüssel sind dann unbrauchbar.

## Was hier nicht getestet werden kann

**Keyboard-interactive.** OpenSSH auf Linux bräuchte dafür PAM, das Alpines Paket nicht
mitbringt. Der Code-Pfad in `SshConnector` existiert, ist aber nur gegen einen echten
Server mit PAM belegbar.
