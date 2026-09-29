"""ТЕСТОВАЯ схема БД — зеркало docs/data_model.md (все разделы).

Используется ТОЛЬКО тестами (tests/): в проде схему создают миграции Spring Boot,
seed-скрипт её только отражает (reflect) и не меняет. Определения здесь намеренно
повторяют ограничения data_model.md, чтобы тесты ловили нарушения CHECK/UNIQUE.

Часть 1 — каталог и параметры объектов (разделы 2–4);
Часть 2 — домены пользователя (разделы 8–12): user, project,
project_parameter_value, project_attachment, scenario, scenario_solution,
selection_result, calculation, calculation_assumption, manual_adjustment,
simulation_result, export, project_assumption (миграция V5),
admin_import_log (V7).
"""
import sqlalchemy as sa

meta = sa.MetaData()

# PK: BIGINT (как в проде), но INTEGER на SQLite — иначе автоинкремент не работает
ID_TYPE = sa.BigInteger().with_variant(sa.Integer, "sqlite")

vendor = sa.Table(
    "vendor", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
)

industry = sa.Table(
    "industry", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
)

region = sa.Table(
    "region", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
)

solution_type = sa.Table(
    "solution_type", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
)

solution_subtype = sa.Table(
    "solution_subtype", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
)

process = sa.Table(
    "process", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
    sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
)

object_type = sa.Table(
    "object_type", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("is_calc_enabled", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("data_source_note", sa.Text, nullable=True),
)

parameter_type = sa.Table(
    "parameter_type", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("unit", sa.Text, nullable=True),
    sa.Column("value_type", sa.Text, sa.CheckConstraint("value_type IN ('number', 'boolean', 'text')"),
              nullable=False),  # дефолта в V1 нет — зеркало не добавляет своего
)

characteristic_type = sa.Table(
    "characteristic_type", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("code", sa.Text, nullable=False, unique=True),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("group_code", sa.Text,
              sa.CheckConstraint("group_code IN ('identification', 'technical', 'infrastructure', "
                                 "'economic', 'applicability', 'data_quality')"),
              nullable=False),
    sa.Column("data_type", sa.Text,
              sa.CheckConstraint("data_type IN ('number', 'text', 'boolean', 'date')"),
              nullable=False),
    sa.Column("unit", sa.Text, nullable=True),
    sa.Column("is_filterable", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("is_required", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("sort_order", sa.Integer, nullable=False, server_default="0"),
)

solution = sa.Table(
    "solution", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("external_id", sa.Uuid(), nullable=True, unique=True),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("vendor_id", sa.BigInteger, sa.ForeignKey("vendor.id"), nullable=False),
    sa.Column("product_class", sa.Text, sa.CheckConstraint("product_class IN ('brs', 'bas', 'software')"),
              nullable=False),
    sa.Column("solution_type_id", sa.BigInteger, sa.ForeignKey("solution_type.id"), nullable=True),
    sa.Column("solution_subtype_id", sa.BigInteger, sa.ForeignKey("solution_subtype.id"), nullable=True),
    sa.Column("region_id", sa.BigInteger, sa.ForeignKey("region.id"), nullable=True),
    sa.Column("status", sa.Text, sa.CheckConstraint("status IN ('operation', 'piloting', 'rnd')"),
              nullable=False),
    sa.Column("description", sa.Text, nullable=True),
    sa.Column("price_rub", sa.Numeric(15, 2), nullable=False),
    sa.Column("trl", sa.SmallInteger, sa.CheckConstraint("trl BETWEEN 1 AND 9"), nullable=True),
    sa.Column("market_potential", sa.Numeric(3, 1), sa.CheckConstraint("market_potential BETWEEN 2 AND 5"),
              nullable=True),
    sa.Column("payload_kg", sa.Numeric(12, 3), nullable=True),
    sa.Column("mass_kg", sa.Numeric(12, 3), nullable=True),
    sa.Column("length_mm", sa.Numeric(12, 3), nullable=True),
    sa.Column("width_mm", sa.Numeric(12, 3), nullable=True),
    sa.Column("height_mm", sa.Numeric(12, 3), nullable=True),
    sa.Column("positioning_accuracy_mm", sa.Numeric(12, 3), nullable=True),
    sa.Column("speed_m_s", sa.Numeric(12, 3), nullable=True),
    sa.Column("charging_power_kw", sa.Numeric(12, 3), nullable=True),
    sa.Column("noise_level_dba", sa.Numeric(12, 3), nullable=True),
    sa.Column("completeness_pct", sa.SmallInteger, sa.CheckConstraint("completeness_pct BETWEEN 0 AND 100"),
              nullable=True),
    sa.Column("source_kind", sa.Text,
              sa.CheckConstraint("source_kind IN ('organizer_catalog', 'open_source', 'manual')"),
              nullable=False, server_default="organizer_catalog"),
    sa.Column("source_url", sa.Text, nullable=True),
    sa.Column("source_date", sa.Date, nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("vendor_id", "name"),
)

solution_application = sa.Table(
    "solution_application", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("solution_id", sa.BigInteger, sa.ForeignKey("solution.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("industry_id", sa.BigInteger, sa.ForeignKey("industry.id"), nullable=False),
    sa.Column("process_id", sa.BigInteger, sa.ForeignKey("process.id"), nullable=False),
    sa.Column("offer_price_rub", sa.Numeric(15, 2), nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("solution_id", "industry_id", "process_id"),
)

solution_characteristic = sa.Table(
    "solution_characteristic", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("solution_id", sa.BigInteger, sa.ForeignKey("solution.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("characteristic_type_id", sa.BigInteger, sa.ForeignKey("characteristic_type.id"),
              nullable=False),
    sa.Column("value_numeric", sa.Numeric(14, 4), nullable=True),
    sa.Column("value_text", sa.Text, nullable=True),
    sa.Column("value_bool", sa.Boolean, nullable=True),
    sa.Column("value_date", sa.Date, nullable=True),
    sa.Column("source_kind", sa.Text,
              sa.CheckConstraint("source_kind IN ('organizer_catalog', 'open_source', 'manual')"),
              nullable=False, server_default="manual"),
    sa.Column("source_url", sa.Text, nullable=True),
    sa.Column("source_date", sa.Date, nullable=True),
    sa.Column("is_confirmed", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.CheckConstraint(
        "(CASE WHEN value_numeric IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN value_text IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN value_bool IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN value_date IS NOT NULL THEN 1 ELSE 0 END) = 1",
        name="solution_characteristic_one_value_check"),
    sa.UniqueConstraint("solution_id", "characteristic_type_id"),
)

solution_case = sa.Table(
    "solution_case", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("name", sa.Text, nullable=False, unique=True),
    sa.Column("description", sa.Text, nullable=True),
    sa.Column("source_url", sa.Text, nullable=True),
    sa.Column("source_date", sa.Date, nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
)

solution_case_link = sa.Table(
    "solution_case_link", meta,
    sa.Column("solution_id", sa.BigInteger, sa.ForeignKey("solution.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("case_id", sa.BigInteger, sa.ForeignKey("solution_case.id", ondelete="CASCADE"),
              nullable=False),
    sa.PrimaryKeyConstraint("solution_id", "case_id"),
)

solution_type_subtype_mapping = sa.Table(
    "solution_type_subtype_mapping", meta,
    sa.Column("solution_type_id", sa.BigInteger, sa.ForeignKey("solution_type.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("solution_subtype_id", sa.BigInteger,
              sa.ForeignKey("solution_subtype.id", ondelete="CASCADE"), nullable=False),
    sa.Column("source_note", sa.Text, nullable=True),
    sa.PrimaryKeyConstraint("solution_type_id", "solution_subtype_id"),
)

object_type_industry = sa.Table(
    "object_type_industry", meta,
    sa.Column("object_type_id", sa.BigInteger, sa.ForeignKey("object_type.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("industry_id", sa.BigInteger, sa.ForeignKey("industry.id"), nullable=False),
    sa.Column("source_note", sa.Text, nullable=False),
    sa.PrimaryKeyConstraint("object_type_id", "industry_id"),
)

object_type_parameter = sa.Table(
    "object_type_parameter", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("object_type_id", sa.BigInteger, sa.ForeignKey("object_type.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("parameter_type_id", sa.BigInteger, sa.ForeignKey("parameter_type.id"), nullable=False),
    sa.Column("group_name", sa.Text, nullable=False),
    sa.Column("is_required", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("is_fixed", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("is_derived", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("default_value_numeric", sa.Numeric(16, 4), nullable=True),
    sa.Column("default_value_text", sa.Text, nullable=True),
    sa.Column("default_value_bool", sa.Boolean, nullable=True),
    sa.Column("min_value", sa.Numeric(16, 4), nullable=True),
    sa.Column("max_value", sa.Numeric(16, 4), nullable=True),
    sa.Column("source_note", sa.Text, nullable=True),
    sa.CheckConstraint(
        "(CASE WHEN default_value_numeric IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN default_value_text IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN default_value_bool IS NOT NULL THEN 1 ELSE 0 END) = 1"),
    sa.CheckConstraint("min_value IS NULL OR max_value IS NULL OR min_value <= max_value"),
    sa.UniqueConstraint("object_type_id", "parameter_type_id"),
)

# ============================================================================
# Часть 2: домены пользователя (data_model.md, разделы 8–12)
# ============================================================================

user = sa.Table(
    "user", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("login", sa.Text, nullable=False, unique=True),
    sa.Column("password_hash", sa.Text, nullable=False),
    sa.Column("role", sa.Text,
              sa.CheckConstraint("role IN ('guest', 'user', 'admin')"),
              nullable=False, server_default="user"),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
)

project = sa.Table(
    "project", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("user_id", sa.BigInteger, sa.ForeignKey("user.id", ondelete="CASCADE"), nullable=False),
    sa.Column("object_type_id", sa.BigInteger, sa.ForeignKey("object_type.id"), nullable=False),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("description", sa.Text, nullable=True),
    sa.Column("status", sa.Text,
              sa.CheckConstraint("status IN ('draft', 'active', 'archived')"),
              nullable=False, server_default="draft"),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("user_id", "name"),
)

project_parameter_value = sa.Table(
    "project_parameter_value", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("object_type_parameter_id", sa.BigInteger, sa.ForeignKey("object_type_parameter.id"),
              nullable=False),
    sa.Column("value_numeric", sa.Numeric(16, 4), nullable=True),
    sa.Column("value_text", sa.Text, nullable=True),
    sa.Column("value_bool", sa.Boolean, nullable=True),
    sa.Column("source", sa.Text,
              sa.CheckConstraint("source IN ('manual', 'import')"),
              nullable=False, server_default="manual"),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.CheckConstraint(
        "(CASE WHEN value_numeric IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN value_text IS NOT NULL THEN 1 ELSE 0 END"
        " + CASE WHEN value_bool IS NOT NULL THEN 1 ELSE 0 END) = 1",
        name="ppv_one_value_check"),
    sa.UniqueConstraint("project_id", "object_type_parameter_id"),
)

project_attachment = sa.Table(
    "project_attachment", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("file_name", sa.Text, nullable=False),
    sa.Column("file_path", sa.Text, nullable=False),
    sa.Column("mime_type", sa.Text, nullable=True),
    sa.Column("size_bytes", sa.BigInteger,
              sa.CheckConstraint("size_bytes IS NULL OR size_bytes >= 0"), nullable=True),
    sa.Column("uploaded_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
)

scenario = sa.Table(
    "scenario", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("type", sa.Text,
              sa.CheckConstraint("type IN ('base', 'purchase', 'raas')"), nullable=False),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("project_id", "name"),
)

scenario_solution = sa.Table(
    "scenario_solution", meta,
    sa.Column("scenario_id", sa.BigInteger, sa.ForeignKey("scenario.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("solution_id", sa.BigInteger, sa.ForeignKey("solution.id"), nullable=False),
    sa.Column("quantity", sa.Integer,
              sa.CheckConstraint("quantity > 0"), nullable=False, server_default="1"),
    sa.Column("is_manual", sa.Boolean, nullable=False, server_default=sa.false()),
    sa.Column("manual_reason", sa.Text, nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.PrimaryKeyConstraint("scenario_id", "solution_id"),
)

selection_result = sa.Table(
    "selection_result", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("solution_id", sa.BigInteger, sa.ForeignKey("solution.id"), nullable=False),
    sa.Column("status", sa.Text,
              sa.CheckConstraint("status IN ('fit', 'needs_check', 'excluded')"), nullable=False),
    sa.Column("reason", sa.Text, nullable=True),
    sa.Column("rank", sa.Integer,
              sa.CheckConstraint("rank IS NULL OR rank >= 1"), nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("project_id", "solution_id"),
)

calculation = sa.Table(
    "calculation", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("scenario_id", sa.BigInteger, sa.ForeignKey("scenario.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("version_data", sa.Text, nullable=False),
    sa.Column("version_model", sa.Text, nullable=False),
    sa.Column("calculated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("total_capex", sa.Numeric(18, 2), nullable=True),
    sa.Column("total_opex", sa.Numeric(18, 2), nullable=True),
    sa.Column("opex_delta_rub", sa.Numeric(18, 2), nullable=True),
    sa.Column("effect_year", sa.Numeric(18, 2), nullable=True),
    sa.Column("payback_years", sa.Numeric(10, 2), nullable=True),
    sa.Column("roi_pct", sa.Numeric(12, 2), nullable=True),
    sa.Column("tco_rub", sa.Numeric(18, 2), nullable=True),
    sa.Column("metrics_json", sa.JSON, nullable=True),
)

calculation_assumption = sa.Table(
    "calculation_assumption", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("calculation_id", sa.BigInteger, sa.ForeignKey("calculation.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("value", sa.Text, nullable=False),
    sa.Column("unit", sa.Text, nullable=True),
    sa.Column("source_kind", sa.Text,
              sa.CheckConstraint("source_kind IN ('organizer_catalog', 'open_source', 'manual')"),
              nullable=False, server_default="manual"),
    sa.Column("source_url", sa.Text, nullable=True),
    sa.Column("source_date", sa.Date, nullable=True),
    sa.Column("impact_note", sa.Text, nullable=True),
    sa.UniqueConstraint("calculation_id", "name"),
)

manual_adjustment = sa.Table(
    "manual_adjustment", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("calculation_id", sa.BigInteger, sa.ForeignKey("calculation.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("metric_name", sa.Text, nullable=False),
    sa.Column("original_value", sa.Numeric(18, 4), nullable=True),
    sa.Column("new_value", sa.Numeric(18, 4), nullable=False),
    sa.Column("reason", sa.Text, nullable=False),
    sa.Column("author_user_id", sa.BigInteger, sa.ForeignKey("user.id"), nullable=False),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
)

simulation_result = sa.Table(
    "simulation_result", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("scenario_id", sa.BigInteger, sa.ForeignKey("scenario.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("kpi_json", sa.JSON, nullable=True),
    sa.Column("started_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
    sa.Column("status", sa.Text,
              sa.CheckConstraint("status IN ('running', 'completed', 'failed')"),
              nullable=False, server_default="running"),
)

export = sa.Table(
    "export", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("format", sa.Text,
              sa.CheckConstraint("format IN ('pdf', 'xlsx', 'csv')"), nullable=False),
    sa.Column("file_path", sa.Text, nullable=False),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("created_by_user_id", sa.BigInteger, sa.ForeignKey("user.id"), nullable=False),
)

# V7: история импортов каталога организатора
admin_import_log = sa.Table(
    "admin_import_log", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("file_name", sa.Text, nullable=False),
    sa.Column("file_path", sa.Text, nullable=False),
    sa.Column("size_bytes", sa.BigInteger, nullable=False),
    sa.Column("started_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
    sa.Column("status", sa.Text,
              sa.CheckConstraint("status IN ('running', 'completed', 'failed')"),
              nullable=False, server_default="running"),
    sa.Column("created_by_user_id", sa.BigInteger, sa.ForeignKey("user.id"), nullable=False),
    sa.Column("summary_json", sa.JSON, nullable=True),
)


def create_all(engine) -> None:
    meta.create_all(engine)


def drop_all(engine) -> None:
    meta.drop_all(engine)

project_assumption = sa.Table(
    "project_assumption", meta,
    sa.Column("id", ID_TYPE, primary_key=True, autoincrement=True),
    sa.Column("project_id", sa.BigInteger, sa.ForeignKey("project.id", ondelete="CASCADE"),
              nullable=False),
    sa.Column("name", sa.Text, nullable=False),
    sa.Column("value", sa.Text, nullable=False),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.UniqueConstraint("project_id", "name"),
)
