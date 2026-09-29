# Dockerfile seed-загрузчика каталога - одноразовый сервис docker-compose.
#
# Запускает scripts/seed/run.py внутри контейнера: python:3.12-slim +
# зависимости из requirements.txt. Схему НЕ создаёт и НЕ меняет -
# ожидает, что backend уже применил Flyway-миграции (V1 схема + V2 демо).
# Seed идемпотентен (INSERT .. ON CONFLICT): повторный запуск обновляет
# те же 187 строк, дублей не создаёт.
#
# Переменные задаёт docker-compose:
#   DATABASE_URL - подключение к сервису db (SQLAlchemy-формат);
#   CATALOG_CSV, OBJECTS_XLSX - файлы организатора (read-only том).

FROM python:3.12-slim

WORKDIR /seed

# 1) Зависимости отдельным слоем: пока requirements.txt не меняется,
#    pip не качается заново при правках кода seed.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 2) Код seed (config.py поднимется на уровень /seed - как в репозитории).
COPY . .

# Не от root - базовая гигиена контейнеров.
RUN useradd --system --uid 1001 seeder && chown -R seeder:seeder /seed
USER seeder

ENTRYPOINT ["python", "run.py"]
