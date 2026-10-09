#!/bin/sh
# Copies the built app into place with this container's environment in place of the
# build-time placeholders. Starts from the untouched template each time, so a restart
# with new values takes effect.
set -eu

: "${VITE_API_BASE_URL:?VITE_API_BASE_URL must be set}"
: "${VITE_WORKOS_CLIENT_ID:?VITE_WORKOS_CLIENT_ID must be set}"

# The Content-Security-Policy names the API's origin; an API on the app's own address needs none.
case "$VITE_API_BASE_URL" in
    http://* | https://*) VITE_API_ORIGIN=$(printf '%s' "$VITE_API_BASE_URL" | sed -E 's#^(https?://[^/]+).*#\1#') ;;
    *) VITE_API_ORIGIN= ;;
esac

html=/usr/share/nginx/html
rm -rf "${html:?}"/*
cp -R /usr/share/nginx/template/. "$html"

for name in VITE_API_BASE_URL VITE_API_ORIGIN VITE_WORKOS_CLIENT_ID VITE_PRIMEUI_LICENSE_KEY; do
    eval "value=\${$name:-}"
    escaped=$(printf '%s' "$value" | sed 's/[\\|&]/\\&/g')
    find "$html" -type f \( -name '*.js' -o -name '*.html' \) \
        -exec sed -i "s|__${name}__|${escaped}|g" {} +
done
