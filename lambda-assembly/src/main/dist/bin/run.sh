#!/bin/sh
cd "$(dirname "$0")/.." || exit 1
exec java ${JAVA_OPTS} -cp "lib/*" fr.antschw.lambda.Main "$@"
