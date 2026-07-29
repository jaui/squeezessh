# Testfixtures

`juicessh-3.2.2.db` und `juicessh-3.2.2-prefs.xml` stammen aus einer echten JuiceSSH
3.2.2 (Free), die auf einem Emulator installiert und von Hand befuellt wurde. Sie sind
die Referenz dafuer, dass der Import gegen das tatsaechliche Format laeuft und nicht nur
gegen unsere Annahmen darueber.

Inhalt:

| | |
|---|---|
| Identitaet | `Prod-Root`, Benutzer `root`, Passwort `MarkerPass123` |
| Verbindung | `Web-Produktion` → `web.example.com:22`, Typ SSH, mit der Identitaet verknuepft |
| Verbindung | `DB-Intern` → `db.internal:22`, Typ Telnet, Backspace Legacy, Gruppe `Produktion` |
| Gruppe | `Produktion` |

**Die Zugangsdaten darin sind erfunden.** `MarkerPass123` existiert nur, damit ein Test
belegen kann, dass die Entschluesselungskette bis zum Klartext durchlaeuft. Es gehoert
zu keinem echten System.

Port-Forwards, Snippets und Known Hosts fehlen: Port-Forwards und Snippets sind in
JuiceSSH Pro-Funktionen, Known Hosts entstehen erst bei einer echten Verbindung. Diese
Pfade decken synthetisch aufgebaute Datenbanken in `JuiceSshFixtures` ab.
