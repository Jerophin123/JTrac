#!/bin/sh
# JTRAC: restart (same as: bin/jtrac restart)
exec "$(dirname "$0")/bin/jtrac" restart "$@"
