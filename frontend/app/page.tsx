import Link from "next/link";

/**
 * Главная страница: что за платформа и куда идти дальше.
 * Серверный компонент без данных — рендерится мгновенно.
 * Без внутренних статусов готовности: клиентские страницы нейтральны,
 * статусы — только в docs/*.
 */
export default function HomePage() {
  return (
    <div className="flex flex-col gap-10">
      {/* Превью-секция: тёмная плашка в светлой теме,
 в тёмной инвертируется в светлую карточку */}
      <section className="rounded-2xl bg-slate-900 px-8 py-12 text-white dark:bg-slate-100 dark:text-slate-900">
        <h1 className="max-w-3xl text-3xl leading-tight font-bold sm:text-4xl">
          Подбор роботизированных решений с расчётом экономического эффекта
        </h1>
        <p className="mt-4 max-w-2xl text-slate-300 dark:text-slate-600">
          RoboMatch помогает до инвестиций оценить роботизацию объекта:
          выбрать тип объекта, подобрать решения из каталога и сравнить
          сценарии CAPEX / OPEX / окупаемость.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <Link
            href="/catalog"
            className="rounded-lg bg-emerald-500 px-5 py-3 text-sm font-semibold text-white hover:bg-emerald-400"
          >
            Открыть каталог решений
          </Link>
          <Link
            href="/demo"
            className="rounded-lg border border-slate-600 px-5 py-3 text-sm font-semibold text-slate-200 hover:bg-slate-800
                       dark:text-slate-800 dark:hover:bg-slate-200"
          >
            Демо-расчёт без регистрации
          </Link>
          <Link
            href="/projects"
            className="rounded-lg border border-slate-600 px-5 py-3 text-sm font-semibold text-slate-200 hover:bg-slate-800
                       dark:text-slate-800 dark:hover:bg-slate-200"
          >
            Мои проекты
          </Link>
        </div>
      </section>

      {/* Три «кирпичика» платформы */}
      <section aria-labelledby="how-it-works">
        <h2 id="how-it-works" className="text-xl font-semibold">
          Как это устроено
        </h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-3">
          {[
            {
              title: "Каталог",
              text: "Роботизированные решения с ТТХ, ценами и источниками данных: 187 позиций из каталога решений.",
            },
            {
              title: "Подбор",
              text: "Фильтрация по параметрам объекта (склад, аэропорт, медицина) с объяснением включения и исключения решений.",
            },
            {
              title: "Экономика",
              text: "CAPEX, OPEX, TCO, ROI и окупаемость по сценариям: базовый, покупка, RaaS (робот как сервис).",
            },
          ].map((item) => (
            <article
              key={item.title}
              className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900"
            >
              <h3 className="font-semibold text-slate-900 dark:text-slate-100">{item.title}</h3>
              <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">{item.text}</p>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
