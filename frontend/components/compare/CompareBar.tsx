"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { COMPARE_CHANGED_EVENT, getCompareIds } from "@/lib/compare";

/**
 * Плавающая панель «Сравнить (N)»: показывает, сколько решений выбрано
 * для сравнения, и ведёт на /catalog/compare?ids=....
 * Появляется только когда выбрано хотя бы одно решение.
 */
export default function CompareBar() {
  const [count, setCount] = useState(0);
  const [ids, setIds] = useState<number[]>([]);

  useEffect(() => {
    const sync = () => {
      const current = getCompareIds();
      setIds(current);
      setCount(current.length);
    };
    sync();
    window.addEventListener(COMPARE_CHANGED_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(COMPARE_CHANGED_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, []);

  if (count === 0) {
    return null;
  }

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white/95
                 p-3 shadow-lg backdrop-blur dark:border-slate-800 dark:bg-slate-900/95"
    >
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
        <p className="text-sm text-slate-700 dark:text-slate-300">
          Выбрано для сравнения: <span className="font-semibold">{count}</span> из 10
        </p>
        <Link
          href={`/catalog/compare${ids.length >= 2 ? `?ids=${ids.join(",")}` : ""}`}
          className={`rounded-lg px-4 py-2 text-sm font-medium text-white transition
                      ${ids.length >= 2 ? "bg-blue-600 hover:bg-blue-700" : "bg-slate-400 dark:bg-slate-600"}`}
          aria-disabled={ids.length < 2}
        >
          {ids.length >= 2 ? `Сравнить (${count})` : "Нужно минимум 2 решения"}
        </Link>
      </div>
    </div>
  );
}
