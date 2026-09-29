/**
 * Типы API параметров объекта.
 * Форма — по метаданным object_type_parameter + parameter_type;
 * currentValue/currentValue.kind соответствуют backend TypedValueDto.
 */

export type ParameterValueType = "number" | "boolean" | "text";

/** Типизированное значение: kind = number | text | bool. */
export type TypedValue = {
  kind: ParameterValueType | "bool";
  value: number | string | boolean | null;
};

/** Параметр проекта с метаданными и текущим значением (GET /parameters). */
export type ParameterDto = {
  id: number;
  code: string;
  name: string;
  unit: string | null;
  groupName: string;
  valueType: ParameterValueType;
  isRequired: boolean;
  /** Фиксированный параметр-константа: не редактируется. */
  isFixed: boolean;
  /** Производный параметр: считается из derivedFromName. */
  isDerived: boolean;
  /** Имя параметра-источника производного (null — параметр не производный). */
  derivedFromName: string | null;
  defaultValue: TypedValue | null;
  minValue: number | null;
  maxValue: number | null;
  sourceNote: string | null;
  currentValue: TypedValue | null;
  updatedAt: string | null;
};

/** Тело PUT /parameters/{parameterId}. */
export type ParameterSetRequest = {
  value: number | string | boolean | null;
};

/** Успех импорта (POST /parameters/import). */
export type ImportResult = {
  importedCount: number;
  attachmentId: number | null;
  warnings: string[];
};

/** Ошибка импорта: строка/колонка/параметр/причина. */
export type ImportError = {
  row: number;
  column: number;
  parameterCode: string | null;
  message: string;
};

/** Тело 400-ответа при ошибках валидации файла. */
export type ImportFailure = {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  errors: ImportError[];
};

/** Вложение проекта (исходник параметров). */
export type Attachment = {
  id: number;
  fileName: string;
  mimeType: string | null;
  sizeBytes: number | null;
  uploadedAt: string;
};
