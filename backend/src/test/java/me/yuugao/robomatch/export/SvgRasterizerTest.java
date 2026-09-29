package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.Test;

/**
 * Unit-тест растеризатора 2D-схем: Batik
 * PNG + подготовка разметки (шрифт Noto Sans для кириллицы подписей,
 * SVG 2 orient="auto-start-reverse" → "auto" — Batik понимает только
 * SVG 1.1 — выявлено на сохранённой реальной схеме).
 */
class SvgRasterizerTest {

    /**
 * Реальная схема (структура SimulationView): зоны, текст, маркеры.
 */
    private static final String SCHEMA = """
            <svg viewBox="0 0 960 480" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <marker id="arrow" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z"/>
                </marker>
              </defs>
              <rect x="10" y="10" width="940" height="460" fill="#f8fafc"/>
              <text x="30" y="36" font-size="15">Склад — 2D-схема</text>
              <text x="40" y="90" font-size="13">Приёмка</text>
            </svg>
            """;
    /**
     * Реальная разметка редизайна схемы — тень роботов через SVG 2
     * feDropShadow (как сериализует SimulationView).
     */
    private static final String SCHEMA_WITH_SHADOW = """
            <svg viewBox="0 0 960 480" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <filter id="schema-shadow" x="-40%" y="-40%" width="180%" height="180%">
                  <feDropShadow dx="0" dy="1.5" stdDeviation="2" flood-color="#0f172a" flood-opacity="0.2"/>
                </filter>
              </defs>
              <rect x="10" y="10" width="940" height="460" fill="#f8fafc" filter="url(#schema-shadow)"/>
              <text x="30" y="36" font-size="15">Робот</text>
            </svg>
            """;
    private final SvgRasterizer rasterizer = new SvgRasterizer();

    @Test
    void rasterizeRealSchema_returnsPng() {
        byte[] png = rasterizer.rasterize(SCHEMA);
        assertThat(png).isNotEmpty();
        // PNG-заголовок
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    void prepare_injectsFontAndDowngradesOrient() {
        String prepared = SvgRasterizer.injectFontFamily(SCHEMA);
        assertThat(prepared).contains("<text font-family=\"Noto Sans\" ");
        assertThat(prepared).doesNotContain("auto-start-reverse");
        assertThat(prepared).contains("orient=\"auto\"");
    }

    @Test
    void rasterize_brokenMarkup_failsWithClearError() {
        assertThatThrownBy(() -> rasterizer.rasterize("это не SVG"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Схема не растеризована");
    }

    @Test
    void prepare_nullOrBlank_passthrough() {
        assertThat(SvgRasterizer.injectFontFamily(null)).isNull();
        assertThat(SvgRasterizer.injectFontFamily("")).isEmpty();
    }

    @Test
    void prepare_downgradesFeDropShadowToSvg11() {
        String prepared = SvgRasterizer.injectFontFamily(SCHEMA_WITH_SHADOW);
        // SVG 2-примитив заменён цепочкой SVG 1.1 (Batik 1.17 его не
        // парсит — TranscoderException и потеря схемы в PDF-отчёте)
        assertThat(prepared).doesNotContain("feDropShadow");
        assertThat(prepared).contains("<feGaussianBlur in=\"SourceAlpha\"")
                .contains("stdDeviation=\"2\"")
                .contains("<feOffset in=\"shadowBlur\" dx=\"0\" dy=\"1.5\"")
                .contains("flood-color=\"#0f172a\"")
                .contains("flood-opacity=\"0.2\"")
                .contains("operator=\"in\"")
                .contains("<feMergeNode in=\"SourceGraphic\"/>");
        // внутренняя ссылка на фильтр не тронута
        assertThat(prepared).contains("filter=\"url(#schema-shadow)\"");
    }

    @Test
    void rasterize_schemaWithFeDropShadow_returnsPng() {
        // регресс TZS-1/SEX-1: реальная схема с тенью раньше валила
        // растеризацию всего Batik-конвейера
        byte[] png = rasterizer.rasterize(SCHEMA_WITH_SHADOW);
        assertThat(png).isNotEmpty();
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }
}
