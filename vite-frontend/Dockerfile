FROM node:20.19.0 AS builder

WORKDIR /app

ARG PNPM_VERSION=11.7.0
ARG NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_FALLBACK_REGISTRY=https://registry.npmmirror.com

RUN set -eux; \
    npm config set fetch-retries 5; \
    npm config set fetch-retry-mintimeout 20000; \
    npm config set fetch-retry-maxtimeout 120000; \
    npm config set registry "${NPM_REGISTRY}"; \
    npm install -g "pnpm@${PNPM_VERSION}" --registry="${NPM_REGISTRY}" \
      || npm install -g "pnpm@${PNPM_VERSION}" --registry="${NPM_FALLBACK_REGISTRY}"

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm config set registry "${NPM_REGISTRY}" \
    && (pnpm install --frozen-lockfile --network-timeout 600000 \
      || (pnpm config set registry "${NPM_FALLBACK_REGISTRY}" \
        && pnpm install --frozen-lockfile --network-timeout 600000))

COPY . .
RUN pnpm run build

FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
