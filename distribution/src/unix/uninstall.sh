#!/bin/sh
# JTRAC: uninstall (same as: bin/jtrac uninstall)
exec "$(dirname "$0")/bin/jtrac" uninstall "$@"
