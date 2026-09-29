"""Параметры объектов: parameter_type, object_type_parameter."""
from typing import TypeAlias

from sqlalchemy import Table
from sqlalchemy.engine import Connection, Engine

from mappers.objects_mapper import ObjectsData
from mappers.code_map import code_for
from normalizers.strings import canonical_key, slugify
from .common import load_id_map, upsert

LogFn: TypeAlias = object


def seed_parameter_types(conn, engine, tables: dict[str, Table],
                         objects: ObjectsData, log) -> dict[str, int]:
    # английские коды — code_map.py (конвенция assumptions.md §20);
    # резолвятся уже в map_objects (нужны overrides-механизму), здесь —
    # фолбэк для строк без кода (транслит с предупреждением УТОЧНИТЬ)
    def _code(name: str) -> str:
        code, _ = code_for("parameter_type", name)
        if code is None:
            code = slugify(name) or "x"
            log(f"УТОЧНИТЬ: для parameter_type «{name}» нет кода в конвенции "
                f"(code_map.py), использован транслит «{code}")
        return code

    rows = [{
        "code": pt.code or _code(pt.name),
        "name": pt.name,
        "unit": pt.unit,
        "value_type": pt.value_type,
    } for pt in objects.param_types]
    upsert(conn, engine, tables["parameter_type"], rows, ["code"],
           ["name", "unit", "value_type"], log, "parameter_type")
    return load_id_map(conn, tables["parameter_type"])


def seed_object_type_parameters(conn, engine, tables, objects: ObjectsData,
                                object_type_map: dict, param_type_map: dict, log):
    rows = []
    for r in objects.object_type_params:
        ot_id = object_type_map.get(canonical_key(r.object_type))
        pt_id = param_type_map.get(canonical_key(r.param_name))
        if ot_id is None or pt_id is None:
            raise ValueError(f"не найдены справочники для параметра «{r.param_name}» "
                             f"типа объекта «{r.object_type}»")
        rows.append({
            "object_type_id": ot_id,
            "parameter_type_id": pt_id,
            "group_name": r.group_name,
            "is_required": r.is_required,
            "is_fixed": r.is_fixed,
            "is_derived": r.is_derived,
            "default_value_numeric": r.default_value_numeric,
            "default_value_text": r.default_value_text,
            "default_value_bool": r.default_value_bool,
            "min_value": r.min_value,
            "max_value": r.max_value,
            "source_note": r.source_note,
        })
    upsert(conn, engine, tables["object_type_parameter"], rows,
           ["object_type_id", "parameter_type_id"],
           ["group_name", "is_required", "is_fixed", "is_derived",
            "default_value_numeric", "default_value_text",
            "default_value_bool", "min_value", "max_value", "source_note"],
           log, "object_type_parameter")
