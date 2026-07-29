#!/bin/sh
# Installiert die vom Host eingehaengten oeffentlichen Schluessel und startet sshd.
set -e

install_key() {
    user="$1"
    pubkey="/keys/$2"
    [ -f "$pubkey" ] || { echo "entrypoint: $pubkey fehlt" >&2; return 0; }
    home=$(getent passwd "$user" | cut -d: -f6)
    mkdir -p "$home/.ssh"
    cp "$pubkey" "$home/.ssh/authorized_keys"
    chown -R "$user:$user" "$home/.ssh"
    chmod 700 "$home/.ssh"
    chmod 600 "$home/.ssh/authorized_keys"
}

install_key keyuser  id_ed25519.pub
install_key lockuser id_ed25519_locked.pub

# Marker, an dem die Tests erkennen, dass sie beim richtigen Container gelandet sind.
echo "SqueezeSSH test host: $(hostname)" > /etc/motd

exec /usr/sbin/sshd -D -e
