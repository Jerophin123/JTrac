#!/bin/sh
# JTRAC: backup (same as: bin/jtrac backup)
exec "$(dirname "$0")/bin/jtrac" backup "$@"
