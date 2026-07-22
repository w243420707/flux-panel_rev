ARG FRONTEND_NODE_VERSION=22.13.1
FROM node:${FRONTEND_NODE_VERSION} AS builder

WORKDIR /app

ARG PNPM_VERSION=11.7.0
ARG NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_FALLBACK_REGISTRY=https://registry.npmmirror.com
ARG FRONTEND_BUILD_MAX_OLD_SPACE_SIZE=1536
ARG VITE_LEGACY_BUILD=false

ENV NODE_OPTIONS=--max-old-space-size=${FRONTEND_BUILD_MAX_OLD_SPACE_SIZE}
ENV VITE_LEGACY_BUILD=${VITE_LEGACY_BUILD}

RUN set -eux; \
    npm install -g "pnpm@${PNPM_VERSION}" --registry="${NPM_REGISTRY}" \
      || npm install -g "pnpm@${PNPM_VERSION}" --registry="${NPM_FALLBACK_REGISTRY}"; \
    pnpm config set registry "${NPM_REGISTRY}"; \
    pnpm config set fetchRetries 5; \
    pnpm config set fetchRetryMintimeout 20000; \
    pnpm config set fetchRetryMaxtimeout 120000; \
    pnpm config set fetchTimeout 600000

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm ci \
    || (pnpm config set registry "${NPM_FALLBACK_REGISTRY}" \
      && pnpm ci)

COPY . .
RUN pnpm run build

FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
