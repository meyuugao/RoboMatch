# Dockerfile backend RoboMatch - двухступенчатая сборка (multi-stage).
#
# Стадия 1 (build): Gradle 9.7.1 собирает jar в контейнере с JDK 21.
# Стадия 2 (run):   лёгкий образ только с JRE 21 и готовым jar.
# Итоговый образ ~250 МБ вместо ~800 МБ с JDK.

# ---------- Стадия 1: сборка ----------
FROM gradle:9.7.1-jdk21 AS build

# В образе gradle работа идёт от пользователя gradle (home: /home/gradle),
# поэтому исходники копируем в его домашний каталог с нужным владельцем.
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts /home/gradle/src/
COPY --chown=gradle:gradle gradle /home/gradle/src/gradle
WORKDIR /home/gradle/src

# 1) Качаем зависимости отдельным слоем: пока build.gradle.kts не меняется,
#    Docker использует кэш - пересборка занимает секунды, а не минуты.
#    (--no-daemon: демон Gradle в контейнере не нужен и только мешает)
RUN gradle --no-daemon dependencies

# 2) Копируем исходники и собираем (тесты прогоняются задачей build).
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon clean build

# ---------- Стадия 2: запуск ----------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# curl ставим ДО понижения прав - apt требует root. Нужен только для
# healthcheck в docker-compose (зонд /api/solutions: HTTP 200 =
# Spring полностью поднялся и Flyway применил схему).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Не от root - базовая гигиена контейнеров.
RUN useradd --system --uid 1001 robomatch
USER robomatch

COPY --from=build /home/gradle/src/build/libs/robomatch-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
