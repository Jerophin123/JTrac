#!/bin/sh
# JTRAC: setup (same as: bin/jtrac setup)
exec "$(dirname "$0")/bin/jtrac" setup "$@"
