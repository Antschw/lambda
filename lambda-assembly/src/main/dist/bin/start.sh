#!/bin/sh
# Démarre le serveur Quarkus (port 8080 par défaut, modifiable : -Dquarkus.http.port=9090)
cd "$(dirname "$0")/.." || exit 1
exec java ${JAVA_OPTS} -jar quarkus-run.jar "$@"
