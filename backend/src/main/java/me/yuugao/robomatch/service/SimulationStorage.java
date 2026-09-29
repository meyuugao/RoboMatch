package me.yuugao.robomatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Файловое хранилище сохранённых 2D-схем имитации ( — экспорт
 * или сохранение визуализаций: здесь только сохранение схемы
 * как SVG-файла, полный экспорт PDF/Excel — модуль export). Модель —
 * {@code AttachmentStorage}: локальный диск под корнем
 * SIM_ROOT (env, дефолт ./data/simulations), путь задаётся ТОЛЬКО
 * серверными идентификаторами — path traversal исключён конструкцией.
 *
 * <p>СТРУКТУРА: {root}/{projectId}/{simulationResultId}.svg; в БД
 * хранится ОТНОСИТЕЛЬНЫЙ путь (kpi_json.exportUrl — ссылка на API,
 * файл отдаёт SimulationController).
 *
 * <p>БЕЗОПАСНОСТЬ содержимого: SVG
 * приходит от клиента и открывается пользователем как файл —
 * разметка обязана быть безопасной к открытию в браузере. Регексп-блэклист
 * обходился (foreignObject+iframe srcdoc выполнялся БЕЗ клика,
 * javascript:-href, SMIL set/animate, незакавыченные on*, use data:).
 * Теперь — КОНСТРУКТИВНАЯ санитизация на DOM-парсере:
 * <ul>
 * <li>разметка обязана быть well-formed XML (битая — 400
 * IllegalArgumentException от парсера);</li>
 * <li>ALLOWLIST тегов: всё кроме статичной 2D-графики удаляется —
 * script, foreignObject, use, set/animate (SMIL), iframe,
 * style и прочее;</li>
 * <li>атрибуты: все on* удаляются ПО ИМЕНИ (кавычки не важны),
 * href/xlink:href/src со значением javascript: или data:
 * удаляются, srcdoc удаляется;</li>
 * <li>XXE исключён: парсер без DTD и внешних сущностей.</li>
 * </ul>
 * Схема, которую сериализует клиент (SimulationView), — статичная
 * графика; после санитизации остаётся той же картинкой.
 */
@Component
public class SimulationStorage {

    private static final Logger log =
            LoggerFactory.getLogger(SimulationStorage.class);

    /**
 * Разрешённые элементы — только статичная 2D-графика SVG
 * (список тегов, которые генерирует клиентская схема, плюс
 * базовые примитивы). Всё остальное удаляется ЦЕЛИКОМ.
 */
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "svg", "g", "defs", "marker", "title", "desc",
            "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "path", "text", "tspan", "stop", "lineargradient",
            "radialgradient", "pattern", "filter", "clippath", "mask",
            "symbol", "image",
            // декоративный примитив фильтра тени (редизайн схемы
            // имитации): чисто декларативный, без скриптов и внешних
            // ресурсов — клиентская схема использует его для роботов
            "fedropshadow");

    /**
 * Атрибуты-ссылки, где допустим только безопасный URI.
 */
    private static final Set<String> URI_ATTRIBUTES = Set.of(
            "href", "xlink:href", "src");

    /**
 * Значения URI, которые выполняют код — удаляются.
 */
    private static final Set<String> DANGEROUS_URI_PREFIXES = Set.of(
            "javascript:", "data:", "vbscript:");

    /**
 * URI внешних ресурсов — вырезаются: схема
 * самодостаточна, внешние запросы при открытии файла не нужны
 * (http/https/file — трекинг и локальные файлы). Разрешены только
 * фрагменты «#id» и относительные ссылки без схемы.
 */
    private static final Set<String> EXTERNAL_URI_PREFIXES = Set.of(
            "http:", "https:", "file:", "ftp:");

    /**
 * Атрибуты со встроенным CSS — удаляются целиком:
 * url(…) в style тянет внешние ресурсы, клиентская схема стили
 * атрибутами не использует.
 */
    private static final Set<String> STYLE_ATTRIBUTES = Set.of("style");

    /**
 * url(…) с внешней/исполняющей схемой в значении ЛЮБОГО атрибута
 * (fill/filter и т.п.) — вырезается: url(#id) — легитимная
 * внутренняя ссылка, url(http://…) — внешняя загрузка
 * (трекинг/SSRF-проба на старых рендерерах).
 */
    private static final Pattern EXTERNAL_URL = Pattern.compile(
            "url\\(\\s*[\"']?\\s*(javascript:|data:|vbscript:|http:|https:|file:|ftp:)");

    private final Path root;

    /**
 * Создаёт хранилище на локальном диске.
 *
 * @param root корень SIMULATION_ROOT (env, дефолт ./data/simulations)
 */
    public SimulationStorage(
            @Value("${app.simulation.root:./data/simulations}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /**
 * Конструктивная санитизация: парсим как XML (битая разметка —
 * ошибка), удаляем запрещённые элементы целиком (script,
 * foreignObject, use, SMIL, iframe, style...) и опасные атрибуты
 * (все on*, javascript:/data:-ссылки, srcdoc, инлайн-CSS,
 * внешние http:/file:-ссылки). Возвращает сериализованную
 * безопасную схему.
 */
    static String sanitize(String svg) {
        Document document = parse(svg);
        clean(document.getDocumentElement());
        return serialize(document);
    }

    private static Document parse(String svg) {
        try {
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();
            // XXE исключён: без DTD, внешних и параметрических сущностей
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(
                            svg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Схема не является корректным XML (SVG): "
                            + ex.getMessage());
        }
    }

    /**
 * Рекурсивная чистка элемента: запрещённые теги и атрибуты.
 */
    private static void clean(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i -= 1) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                String tag = childElement.getTagName().toLowerCase(
                        Locale.ROOT);
                // локальное имя без префикса (svg:rect и т.п.)
                String local = childElement.getLocalName() == null
                        ? tag
                        : childElement.getLocalName().toLowerCase(
                        Locale.ROOT);
                if (!ALLOWED_TAGS.contains(tag)
                        && !ALLOWED_TAGS.contains(local)) {
                    child.getParentNode().removeChild(child);
                    continue;
                }
                clean(childElement);
            }
        }
        cleanAttributes(element);
    }

    // ------------------------------------------------------------------
    // Санитизация (DOM allowlist)
    // ------------------------------------------------------------------

    /**
 * Атрибуты: on* и srcdoc — всегда; URI-атрибуты — только без
 * исполняющих схем (javascript:/data:/vbscript:) и без внешних
 * (http:/https:/file:/ftp:); инлайн-CSS (style) — целиком;
 * url(…) с внешней/исполняющей схемой — в любом атрибуте
 * (внутренние url(#id) сохраняются).
 */
    private static void cleanAttributes(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = attributes.getLength() - 1; i >= 0; i -= 1) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase(
                    Locale.ROOT);
            String value = attribute.getNodeValue() == null ? ""
                    : attribute.getNodeValue().trim().toLowerCase(
                    Locale.ROOT);
            boolean dangerous = name.startsWith("on")
                    || "srcdoc".equals(name)
                    || STYLE_ATTRIBUTES.contains(name)
                    || (URI_ATTRIBUTES.contains(name) && unsafeUri(value))
                    || EXTERNAL_URL.matcher(value).find();
            if (dangerous) {
                element.removeAttribute(attribute.getNodeName());
            }
        }
    }

    /**
 * URI небезопасен: исполняет код либо ведёт на внешний ресурс.
 */
    private static boolean unsafeUri(String value) {
        return DANGEROUS_URI_PREFIXES.stream().anyMatch(value::startsWith)
                || EXTERNAL_URI_PREFIXES.stream().anyMatch(value::startsWith);
    }

    private static String serialize(Document document) {
        try {
            var transformer = javax.xml.transform.TransformerFactory
                    .newInstance().newTransformer();
            transformer.setOutputProperty(
                    javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,
                    "yes");
            var source = new javax.xml.transform.dom.DOMSource(document);
            var output = new java.io.StringWriter();
            transformer.transform(source, new javax.xml.transform.stream
                    .StreamResult(output));
            return output.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Не удалось сериализовать санитизированную схему", ex);
        }
    }

    /**
 * Сохранить SVG-схему: {root}/{projectId}/{simulationId}.svg.
 * Возвращает ОТНОСИТЕЛЬНЫЙ путь (для БД). Содержимое санитизируется
 * конструктивно (DOM allowlist — см. javadoc класса).
 *
 * @param projectId идентификатор проекта (каталог)
 * @param simulationId идентификатор запуска имитации (имя файла)
 * @param svg текст SVG-схемы от клиента
 * @return относительный путь для БД
 * @throws IllegalArgumentException битая разметка (не XML) —
 * на сервисном уровне превращается в 400
 */
    public String saveSchema(Long projectId, Long simulationId, String svg) {
        String clean = sanitize(svg);
        String relative = projectId + "/" + simulationId + ".svg";
        try {
            Path target = root.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, clean, StandardCharsets.UTF_8);
            return relative;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Не удалось сохранить схему имитации", ex);
        }
    }

    /**
 * Прочитать сохранённую схему (относительный путь из БД);
 * null — файл не существует (404 вызывающего).
 *
 * @param relativePath относительный путь из БД
 * @return текст SVG или null, если файла нет
 */
    @Nullable
    public String readSchema(String relativePath) {
        try {
            Path target = resolve(relativePath);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Не удалось прочитать схему имитации", ex);
        }
    }

    /**
 * Удалить все схемы проекта (удаление проекта).
 *
 * @param projectId идентификатор проекта (каталог в хранилище)
 */
    public void deleteProjectFiles(Long projectId) {
        Path dir = root.resolve(String.valueOf(projectId));
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    log.warn("Не удалось удалить файл схемы {}: {}",
                            p, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("Не удалось удалить каталог схем {}: {}",
                    dir, ex.getMessage());
        }
    }

    /**
 * Абсолютный путь с проверкой выхода за корень (защита в глубину).
 */
    private Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Недопустимый путь файла схемы");
        }
        return target;
    }
}
