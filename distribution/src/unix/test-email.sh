#!/bin/sh
# JTRAC: test-email (same as: bin/jtrac test-email)
exec "$(dirname "$0")/bin/jtrac" test-email "$@"
