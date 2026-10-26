#!/bin/sh
# JTRAC: stop (same as: bin/jtrac stop)
exec "$(dirname "$0")/bin/jtrac" stop "$@"
