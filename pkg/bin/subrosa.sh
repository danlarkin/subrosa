#!/bin/sh

BASEDIR="$(cd "$(dirname $0)/.." > /dev/null; pwd)"

JAVA_CMD=${JAVA_CMD:-"java"}
JAVA_OPTS=${JAVA_OPTS:-"-server"}

exec $JAVA_CMD $JAVA_OPTS -cp "$BASEDIR/etc/:$BASEDIR/lib/*" subrosa.main
