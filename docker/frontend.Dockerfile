# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f docker/frontend.Dockerfile .
# The built files are the same on every CPU, so they are built once on the build machine's platform.
FROM --platform=$BUILDPLATFORM node:25-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Placeholders, swapped for the container's environment when it starts (see frontend-env.sh),
# so one image works for any deployment.
ENV VITE_API_BASE_URL=__VITE_API_BASE_URL__ \
    VITE_WORKOS_CLIENT_ID=__VITE_WORKOS_CLIENT_ID__ \
    VITE_PRIMEUI_LICENSE_KEY=__VITE_PRIMEUI_LICENSE_KEY__
RUN npm run build-only

# Runs as the nginx user (101) rather than root, so it listens on 8080.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 docker/frontend-env.sh /docker-entrypoint.d/40-frontend-env.sh
COPY --from=build /app/dist /usr/share/nginx/template
# frontend-env.sh rewrites the served files at startup.
USER root
RUN chown 101 /usr/share/nginx/html
USER 101
EXPOSE 8080
