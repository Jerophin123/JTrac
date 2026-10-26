#!/bin/sh
# JTRAC: status (same as: bin/jtrac status)
exec "$(dirname "$0")/bin/jtrac" status "$@"
