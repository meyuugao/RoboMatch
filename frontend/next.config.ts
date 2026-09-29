import type { NextConfig } from "next";

/**
 * Конфигурация Next.js.
 *
 * output: "standalone" — официальный Docker-режим Next.js: команда
 * `next build` ДОПОЛНИТЕЛЬНО складывает в `.next/standalone` минимальный
 * сервер (server.js + только нужные node_modules) — именно его запускает
 * frontend/Dockerfile в контейнере. Локальная разработка (`npm run dev`,
 * `npm run build && npm start`) на это не завязана и работает как раньше.
 */
const nextConfig: NextConfig = {
  output: "standalone",
  // Dev-сервер открывают и по 127.0.0.1 (например, E2E против localhost
  // бесполезен при IPv6-first резолве) — без этого Next блокирует
  // HMR-соединение с этого Origin и страница не гидрируется.
  allowedDevOrigins: ["127.0.0.1"],
};

export default nextConfig;
