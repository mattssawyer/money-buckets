#!/bin/sh
# Copies the built app into place with this container's environment in place of the
# build-time placeholders. Starts from the untouched template each time, so a restart
# with new values takes effect.
set -eu

: "${VITE_API_BASE_URL:?VITE_API_BASE_URL must be set}"
: "${VITE_CLERK_PUBLISHABLE_KEY:?VITE_CLERK_PUBLISHABLE_KEY must be set}"

html=/usr/share/nginx/html
rm -rf "${html:?}"/*
cp -R /usr/share/nginx/template/. "$html"

for name in VITE_API_BASE_URL VITE_CLERK_PUBLISHABLE_KEY VITE_PRIMEUI_LICENSE_KEY; do
    eval "value=\${$name:-}"
    escaped=$(printf '%s' "$value" | sed 's/[\\|&]/\\&/g')
    find "$html" -type f \( -name '*.js' -o -name '*.html' \) \
        -exec sed -i "s|__${name}__|${escaped}|g" {} +
done
