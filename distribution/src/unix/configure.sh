#!/bin/sh
# JTRAC: configure (same as: bin/jtrac configure)
exec "$(dirname "$0")/bin/jtrac" configure "$@"
