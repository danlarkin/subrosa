#!/bin/sh

BASEDIR="$(cd "$(dirname $0)/.." > /dev/null; pwd)"

JAVA_CMD=${JAVA_CMD:-"java"}
JAVA_OPTS=${JAVA_OPTS:-"-server"}
SUBROSA_PORT=${SUBROSA_PORT:-"6667"}

exec $JAVA_CMD $JAVA_OPTS -cp "$BASEDIR/etc/:$BASEDIR/lib/*" subrosa.main $SUBROSA_PORT
