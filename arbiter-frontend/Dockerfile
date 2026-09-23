# Build context: project root, same as the backend images.
# docker build -t arbiter-frontend-img -f arbiter-frontend/Dockerfile .
#
# The only publicly exposed image: serves the SPA and reverse-proxies /api/v1/* to the backends
# over the private network, so the frontend can keep using relative paths.

# --- Stage 1: build the SPA ---
FROM node:22-alpine AS build

WORKDIR /build

# Manifests first, so the dependency layer is cached independently of the source.
COPY arbiter-frontend/package.json arbiter-frontend/package-lock.json ./
RUN npm ci

COPY arbiter-frontend/ ./
RUN npm run build -- --configuration production

# --- Stage 2: serve ---
# Same nginx and envsubst entrypoint as the stock image, but without a root master process.
FROM nginxinc/nginx-unprivileged:alpine

# The Angular `application` builder emits the browser bundle under browser/.
COPY --from=build /build/dist/arbiter-frontend/browser /usr/share/nginx/html

# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/default.conf at startup.
COPY arbiter-frontend/nginx.conf.template /etc/nginx/templates/default.conf.template

# Not a .template and not under conf.d/: see the header comment in the file.
COPY arbiter-frontend/arbiter-proxy-headers.conf /etc/nginx/arbiter-proxy-headers.conf

# Without this filter envsubst would also blank nginx's own variables ($uri, $host...). Only the
# names listed here get substituted.
ENV NGINX_ENVSUBST_FILTER="^(PORT|NGINX_LOCAL_RESOLVERS|AUTH_SERVICE_URL|CASES_SERVICE_URL|CLASSIFICATION_SERVICE_URL|RULES_SERVICE_URL|REPORTS_SERVICE_URL)$"

# Makes the image export NGINX_LOCAL_RESOLVERS from /etc/resolv.conf for the template's `resolver`
# directive, instead of hardcoding a platform-specific DNS address.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

# Defaults match the service names on the platform's private network; override per environment.
ENV PORT=8080 \
    AUTH_SERVICE_URL=http://auth-service.railway.internal:8080 \
    CASES_SERVICE_URL=http://cases-service.railway.internal:8083 \
    CLASSIFICATION_SERVICE_URL=http://classification-service.railway.internal:8082 \
    RULES_SERVICE_URL=http://rules-service.railway.internal:8081 \
    REPORTS_SERVICE_URL=http://reports-service.railway.internal:8084

EXPOSE 8080
