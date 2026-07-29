# Minimaler sshd fuer die Integrationstests.
#
# Bewusst kein fertiges Image von Docker Hub: wir brauchen genau definierte Nutzer und
# Schluessel, damit die Tests deterministisch sind und im Fehlerfall klar ist, woran es lag.
FROM alpine:3.21

RUN apk add --no-cache openssh-server openssh-sftp-server shadow

# Host-Keys werden beim Build erzeugt. Sie sind damit pro Image stabil, was genau das ist,
# was die Known-Hosts-Tests brauchen: derselbe Container zeigt immer denselben Key.
RUN ssh-keygen -A

# `adduser -D` setzt das Passwortfeld auf "!". OpenSSH wertet das als gesperrtes Konto
# und lehnt dann auch Public-Key-Anmeldungen ab. "*" bedeutet dagegen nur „kein Passwort".
RUN adduser -D -s /bin/sh pwuser  && echo 'pwuser:testpass123' | chpasswd \
 && adduser -D -s /bin/sh keyuser  && usermod -p '*' keyuser \
 && adduser -D -s /bin/sh lockuser && usermod -p '*' lockuser \
 && adduser -D -s /bin/sh jumpuser && echo 'jumpuser:jumppass123' | chpasswd

COPY sshd_config /etc/ssh/sshd_config
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
