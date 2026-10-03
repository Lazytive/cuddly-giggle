#!/bin/sh
# Starts the NeoForge dev server in run/ (the self-test stops it), giving up after $1 seconds.
# NEO_VERSION and MOD_VERSION come from the CI environment.
timeout "${1:-1500}" ./gradlew -p neoforge runServer --stacktrace -Pneo_version="$NEO_VERSION" -Pmod_version="$MOD_VERSION" || true
