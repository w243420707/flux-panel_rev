#!/usr/bin/env bash
set -Eeuo pipefail

cat >&2 <<'EOF'
proxy.sh is deprecated.

Nginx reverse proxy, HTTPS certificate request, and certificate renewal are now
handled by deploy.sh. Please run the unified deploy command and use the
interactive menu instead.
EOF

exit 1
