package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.dto.CatalogImportSummaryDto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Сериализация summary импорта каталога в admin_import_log.summary_json
 * и обратно (Jackson 2 — локальный экземпляр: Boot 4 работает на Jackson 3
 * и не даёт бина Jackson 2, паттерн как в ScenarioService).
 * <p>
 * В JSON кладётся стабильный набор полей (без Instant-дат: started_at /
 * finished_at и так колонки admin_import_log) — Jackson 2 без JSR310-модуля
 * не умеет Instant, а тянуть модуль ради двух технических полей незачем.
 */
public final class CatalogJson {

    /**
 * Переиспользуемый потокобезопасный маппер.
 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private CatalogJson() {
    }

    /**
 * Summary -> JSON-строка для admin_import_log.summary_json.
 *
 * @param summary счётчики импорта каталога
 * @return JSON-строка без дат (даты — колонки admin_import_log)
 */
    public static String toJson(CatalogImportSummaryDto summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", summary.getImportId());
        out.put("fileName", summary.getFileName());
        out.put("status", summary.getStatus());
        out.put("csvRows", summary.getCsvRows());
        out.put("solutions", summary.getSolutions());
        out.put("dupGroups", summary.getDupGroups());
        Map<String, Object> entities = new LinkedHashMap<>();
        summary.getEntities().forEach((entity, counters) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("added", counters.getAdded());
            values.put("updated", counters.getUpdated());
            values.put("skipped", counters.getSkipped());
            entities.put(entity, values);
        });
        out.put("entities", entities);
        out.put("warnings", summary.getWarnings());
        try {
            return JSON.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сериализовать summary импорта",
                    ex);
        }
    }

    /**
 * summary_json -> Map (для AdminImportDto); битый JSON -> null.
 * <p>
 * H2 (тесты) оборачивает строку в jsonb-скаляр — «{"a":1}» хранится
 * как строка с кавычками; PostgreSQL читает как есть. Оба варианта
 * разбираются: первый парсинг даёт Map (PostgreSQL) либо String (H2)
 * — во втором случае парсим ещё раз.
 *
 * @param json содержимое summary_json (или null)
 * @return карта полей summary или null (битый/пустой JSON)
 */
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            if (parsed instanceof String inner) {
                Object second = JSON.readValue(inner, Object.class);
                if (second instanceof Map<?, ?> map) {
                    return (Map<String, Object>) map;
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }
}
