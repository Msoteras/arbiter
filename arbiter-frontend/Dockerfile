# Build context: project root, same as the backend images.
# docker build -t arbiter-frontend-img -f arbiter-frontend/Dockerfile .
#
# This image is the only one that gets a public domain. It serves the compiled SPA and
# reverse-proxies /api/v1/* to the backends over the platform's private network, which is what
# lets the frontend keep using relative paths (see src/environments/environment.ts) instead of
# hardcoding a host per module.

# --- Stage 1: build the SPA ---
FROM node:22-alpine AS build

WORKDIR /build

# Manifests first, so the dependency layer is cached independently of the source.
COPY arbiter-frontend/package.json arbiter-frontend/package-lock.json ./
RUN npm ci

COPY arbiter-frontend/ ./
RUN npm run build -- --configuration production

# --- Stage 2: serve ---
# nginx-unprivileged and not the stock nginx image: the stock one runs its master process as root.
# It is the same nginx with the same /etc/nginx/templates envsubst entrypoint, just non-root.
FROM nginxinc/nginx-unprivileged:alpine

# The Angular `application` builder emits the browser bundle under browser/.
COPY --from=build /build/dist/arbiter-frontend/browser /usr/share/nginx/html

# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/default.conf at startup.
COPY arbiter-frontend/nginx.conf.template /etc/nginx/templates/default.conf.template

# Not a .template and not under conf.d/ — see the header comment in the file itself.
COPY arbiter-frontend/arbiter-proxy-headers.conf /etc/nginx/arbiter-proxy-headers.conf

# Without this filter envsubst would also eat nginx's OWN variables ($uri, $host, $remote_addr…)
# and leave them blank, which breaks routing in ways that are painful to read in a config that
# looks correct. Only the names listed here get substituted.
ENV NGINX_ENVSUBST_FILTER="^(PORT|NGINX_LOCAL_RESOLVERS|AUTH_SERVICE_URL|CASES_SERVICE_URL|CLASSIFICATION_SERVICE_URL|RULES_SERVICE_URL)$"

# Opt-in flag that makes the image's 15-local-resolvers.envsh export NGINX_LOCAL_RESOLVERS from
# /etc/resolv.conf. The `resolver` directive in the template needs it, and taking it from the
# container's own resolv.conf beats hardcoding a platform-specific DNS address.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

# Defaults match the service names on the platform's private network; override per environment.
ENV PORT=8080 \
    AUTH_SERVICE_URL=http://auth-service.railway.internal:8080 \
    CASES_SERVICE_URL=http://cases-service.railway.internal:8083 \
    CLASSIFICATION_SERVICE_URL=http://classification-service.railway.internal:8082 \
    RULES_SERVICE_URL=http://rules-service.railway.internal:8081

EXPOSE 8080
