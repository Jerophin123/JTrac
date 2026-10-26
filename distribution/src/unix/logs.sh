#!/bin/sh
# JTRAC: logs (same as: bin/jtrac logs)
exec "$(dirname "$0")/bin/jtrac" logs "$@"
