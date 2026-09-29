package me.yuugao.robomatch.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Генерация шаблона импорта параметров. Формат -
 * «шаблон wide»: строка 1 - коды параметров (parameter_type.code),
 * строка 2 - значения пользователя; импорт читает первую строку данных.
 * <p>
 * XLSX - два листа:
 * - «Значения» - строка кодов + пустая строка для заполнения
 * (заливка подсказывает, куда вводить);
 * - «Параметры» - человекочитаемый справочник: название, единица,
 * обязательность, диапазон, значение по умолчанию и ИСТОЧНИК
 * НОРМАТИВА.
 * <p>
 * CSV - строка кодов с разделителем ';' и UTF-8 BOM (Excel русской
 * локали открывает такой файл корректно; парсер импорта BOM срезает).
 * Справочник параметров в CSV не помещается - он уже виден в форме
 * на странице параметров.
 * <p>
 * Имя файла включает код типа объекта: у склада, аэропорта и
 * медучреждения составы параметров разные - так файл сложнее
 * перепутать (ошибка «параметр не относится к типу объекта» при
 * загрузке чужого шаблона всё равно есть - защита в глубину).
 */
@Component
public class ParameterTemplateService {

    /**
 * Шаблон XLSX: лист «Значения» + лист «Параметры».
 *
 * @param objectTypeCode код типа объекта (в имя файла)
 * @param metas метаданные параметров (строки справочника)
 * @return готовый XLSX-файл шаблона
 */
    public static TemplateFile xlsx(String objectTypeCode, List<Meta> metas) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle fillStyle = fillStyle(workbook);

            Sheet values = workbook.createSheet("Значения");
            Row header = values.createRow(0);
            for (int i = 0; i < metas.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(metas.get(i).code());
                cell.setCellStyle(headerStyle);
                values.setColumnWidth(i, 22 * 256);
            }
            Row fill = values.createRow(1);
            for (int i = 0; i < metas.size(); i++) {
                fill.createCell(i).setCellStyle(fillStyle);
            }

            Sheet info = workbook.createSheet("Параметры");
            String[] titles = {"Код", "Название", "Единица", "Обязательный",
                    "Минимум", "Максимум", "По умолчанию", "Источник норматива"};
            Row infoHeader = info.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                Cell cell = infoHeader.createCell(i);
                cell.setCellValue(titles[i]);
                cell.setCellStyle(headerStyle);
                info.setColumnWidth(i, 24 * 256);
            }
            info.setColumnWidth(1, 34 * 256);
            info.setColumnWidth(7, 40 * 256);
            for (int r = 0; r < metas.size(); r++) {
                Meta meta = metas.get(r);
                Row row = info.createRow(r + 1);
                row.createCell(0).setCellValue(meta.code());
                row.createCell(1).setCellValue(meta.name());
                row.createCell(2).setCellValue(nullToEmpty(meta.unit()));
                row.createCell(3).setCellValue(meta.required() ? "да" : "нет");
                row.createCell(4).setCellValue(nullToEmpty(plain(meta.min())));
                row.createCell(5).setCellValue(nullToEmpty(plain(meta.max())));
                row.createCell(6).setCellValue(nullToEmpty(meta.defaultValue()));
                row.createCell(7).setCellValue(nullToEmpty(meta.sourceNote()));
            }
            workbook.write(out);
            return new TemplateFile("parameters_" + objectTypeCode + "_template.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сформировать шаблон XLSX", ex);
        }
    }

    /**
 * Шаблон CSV: строка кодов (разделитель ';', UTF-8 BOM для Excel).
 *
 * @param objectTypeCode код типа объекта (в имя файла)
 * @param metas метаданные параметров (коды в строку 1)
 * @return готовый CSV-файл шаблона
 */
    public static TemplateFile csv(String objectTypeCode, List<Meta> metas) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        for (int i = 0; i < metas.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(metas.get(i).code());
        }
        sb.append("\r\n");
        return new TemplateFile("parameters_" + objectTypeCode + "_template.csv",
                "text/csv;charset=UTF-8",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle fillStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    // ------------------------------------------------------------------
    // Оформление
    // ------------------------------------------------------------------

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
 * Готовый файл шаблона: имя, MIME и содержимое.
 *
 * @param fileName имя файла (включает код типа объекта)
 * @param mimeType MIME-тип (xlsx или csv)
 * @param content байты готового файла
 */
    public record TemplateFile(String fileName, String mimeType, byte[] content) {
    }

    /**
 * Строка справочника «Параметры» (метаданные для листа 2).
 *
 * @param code код параметра (parameter_type.code)
 * @param name человекочитаемое название
 * @param unit единица измерения
 * @param required обязательность заполнения
 * @param min минимум диапазона (null - не задан)
 * @param max максимум диапазона (null - не задан)
 * @param defaultValue значение по умолчанию
 * @param sourceNote источник норматива
 */
    public record Meta(String code, String name, String unit, boolean required,
                       BigDecimal min, BigDecimal max, String defaultValue,
                       String sourceNote) {
    }
}
