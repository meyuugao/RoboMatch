/**
 * Типы экспорта отчётов:
 * DTO ExportDto backend (история и результат генерации) + формат.
 */

export type ExportFormat = "pdf" | "xlsx" | "csv";

/** Одна выгрузка в истории проекта (backend ExportDto). */
export interface ExportDto {
  id: number;
  format: ExportFormat;
  fileName: string;
  sizeBytes: number;
  createdAt: string;
  createdByLogin: string;
  downloadUrl: string;
}

/** Человекочитаемый размер файла для списка истории. */
export function formatFileSize(bytes: number): string {
  if (bytes <= 0) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} Б`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} КБ`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
}

/** Название формата для кнопок и списка. */
export const FORMAT_LABELS: Record<ExportFormat, string> = {
  pdf: "PDF",
  xlsx: "Excel (XLSX)",
  csv: "CSV",
};
