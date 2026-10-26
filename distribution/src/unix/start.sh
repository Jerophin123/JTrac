#!/bin/sh
# JTRAC: start (same as: bin/jtrac start)
exec "$(dirname "$0")/bin/jtrac" start "$@"
