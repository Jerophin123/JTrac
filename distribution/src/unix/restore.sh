#!/bin/sh
# JTRAC: restore (same as: bin/jtrac restore)
exec "$(dirname "$0")/bin/jtrac" restore "$@"
