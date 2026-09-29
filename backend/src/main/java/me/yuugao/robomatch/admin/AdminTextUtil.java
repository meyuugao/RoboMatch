package me.yuugao.robomatch.admin;

/**
 * Нормализация текста - порт scripts/seed/normalizers/strings.py.
 * <p>
 * Правила - data_model.md «Нормализация строк», assumptions.md §10:
 * - ё -> е ТОЛЬКО для ключей дедупликации (canonicalKey), НЕ для
 * отображения: в БД имена хранятся в исходном написании;
 * - типографские тире (em/en-dash, horizontal bar…) -> обычный дефис;
 * - trim + схлопывание повторных пробелов (включая \n и NBSP).
 * <p>
 * Один и тот же код на Python (seed) и Java (админ-импорт) - правила
 * нормализации обязаны совпадать, иначе повторный импорт через админку
 * разъедется с первичным seed-ом (двойные записи справочников).
 */
public final class AdminTextUtil {

    private static final String[][] TRANSLIT = {
            {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"},
            {"е", "e"}, {"ё", "e"}, {"ж", "zh"}, {"з", "z"}, {"и", "i"},
            {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"},
            {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"},
            {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"},
            {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""},
            {"э", "e"}, {"ю", "yu"}, {"я", "ya"},
    };

    private AdminTextUtil() {
    }

    /**
 * Нормализация для хранения: null/пустое -> null (NULL в БД).
 *
 * @param value исходный текст (типографские тире/пробелы - к обычным)
 * @return нормализованный текст или null для пустого
 */
    public static String normText(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replace("\u00a0", " ");
        s = s.replace('\u2014', '-')   // -
                .replace('\u2013', '-')   // –
                .replace('\u2015', '-')   // ―
                .replace('\u2012', '-')   // ‒
                .replace('\u2043', '-')   // ⁃
                .replace('\u2011', '-');  // ‑
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    // --- Транслитерация для кодов новых записей справочников -----------

    /**
 * Ключ дедупликации: normText + lower + ё -> е.
 *
 * @param value исходный текст
 * @return ключ сравнения или null для пустого
 */
    public static String canonicalKey(String value) {
        String s = normText(value);
        if (s == null) {
            return null;
        }
        return s.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
    }

    /**
 * Код справочника для НОВОЙ записи (slugify из strings.py).
 * Существующие записи находят по имени и код не трогают - коды
 * конвенции (code_map.py, assumptions.md §20) уже в БД от seed;
 * транслит - только фолбэк для имён, которых в конвенции нет.
 *
 * @param value имя новой записи справочника
 * @return код-слаг (транслит) или null для пустого имени
 */
    public static String slugify(String value) {
        String key = canonicalKey(value);
        if (key == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            String ch = key.substring(i, i + 1);
            String mapped = null;
            for (String[] pair : TRANSLIT) {
                if (pair[0].equals(ch)) {
                    mapped = pair[1];
                    break;
                }
            }
            if (mapped != null) {
                out.append(mapped);
            } else if (isAsciiAlnum(ch.charAt(0))) {
                out.append(ch);
            } else {
                out.append('_');
            }
        }
        String slug = out.toString().replaceAll("_+", "_");
        while (slug.startsWith("_")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("_") && !slug.isEmpty()) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? null : slug;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
