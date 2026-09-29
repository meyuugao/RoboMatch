-- V3: функциональный GIN-индекс для поиска каталога (срез 2, ревью).
--
-- Поиск каталога фильтрует LOWER(name) LIKE '%q%' (SolutionRepository.search):
-- существующий idx_solution_name_trgm (V1) построен по выражению «name» и
-- под LOWER(name) не подходит - планировщик PostgreSQL делал seq scan на
-- каждый поиск (при 187 строках незаметно, при росте каталога - узкое
-- место; находка независимого ревью срезов 0-2).
--
-- pg_trgm уже включён в V1; индекс по выражению lower(name) обслуживает
-- и LIKE-шаблоны по подстроке - тот же класс запроса остаётся
-- индексируемым при любом объёме каталога.

CREATE INDEX IF NOT EXISTS idx_solution_name_lower_trgm
    ON solution USING GIN (lower (name) gin_trgm_ops);
