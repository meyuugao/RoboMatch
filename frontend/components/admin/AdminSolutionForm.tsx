"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { DictItem, TypeSubtypeLink } from "@/types/solution";
import type { SolutionFull } from "@/types/solution";

/**
 * Форма решения для раздела «Управление» (создание/правка).
 * Клиентский компонент: валидация на клиенте дублирует backend-DTO
 * (границы полей), ошибки сервера (400/409) - из тела ответа.
 *
 * Справочники приходят серверными пропсами: вендоры и регионы - из
 * админских эндпоинтов справочников (фильтры каталога их не отдают),
 * типы/подтипы - из публичного /api/filters (зависимый список).
 *
 * ТТХ-поля формы пишут материализованные колонки карточки; значения EAV
 * (с провенансом) редактируются отдельно на странице карточки.
 */
export default function AdminSolutionForm({
  vendors,
  regions,
  types,
  subtypes,
  typeSubtypeMapping,
  solution,
}: {
  vendors: DictItem[];
  regions: DictItem[];
  types: DictItem[];
  subtypes: DictItem[];
  typeSubtypeMapping: TypeSubtypeLink[];
  solution?: SolutionFull;
}) {
  const router = useRouter();
  const isEdit = solution !== undefined;

  // В карточке решения справочники приходят именами (публичный DTO),
  // а форме нужны id - сопоставляем по имени (имена в справочниках UNIQUE)
  const idByName = (items: DictItem[], name: string | null | undefined): number | "" =>
    name == null
      ? ""
      : (items.find((item) => item.name === name)?.id ?? "");

  const [name, setName] = useState(solution?.name ?? "");
  const [vendorId, setVendorId] = useState<number | "">(
    solution ? idByName(vendors, solution.vendorName) : "",
  );
  const [productClass, setProductClass] = useState<
    "brs" | "bas" | "software"
  >(solution?.productClass ?? "brs");
  const [typeId, setTypeId] = useState<number | "">(
    solution ? idByName(types, solution.solutionTypeName) : "",
  );
  const [subtypeId, setSubtypeId] = useState<number | "">(
    solution ? idByName(subtypes, solution.solutionSubtypeName) : "",
  );
  const [regionId, setRegionId] = useState<number | "">(
    solution ? idByName(regions, solution.regionName) : "",
  );
  const [status, setStatus] = useState<"operation" | "piloting" | "rnd">(
    solution?.status ?? "operation",
  );
  const [description, setDescription] = useState(solution?.description ?? "");
  const [priceRub, setPriceRub] = useState(
    solution?.priceRub != null ? String(solution.priceRub) : "",
  );
  const [trl, setTrl] = useState(
    solution?.trl != null ? String(solution.trl) : "",
  );
  const [marketPotential, setMarketPotential] = useState(
    solution?.marketPotential != null ? String(solution.marketPotential) : "",
  );
  const [tth, setTth] = useState<Record<TthField, string>>({
    payloadKg: numberText(solution?.payloadKg),
    massKg: numberText(solution?.massKg),
    lengthMm: numberText(solution?.lengthMm),
    widthMm: numberText(solution?.widthMm),
    heightMm: numberText(solution?.heightMm),
    positioningAccuracyMm: numberText(solution?.positioningAccuracyMm),
    speedMs: numberText(solution?.speedMs),
    chargingPowerKw: numberText(solution?.chargingPowerKw),
    noiseLevelDba: numberText(solution?.noiseLevelDba),
  });

  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  /** Подтипы, доступные для выбранного типа (пары «тип-подтип»). */
  const allowedSubtypes = typeId === ""
    ? subtypes
    : subtypes.filter((subtype) =>
        typeSubtypeMapping.some(
          (link) => link.typeId === typeId && link.subtypeId === subtype.id,
        ),
      );

  function validate(): string | null {
    if (name.trim().length < 1 || name.trim().length > 256) {
      return "Название решения: от 1 до 256 символов";
    }
    if (vendorId === "") {
      return "Выберите производителя";
    }
    const price = Number(priceRub.replace(",", "."));
    if (!priceRub.trim() || Number.isNaN(price) || price < 0) {
      return "Цена: неотрицательное число";
    }
    if (trl !== "" && (Number(trl) < 1 || Number(trl) > 9)) {
      return "УГТ: от 1 до 9";
    }
    if (
      marketPotential !== "" &&
      (Number(marketPotential) < 2 || Number(marketPotential) > 5)
    ) {
      return "Рыночный потенциал: от 2 до 5";
    }
    for (const [field, value] of Object.entries(tth)) {
      if (value.trim() !== "") {
        const parsed = Number(value.replace(",", "."));
        if (Number.isNaN(parsed) || parsed < 0) {
          return `${TTH_LABELS[field as TthField]}: неотрицательное число`;
        }
      }
    }
    return null;
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    const validationError = validate();
    if (validationError !== null) {
      setError(validationError);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      const body = {
        name: name.trim(),
        vendorId: vendorId === "" ? null : vendorId,
        productClass,
        solutionTypeId: typeId === "" ? null : typeId,
        solutionSubtypeId: subtypeId === "" ? null : subtypeId,
        regionId: regionId === "" ? null : regionId,
        status,
        description: description.trim() === "" ? null : description.trim(),
        priceRub: Number(priceRub.replace(",", ".")),
        trl: trl === "" ? null : Number(trl),
        marketPotential:
          marketPotential === "" ? null : Number(marketPotential),
        payloadKg: numberOrNull(tth.payloadKg),
        massKg: numberOrNull(tth.massKg),
        lengthMm: numberOrNull(tth.lengthMm),
        widthMm: numberOrNull(tth.widthMm),
        heightMm: numberOrNull(tth.heightMm),
        positioningAccuracyMm: numberOrNull(tth.positioningAccuracyMm),
        speedMs: numberOrNull(tth.speedMs),
        chargingPowerKw: numberOrNull(tth.chargingPowerKw),
        noiseLevelDba: numberOrNull(tth.noiseLevelDba),
      };
      const response = await fetch(
        isEdit ? `/api/admin/solutions/${solution.id}` : "/api/admin/solutions",
        {
          method: isEdit ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
      );
      if (!response.ok) {
        throw new Error(await errorMessage(response));
      }
      const saved = (await response.json()) as SolutionFull;
      router.push(`/admin/solutions/${saved.id}`);
    } catch (submitError) {
      setError(
        submitError instanceof Error
          ? submitError.message
          : "Не удалось сохранить решение",
      );
    } finally {
      setSaving(false);
    }
  }

  async function onDelete() {
    if (solution === undefined || !window.confirm(`Удалить «${solution.name}»?`)) {
      return;
    }
    setSaving(true);
    try {
      const response = await fetch(`/api/admin/solutions/${solution.id}`, {
        method: "DELETE",
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response));
      }
      router.push("/admin/solutions");
    } catch (deleteError) {
      setError(
        deleteError instanceof Error
          ? deleteError.message
          : "Не удалось удалить решение",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5">
      {error !== null && (
        <div
          role="alert"
          data-testid="solution-form-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Название *</span>
          <input
            data-testid="solution-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={256}
            required
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          />
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Производитель *</span>
          <select
            data-testid="solution-vendor"
            value={vendorId}
            onChange={(event) =>
              setVendorId(event.target.value === "" ? "" : Number(event.target.value))
            }
            required
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- выбрать -</option>
            {vendors.map((vendor) => (
              <option key={vendor.id} value={vendor.id}>
                {vendor.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Класс продукта *</span>
          <select
            data-testid="solution-class"
            value={productClass}
            onChange={(event) =>
              setProductClass(event.target.value as "brs" | "bas" | "software")
            }
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="brs">БРС - робототехника</option>
            <option value="bas">БАС - беспилотная авиация</option>
            <option value="software">ПО</option>
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Статус *</span>
          <select
            data-testid="solution-status"
            value={status}
            onChange={(event) =>
              setStatus(
                event.target.value as "operation" | "piloting" | "rnd",
              )
            }
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="operation">В эксплуатации</option>
            <option value="piloting">Пилотные проекты</option>
            <option value="rnd">Разработка</option>
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Тип решения</span>
          <select
            data-testid="solution-type"
            value={typeId}
            onChange={(event) => {
              setTypeId(event.target.value === "" ? "" : Number(event.target.value));
              setSubtypeId("");
            }}
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- не указан -</option>
            {types.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Подтип</span>
          <select
            data-testid="solution-subtype"
            value={subtypeId}
            onChange={(event) =>
              setSubtypeId(
                event.target.value === "" ? "" : Number(event.target.value),
              )
            }
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- не указан -</option>
            {allowedSubtypes.map((subtype) => (
              <option key={subtype.id} value={subtype.id}>
                {subtype.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Регион</span>
          <select
            data-testid="solution-region"
            value={regionId}
            onChange={(event) =>
              setRegionId(event.target.value === "" ? "" : Number(event.target.value))
            }
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- не указан -</option>
            {regions.map((region) => (
              <option key={region.id} value={region.id}>
                {region.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Цена, руб. с НДС *</span>
          <input
            data-testid="solution-price"
            inputMode="decimal"
            value={priceRub}
            onChange={(event) => setPriceRub(event.target.value)}
            required
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          />
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">УГТ (TRL), 1–9</span>
          <select
            data-testid="solution-trl"
            value={trl}
            onChange={(event) => setTrl(event.target.value)}
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- не указан -</option>
            {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">
            Рыночный потенциал, 2–5
          </span>
          <select
            data-testid="solution-market"
            value={marketPotential}
            onChange={(event) => setMarketPotential(event.target.value)}
            className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- не указан -</option>
            {[2, 3, 4, 5].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
      </div>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-slate-700 dark:text-slate-300">Описание</span>
        <textarea
          data-testid="solution-description"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          rows={4}
          className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
        />
      </label>

      <fieldset className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
        <legend className="px-2 text-sm font-semibold text-slate-700 dark:text-slate-300">
          Технические характеристики карточки
        </legend>
        <div className="grid gap-4 md:grid-cols-3">
          {(Object.keys(TTH_LABELS) as TthField[]).map((field) => (
            <label key={field} className="flex flex-col gap-1 text-sm">
              <span className="text-slate-600 dark:text-slate-300">{TTH_LABELS[field]}</span>
              <input
                inputMode="decimal"
                value={tth[field]}
                onChange={(event) =>
                  setTth({ ...tth, [field]: event.target.value })
                }
                className="rounded-lg border border-slate-300 px-3 py-2 focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
              />
            </label>
          ))}
        </div>
      </fieldset>

      <div className="flex flex-wrap items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          data-testid="solution-save"
          className="rounded-lg bg-emerald-600 px-5 py-2 text-sm font-medium text-white transition hover:bg-emerald-700 disabled:opacity-50"
        >
          {saving ? "Сохранение…" : "Сохранить"}
        </button>
        {isEdit && (
          <button
            type="button"
            onClick={onDelete}
            disabled={saving}
            data-testid="solution-delete"
            className="rounded-lg border border-rose-300 bg-white px-5 py-2 text-sm font-medium text-rose-700 transition hover:bg-rose-50 disabled:opacity-50 dark:border-rose-800 dark:bg-slate-900 dark:text-rose-300 dark:hover:bg-rose-950/40"
          >
            Удалить решение
          </button>
        )}
      </div>
    </form>
  );
}

// --------------------------------------------------------------------
// Вспомогательные структуры
// --------------------------------------------------------------------

type TthField =
  | "payloadKg"
  | "massKg"
  | "lengthMm"
  | "widthMm"
  | "heightMm"
  | "positioningAccuracyMm"
  | "speedMs"
  | "chargingPowerKw"
  | "noiseLevelDba";

const TTH_LABELS: Record<TthField, string> = {
  payloadKg: "Грузоподъёмность, кг",
  massKg: "Масса, кг",
  lengthMm: "Длина, мм",
  widthMm: "Ширина, мм",
  heightMm: "Высота, мм",
  positioningAccuracyMm: "Точность позиционирования, мм",
  speedMs: "Скорость, м/с",
  chargingPowerKw: "Мощность зарядки, кВт",
  noiseLevelDba: "Уровень шума, дБА",
};

function numberText(value: number | null | undefined): string {
  return value == null ? "" : String(value);
}

function numberOrNull(value: string): number | null {
  return value.trim() === "" ? null : Number(value.replace(",", "."));
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as {
      message?: string;
      error?: string;
    };
    return body.message ?? body.error ?? `Ошибка HTTP ${response.status}`;
  } catch {
    return `Ошибка HTTP ${response.status}`;
  }
}
