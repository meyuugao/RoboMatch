# RoboMatch Frontend - Next.js 16.3 (App Router) + TypeScript + Tailwind

Веб-интерфейс RoboMatch: каталог решений, проекты с параметрами объекта,
подбором, сценариями и экономикой, имитацией 2D + KPI, экспортом отчётов;
админка каталога. Серверный рендер (React Server Components) с клиентскими
компонентами для интерактива; данные - из Spring Boot через BFF-прокси.
Тёмная и светлая темы, интерфейс - русский.

## Структура

```
frontend/
├── app/
│   ├── layout.tsx              # корневой layout: Header + Footer, темы, метаданные
│   ├── page.tsx                # главная: описание платформы + ссылки
│   ├── (auth)/login/, register/ # вход и регистрация
│   ├── catalog/                # каталог: список - поиск, фильтры,
│   │   │                       #   сортировка, пагинация, выбор для сравнения
│   │   ├── [id]/               #   карточка решения: ТТХ, кейсы, провенанс
│   │   └── compare/            #   сравнение 2–10 решений (URL + localStorage)
│   ├── projects/               # проекты (после входа): список, создание,
│   │   └── [id]/               #   карточка и разделы: параметры объекта,
│   │                           #   подбор решений, сценарии и расчёт, экономика
│   │                           #   (сравнение, чувствительность, допущения),
│   │                           #   имитация 2D + KPI, экспорт отчётов
│   ├── admin/                  # админка (роль admin): дашборд, справочники,
│   │                           #   решения каталога, импорт таблицы каталога
│   ├── api/                    # BFF-роуты: прокси к Spring Boot - auth,
│   │                           #   solutions, filters, projects/**, admin/**
│   └── globals.css             # Tailwind CSS 4
├── components/                 # серверные и клиентские ('use client') компоненты
│   ├── admin/, compare/        #   группы: админка, сравнение решений
├── lib/
│   ├── api.ts                  # серверные функции чтения - к своим BFF-роутам
│   ├── auth.ts                 # сессия: httpOnly-cookie, requireUser/requireAdmin
│   ├── admin-bff.ts            # общий прокси админских BFF-роутов
│   ├── catalog-url.ts          # сборка/разбор URL каталога (фильтры в query)
│   └── compare.ts              # набор сравнения в localStorage (до 10)
├── types/                      # TypeScript-типы - зеркало backend-DTO
└── Dockerfile                  # multi-stage сборка для docker-compose
```

## Как идут данные (BFF - Backend-for-Frontend)

```
страница (сервер, Node.js)
   -> lib/api.ts: fetchSolutions() и др.
   -> GET /api/**                (свой роут в Next.js, app/api/...)
   -> GET http://localhost:8080/api/**   (Spring Boot)
   -> PostgreSQL
```

Страницы не знают адрес Java-сервиса - его знает только BFF-слой
(переменная `BACKEND_URL`). В docker-compose это `http://backend:8080`
(задаётся в docker-compose.yml, сервис frontend). Для локальной разработки
без Docker переменные описаны в корневом `.env.example` (Next.js из
`frontend/` читает их из shell или `.env.local`). Свой адрес Next.js
`lib/api.ts` определяет автоматически из заголовков запроса, поэтому
работает любой порт и reverse-proxy. Авторизованные запросы: JWT лежит в
httpOnly-cookie, BFF-роут подставляет `Authorization: Bearer` - браузер
токен не видит.

## Запуск

Требуется: Node.js 20+ (проверено на 24), запущенный backend на :8080.

```bash
cd frontend
npm install          # или: bun install
npm run dev          # http://localhost:3000
```

Переменные окружения (необязательно, есть дефолты): `BACKEND_URL` для
нестандартного адреса backend - пример в корневом `.env.example`.

## Проверка

- http://localhost:3000 - главная с описанием и ссылками;
- http://localhost:3000/catalog - каталог: фильтры, сортировка, карточки
  (187 решений после seed);
- http://localhost:3000/catalog/1 - детальная карточка решения;
- http://localhost:3000/projects - проекты; без сессии - redirect на
  /login;
- `curl http://localhost:3000/api/solutions` - BFF отдаёт JSON каталога.

Если каталог показывает «Ошибка загрузки» - backend не запущен на :8080
или БД недоступна (сообщение подсказывает, что проверить).

## Сборка

```bash
npm run build && npm start   # прод-режим (порт 3000)
```

В Docker: `npm run build` дополнительно складывает минимальный сервер в
`.next/standalone` (включён `output: "standalone"` в next.config.ts) -
его запускает `frontend/Dockerfile`. Локально на это можно не обращать
внимания.
