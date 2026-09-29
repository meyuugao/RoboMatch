import type { Metadata } from "next";
import DemoCalculator from "@/components/DemoCalculator";

/**
 * Страница гостевого демо-расчёта: знакомство с платформой без
 * регистрации — выбор типа объекта (демо-набор «Склад»), предзаполненные
 * параметры демо-набора и сравнение трёх сценариев (текущий процесс /
 * покупка / роботы как услуга). Расчёт выполняется в памяти и НЕ
 * сохраняется: для сохранения проектов нужен аккаунт.
 *
 * Страница открыта без входа; данные идут через BFF (/api/demo).
 */
export const metadata: Metadata = {
  title: "Демо-расчёт",
};

export default function DemoPage() {
  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">
          Демо-расчёт
        </h1>
        <p className="mt-2 max-w-3xl text-sm leading-relaxed text-slate-600 dark:text-slate-400">
          Познакомьтесь с платформой без регистрации: выберите тип объекта,
          посмотрите предзаполненные параметры из демо-набора данных и
          сравните три сценария — текущий процесс, покупку оборудования и
          роботов как услугу. Демонстрационный расчёт выполняется в памяти
          и не сохраняется.
        </p>
      </header>
      <DemoCalculator />
    </div>
  );
}
