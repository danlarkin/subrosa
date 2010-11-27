#!/bin/bash
die () {
    echo "`basename $0`: error: $@" >&2
    exit 1
}

kt=`which keytool`

[ $? -ne 0 ] && die "keytool not found"

keytool -genkey -alias subrosa -keyalg RSA -keystore subrosa.ks -storetype JKS -dname "CN=, OU=, O=, C="
