# Startet die sshd-Testumgebung. Erzeugt beim ersten Lauf die Client-Schluessel.
#
#   .\up.ps1            starten
#   .\up.ps1 -Down      stoppen und aufraeumen
#   .\up.ps1 -Rebuild   Image neu bauen (neue Host-Keys!)

param(
    [switch]$Down,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$keys = Join-Path $here 'keys'

if ($Down) {
    docker compose -f (Join-Path $here 'compose.yaml') down -v
    Write-Host 'Testumgebung gestoppt.'
    exit 0
}

New-Item -ItemType Directory -Force -Path $keys | Out-Null

# Schluessel ohne Passphrase — der Normalfall.
if (-not (Test-Path (Join-Path $keys 'id_ed25519'))) {
    ssh-keygen -t ed25519 -N '' -C 'squeezessh-test' -f (Join-Path $keys 'id_ed25519') | Out-Null
    Write-Host 'id_ed25519 erzeugt'
}

# Schluessel mit Passphrase — prueft, dass die Passphrase-Abfrage greift.
if (-not (Test-Path (Join-Path $keys 'id_ed25519_locked'))) {
    ssh-keygen -t ed25519 -N 'keypass123' -C 'squeezessh-test-locked' -f (Join-Path $keys 'id_ed25519_locked') | Out-Null
    Write-Host 'id_ed25519_locked erzeugt (Passphrase: keypass123)'
}

# RSA, weil aeltere Server nichts anderes anbieten.
if (-not (Test-Path (Join-Path $keys 'id_rsa'))) {
    ssh-keygen -t rsa -b 3072 -N '' -C 'squeezessh-test-rsa' -f (Join-Path $keys 'id_rsa') | Out-Null
    Write-Host 'id_rsa erzeugt'
}

$composeArgs = @('compose', '-f', (Join-Path $here 'compose.yaml'), 'up', '-d')
if ($Rebuild) { $composeArgs += '--build' }
& docker @composeArgs

Write-Host ''
Write-Host 'Bereit:'
Write-Host '  Ziel     127.0.0.1:2222   (vom Emulator: 10.0.2.2:2222)'
Write-Host '  Bastion  127.0.0.1:2223   (erreicht das Ziel intern als target:22)'
Write-Host ''
Write-Host 'Nutzer:'
Write-Host '  pwuser   / testpass123          Passwort-Authentifizierung'
Write-Host '  keyuser  + keys/id_ed25519      Schluessel ohne Passphrase'
Write-Host '  lockuser + keys/id_ed25519_locked   Passphrase: keypass123'
Write-Host '  jumpuser / jumppass123          fuer den Bastion'
