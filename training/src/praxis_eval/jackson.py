"""Reading a model's answer the way the Java backend does (Jackson, GraphJson.MAPPER).

The verdict on an answer starts with whether it parses at all, so this has to
accept and reject exactly what Jackson does with the backend's settings:

- leading whitespace is skipped, and anything after the first JSON value is
  ignored (FAIL_ON_TRAILING_TOKENS is off);
- the literal `null` reads as no diagnosis (parsed = false, no error);
- unknown fields are ignored; snake_case names (`reasoning_chain`);
- a String field takes any scalar as its text (`true`, `3`, `1.50` as written),
  but an array or object there is an error;
- a list field must be an array (a single value is not wrapped);
- NaN and Infinity are not JSON.

`java_str` is Java's String.valueOf for the values Jackson builds, which the
verifier compares as text (rule 5 and every argument check).
"""

from __future__ import annotations

import json
from decimal import Decimal


class JFloat(float):
    """A JSON number with a fraction or exponent, remembering how it was written."""

    text: str

    def __new__(cls, text: str):
        f = super().__new__(cls, text)
        f.text = text
        return f


def _reject_constant(name: str):
    raise ValueError(f"not JSON: {name}")


_DECODER = json.JSONDecoder(parse_float=JFloat, parse_constant=_reject_constant)


class NotADiagnosis(ValueError):
    """The answer is not a diagnosis Jackson would read (EvalCli: "unparseable: ...")."""


def java_double(x: float) -> str:
    """Double.toString: shortest digits, decimal in [1e-3, 1e7), else d.dddE±n."""
    if x != x:
        return "NaN"
    if x in (float("inf"), float("-inf")):
        return "Infinity" if x > 0 else "-Infinity"
    if x == 0:
        return "-0.0" if str(x).startswith("-") else "0.0"
    if 1e-3 <= abs(x) < 1e7:
        r = repr(float(x))
        if "e" in r or "E" in r:   # repr switched to exponent below 1e-4; not in this range
            r = format(Decimal(r), "f")
        return r if "." in r else r + ".0"
    sign, digits, exp = Decimal(repr(float(x))).normalize().as_tuple()
    ds = "".join(map(str, digits))
    e = exp + len(ds) - 1
    mant = ds[0] + "." + (ds[1:] or "0")
    return ("-" if sign else "") + mant + "E" + str(e)


def java_str(o) -> str:
    """String.valueOf(o) for null, Boolean, Integer/Long, Double, String, List and Map."""
    if o is None:
        return "null"
    if isinstance(o, bool):
        return "true" if o else "false"
    if isinstance(o, int):
        return str(o)
    if isinstance(o, float):
        return java_double(o)
    if isinstance(o, str):
        return o
    if isinstance(o, list):
        return "[" + ", ".join(java_str(v) for v in o) + "]"
    if isinstance(o, dict):
        return "{" + ", ".join(f"{k}={java_str(v)}" for k, v in o.items()) + "}"
    return str(o)


def _string(v, field: str):
    """A String-typed property: null, a string, or a scalar's text."""
    if v is None or isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, JFloat):
        return v.text
    if isinstance(v, (int, float)):
        return str(v)
    raise NotADiagnosis(f"{field}: expected a string, got {type(v).__name__}")


def _string_list(v, field: str):
    if v is None:
        return None
    if not isinstance(v, list):
        raise NotADiagnosis(f"{field}: expected an array")
    return [_string(x, field) for x in v]


def _claim(v, i: int):
    if v is None:
        return None
    if not isinstance(v, dict):
        raise NotADiagnosis(f"reasoning_chain[{i}]: expected an object")
    args = v.get("args")
    if args is not None and not isinstance(args, dict):
        raise NotADiagnosis(f"reasoning_chain[{i}].args: expected an object")
    return {"type": _string(v.get("type"), "type"), "args": args,
            "cites": _string_list(v.get("cites"), "cites"), "text": _string(v.get("text"), "text")}


def parse_diagnosis(raw: str) -> dict | None:
    """The diagnosis in `raw`, None for a JSON null; NotADiagnosis when Jackson would fail."""
    i = 0
    while i < len(raw) and raw[i] in " \t\r\n":
        i += 1
    if i == len(raw):
        raise NotADiagnosis("no content")
    try:
        value, _ = _DECODER.raw_decode(raw, i)
    except (json.JSONDecodeError, ValueError) as e:
        raise NotADiagnosis(str(e)) from None
    if value is None:
        return None
    if not isinstance(value, dict):
        raise NotADiagnosis(f"expected an object, got {type(value).__name__}")
    chain = value.get("reasoning_chain")
    if chain is not None:
        if not isinstance(chain, list):
            raise NotADiagnosis("reasoning_chain: expected an array")
        chain = [_claim(c, i) for i, c in enumerate(chain)]
    return {
        "reasoning_chain": chain,
        "consequence": _string(value.get("consequence"), "consequence"),
        "mechanism": _string(value.get("mechanism"), "mechanism"),
        "motif": _string(value.get("motif"), "motif"),
        "critical_response": _string(value.get("critical_response"), "critical_response"),
        "visibility": _string(value.get("visibility"), "visibility"),
        "explanation": _string(value.get("explanation"), "explanation"),
    }
