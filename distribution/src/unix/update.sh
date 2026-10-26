#!/bin/sh
# JTRAC: update (same as: bin/jtrac update)
exec "$(dirname "$0")/bin/jtrac" update "$@"
