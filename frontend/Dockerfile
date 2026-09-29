# Dockerfile frontend RoboMatch — двухступенчатая сборка (multi-stage).
#
# Стадия 1 (deps):  npm ci качает зависимости по package-lock.json —
#                   ровно те же версии, что у разработчиков (воспроизводимо;
#                   npm install «свежих» версий не допускаем).
# Стадия 2 (build): next build. В next.config.ts включён output: "standalone" —
#                   в .next/standalone попадает минимальный сервер
#                   (server.js + только нужные node_modules).
# Стадия 3 (run):   лёгкий runtime-образ node:24-alpine со standalone-выходом.
#
# Локальный запуск (npm run dev) с Docker-сборкой не связан: здесь образ
# собирает ТОЛЬКО прод-версию для docker-compose.

# ---------- Стадия 1: зависимости ----------
FROM node:24-alpine AS deps
WORKDIR /app

# Отдельный слой-кэш: пока package*.json не меняются, npm ci не повторяется.
COPY package.json package-lock.json ./
RUN npm ci

# ---------- Стадия 2: сборка ----------
FROM node:24-alpine AS build
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Телеметрию Next.js в CI/сборке отключаем.
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---------- Стадия 3: запуск ----------
FROM node:24-alpine AS run
WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    # standalone-сервер по умолчанию слушает только localhost —
    # в контейнере нужно слушать все интерфейсы, иначе порт не пробросится.
    HOSTNAME=0.0.0.0 \
    PORT=3000

# Не от root — базовая гигиена контейнеров.
RUN addgroup -S nextjs && adduser -S nextjs -G nextjs

# standalone-сервер + статика Next.js (в standalone она НЕ входит —
# копируем рядом вручную, как рекомендует документация Next.js).
COPY --from=build --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /app/.next/static ./.next/static
# Если появится каталог public/ — раскомментируйте строку ниже:
# COPY --from=build --chown=nextjs:nextjs /app/public ./public

# BACKEND_URL задаёт docker-compose (http://backend:8080) — его читает
# BFF-роут app/api/solutions/route.ts. APP_URL не нужен: lib/api.ts
# определяет адрес сам из заголовков запроса.
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
