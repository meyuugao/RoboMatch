package me.yuugao.robomatch.export;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Растеризация сохранённой 2D-схемы имитации (SVG → PNG) для вставки в
 * PDF-отчёт.
 *
 * <p>ИНСТРУМЕНТ — Apache Batik (Apache 2.0, свободное ПО),
 * чистая Java2D-трансформация без скриптового движка: схема приходит из
 * хранилища ПОСЛЕ серверной санитизации SimulationStorage (DOM-allowlist:
 * script/foreignObject/SMIL/use/iframe удалены, on*-атрибуты сняты,
 * javascript:/data:-ссылки исключены) — Batik получает заведомо
 * статичную графику.
 *
 * <p>ШРИФТЫ (кириллица в подписях зон): в минимальном JRE-контейнере
 * физических шрифтов может не быть — встроенный Noto Sans
 * (OFL, resources/fonts — тот же, что у PDF) регистрируется в
 * GraphicsEnvironment, а всем text-элементам SVG подставляется
 * font-family="Noto Sans" ДО трансформации (препроцессор ниже). Без
 * этого подписи зон отрисовались бы пустыми.
 *
 * <p>ОТКАЗОУСТОЙЧИВОСТЬ: любая ошибка растеризации НЕ валит отчёт —
 * вызывающий (ReportDataBuilder) подставляет текстовую заметку вместо
 * картинки (KPI остаются).
 */
@Component
public class SvgRasterizer {

    private static final Logger log =
            LoggerFactory.getLogger(SvgRasterizer.class);

    /**
 * Масштаб растеризации: 2× — читаемость текста в PDF.
 */
    private static final float SCALE = 2.0f;

    /**
 * Размер по умолчанию (клиентская схема — 960×480, viewBox).
 */
    private static final float DEFAULT_WIDTH = 960f;
    private static final float DEFAULT_HEIGHT = 480f;

    /**
 * viewBox="minX minY width height" — габариты схемы.
 */
    private static final Pattern VIEWBOX = Pattern.compile(
            "viewBox\\s*=\\s*\"([^\"]+)\"");

    /**
 * Самозакрывающийся feDropShadow (SVG 2) с атрибутами.
 */
    private static final Pattern FE_DROP_SHADOW = Pattern.compile(
            "<feDropShadow\\b([^>]*?)/>");

    /**
 * Парный feDropShadow с пустым телом (на случай сериализатора).
 */
    private static final Pattern FE_DROP_SHADOW_PAIRED = Pattern.compile(
            "<feDropShadow\\b([^>]*)>\\s*</feDropShadow>");

    /**
 * Шрифт для подписей схемы (тот же Noto Sans, что и в PDF).
 */
    private static final String FONT_RESOURCE = "/fonts/NotoSans-Regular.ttf";
    private static final String FONT_FAMILY = "Noto Sans";

    /**
 * Регистрация выполнена один раз (флаг видимости потоков не нужен:
 * registerFont идемпотентен, повторная регистрация безвредна).
 */
    private volatile boolean fontRegistered;

    /**
 * Габариты схемы из viewBox (960×480 по умолчанию).
 */
    private static float[] intrinsicSize(String svg) {
        Matcher matcher = VIEWBOX.matcher(svg);
        if (matcher.find()) {
            String[] parts = matcher.group(1).trim().split("[\\s,]+");
            if (parts.length == 4) {
                try {
                    return new float[]{
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])};
                } catch (NumberFormatException ignored) {
                    // битый viewBox — берём дефолт
                }
            }
        }
        return new float[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
    }

    /**
 * Подготовка схемы к растеризации: (1) всем text/tspan подставляется
 * font-family="Noto Sans" — клиентская схема его не задаёт (браузер
 * рисует своим sans-serif, Batik без шрифтов в контейнере молча
 * оставил бы подписи пустыми); (2) SVG 2-значение
 * orient="auto-start-reverse" маркеров заменяется на "auto" —
 * Batik понимает только SVG 1.1 (цифры/auto), иначе NumberFormat
 * Exception и отказ растеризации всей схемы; (3) SVG 2-примитив
 * feDropShadow (тень роботов редизайна схемы) заменяется
 * эквивалентной цепочкой SVG 1.1 (feGaussianBlur + feOffset +
 * feFlood + feComposite + feMerge) — без этого Batik бросает
 * TranscoderException и PDF-отчёт теряет картинку схемы.
 */
    static String injectFontFamily(String svg) {
        if (svg == null || svg.isBlank()) {
            return svg;
        }
        return downgradeFeDropShadow(svg)
                .replace("<text ", "<text font-family=\"" + FONT_FAMILY
                        + "\" ")
                .replace("<tspan ", "<tspan font-family=\"" + FONT_FAMILY
                        + "\" ")
                .replace("auto-start-reverse", "auto");
    }

    /**
 * feDropShadow (SVG 2, Batik не поддерживает) → эквивалент SVG 1.1:
 * размытая/смещённая альфа заливается цветом тени и склеивается с
 * исходной графикой. Атрибуты dx/dy/stdDeviation/flood-color/
 * flood-opacity переносятся (дефолты SVG 2: dx=2, dy=2,
 * stdDeviation=0, чёрный, непрозрачный).
 */
    private static String downgradeFeDropShadow(String svg) {
        String once = replaceAll(FE_DROP_SHADOW_PAIRED, svg);
        return replaceAll(FE_DROP_SHADOW, once);
    }

    private static String replaceAll(Pattern pattern, String svg) {
        Matcher matcher = pattern.matcher(svg);
        if (!matcher.find()) {
            return svg;
        }
        StringBuilder out = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String dx = attr(attrs, "dx", "2");
            String dy = attr(attrs, "dy", "2");
            String std = attr(attrs, "stdDeviation", "0");
            String color = attr(attrs, "flood-color", "#000000");
            String opacity = attr(attrs, "flood-opacity", "1");
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\""
                            + std + "\" result=\"shadowBlur\"/>"
                            + "<feOffset in=\"shadowBlur\" dx=\"" + dx
                            + "\" dy=\"" + dy + "\" result=\"shadowOff\"/>"
                            + "<feFlood flood-color=\"" + color
                            + "\" flood-opacity=\"" + opacity
                            + "\" result=\"shadowFlood\"/>"
                            + "<feComposite in=\"shadowFlood\" in2=\"shadowOff\""
                            + " operator=\"in\" result=\"shadowMask\"/>"
                            + "<feMerge><feMergeNode in=\"shadowMask\"/>"
                            + "<feMergeNode in=\"SourceGraphic\"/></feMerge>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
 * Значение атрибута по имени (или дефолт).
 */
    private static String attr(String attrs, String name, String fallback) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(attrs);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /**
 * Растеризовать SVG в PNG (2× масштаб). Кириллические подписи —
 * встроенным Noto Sans (регистрация + подстановка font-family).
 *
 * @param svg текст SVG-схемы (viewBox задаёт габариты)
 * @return байты PNG в 2× масштабе
 */
    public byte[] rasterize(String svg) {
        registerFont();
        String prepared = injectFontFamily(svg);
        try {
            PNGTranscoder transcoder = new PNGTranscoder();
            float[] size = intrinsicSize(prepared);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH,
                    size[0] * SCALE);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT,
                    size[1] * SCALE);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            transcoder.transcode(
                    new TranscoderInput(new ByteArrayInputStream(
                            prepared.getBytes(StandardCharsets.UTF_8))),
                    new TranscoderOutput(png));
            return png.toByteArray();
        } catch (Exception ex) {
            log.warn("Не удалось растеризовать 2D-схему для PDF-отчёта: {}",
                    ex.toString());
            throw new IllegalStateException("Схема не растеризована", ex);
        }
    }

    /**
 * Регистрация встроенного шрифта в AWT (идемпотентно).
 */
    private void registerFont() {
        if (fontRegistered) {
            return;
        }
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT,
                    getClass().getResourceAsStream(FONT_RESOURCE));
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .registerFont(font);
            fontRegistered = true;
        } catch (Exception ex) {
            // без регистрации подписи могут не отрисоваться, но схема
            // (фигуры) останется — не блокируем отчёт
            log.warn("Шрифт схемы не зарегистрирован: {}", ex.toString());
        }
    }
}
