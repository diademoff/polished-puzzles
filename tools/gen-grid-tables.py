#!/usr/bin/env python3
"""Convert the generated aperiodic-tiling tables to Kotlin.

The upstream C headers hat-tables.h, spectre-tables-manual.h and
spectre-tables-auto.h hold large constant tables. The Kotlin port must
contain the same numbers, so this script reads the vendored headers and
writes one Kotlin file. Run it from the repository root:

    python3 tools/gen-grid-tables.py

The output is checked in. Re-run this script after an upstream sync.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JNI = ROOT / "app" / "src" / "main" / "jni"
OUT = (
    ROOT
    / "engine"
    / "src"
    / "commonMain"
    / "kotlin"
    / "name"
    / "boyle"
    / "chris"
    / "sgtpuzzles"
    / "engine"
    / "GridTilingsTables.kt"
)


def read(name):
    return (JNI / name).read_text(encoding="utf-8")


def strip_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def table_body(text, name):
    """Return the text between `name[] = {` and its closing `};`."""
    match = re.search(re.escape(name) + r"\s*\[[^\]]*\]\s*=\s*\{(.*?)\};", text, re.S)
    if not match:
        sys.exit("table not found: " + name)
    return match.group(1)


def c_int_list(body, names=None):
    """Parse comma separated C expressions into integers."""
    out = []
    for token in body.replace("\n", " ").split(","):
        token = token.strip()
        if not token:
            continue
        if names is not None and token in names:
            token = str(names[token])
        out.append(int(token, 0))
    return out


def c_struct_list(body, pattern, names=None):
    """Parse C struct initialiser rows into lists of integers."""
    rows = []
    for match in re.finditer(pattern, body):
        values = []
        for token in match.group(1).split(","):
            token = token.strip()
            if names is not None and token in names:
                token = str(names[token])
            values.append(int(token, 0))
        rows.append(values)
    return rows


def flat(rows):
    return [value for row in rows for value in row]


def int_array(values, indent="        "):
    if not values:
        return "intArrayOf()"
    lines = []
    line = indent
    for value in values:
        piece = str(value) + ", "
        if len(line) + len(piece) > 96:
            lines.append(line.rstrip())
            line = indent
        line += piece
    lines.append(line.rstrip())
    return "intArrayOf(\n" + "\n".join(lines) + "\n    )"


def array_rows(name, rows, indent="    "):
    """Emit a two-dimensional table as one function per row.

    A single class initialiser with all upstream table data exceeds the
    JVM 64 KiB method limit, so each row is built by its own function.
    """
    parts = [indent + "val " + name + ": Array<IntArray> = arrayOf("]
    parts.append(",\n".join(indent + "    " + name + str(i) + "()" for i in range(len(rows))))
    parts.append(indent + ")")
    for i, row in enumerate(rows):
        parts.append("")
        parts.append(
            indent + "private fun " + name + str(i) + "(): IntArray = "
            + int_array(row, indent + "    ")
        )
    return "\n".join(parts)


def array_of(rows, indent="        "):
    if not rows:
        return "emptyArray()"
    parts = []
    for row in rows:
        parts.append(indent + "    " + int_array(row, indent + "    "))
    return "arrayOf(\n" + ",\n".join(parts) + "\n" + indent + ")"


def hat_tables():
    text = strip_comments(read("hat-tables.h"))
    tile = {"TT_H": 0, "TT_T": 1, "TT_P": 2, "TT_F": 3, "TT_KITE": 4, "TT_HAT": 5}

    hats = c_int_list(table_body(text, "hats_in_metatile"))
    children = [
        c_int_list(table_body(text, "children_" + name), tile)
        for name in ("H", "T", "P", "F")
    ]
    nchildren = c_int_list(table_body(text, "nchildren"))

    kitemap = []
    for name in ("H", "T", "P", "F"):
        rows = c_struct_list(table_body(text, "kitemap_" + name), r"\{\s*(-?\d+\s*,\s*-?\d+\s*,\s*-?\d+)\s*\}")
        kitemap.append(flat(rows))

    metamap = []
    for name in ("H", "T", "P", "F"):
        rows = c_struct_list(table_body(text, "metamap_" + name), r"\{\s*(-?\d+\s*,\s*-?\d+)\s*\}")
        metamap.append(flat(rows))

    # The row lengths are fixed by the accessors in hat-internal.h.
    assert all(
        len(kitemap[i]) == 4 * 8 * 4 * nchildren[i] * 3 for i in range(4)
    ), [len(r) for r in kitemap]
    assert all(
        len(metamap[i]) == 13 * nchildren[i] * 2 for i in range(4)
    ), [len(r) for r in metamap]

    return f"""
/**
 * Constant tables for the hat tiling. Generated from `hat-tables.h` by
 * `tools/gen-grid-tables.py`; do not edit by hand.
 *
 * `kitemap` rows are flattened `(kite, hat, meta)` triples, indexed by
 * `kitemap_index`. `metamap` rows are flattened `(meta, meta2)` pairs,
 * indexed by `metamap_index`.
 */
internal object HatTables {{
    const val TT_H = 0
    const val TT_T = 1
    const val TT_P = 2
    const val TT_F = 3
    const val TT_KITE = 4
    const val TT_HAT = 5

    const val HAT_KITES = 8
    const val MT_MAXEXPAND = 13

    val tileChars = "HTPF"

    val hatsInMetatile = {int_array(hats, "    ")}

    val children: Array<IntArray> = {array_of(children, "    ")}

    val nchildren = {int_array(nchildren, "    ")}

{array_rows("kitemap", kitemap)}

    val metamap: Array<IntArray> = {array_of(metamap, "    ")}

    fun kitemapIndex(step: Int, kite: Int, hat: Int, meta: Int): Int =
        step + 4 * (kite + 8 * (hat + 4 * meta))

    fun metamapIndex(meta: Int, meta2: Int): Int = meta2 * MT_MAXEXPAND + meta
}}
"""


def spectre_tables():
    manual = strip_comments(read("spectre-tables-manual.h"))
    auto = strip_comments(read("spectre-tables-auto.h"))
    both = manual + "\n" + auto

    hex_names = {
        "HEX_G": 0,
        "HEX_D": 1,
        "HEX_J": 2,
        "HEX_L": 3,
        "HEX_X": 4,
        "HEX_P": 5,
        "HEX_S": 6,
        "HEX_F": 7,
        "HEX_Y": 8,
    }
    probs = {}
    for match in re.finditer(r"#define\s+PROB_([A-Z])\s+(\d+)", manual):
        probs["PROB_" + match.group(1)] = int(match.group(2))

    names = dict(hex_names)
    names.update(probs)

    order = ["G", "D", "J", "L", "X", "P", "S", "F", "Y"]
    subhexes = [
        c_int_list(table_body(manual, "subhexes_" + name), hex_names) for name in order
    ]
    hexmap = [
        flat(c_struct_list(table_body(auto, "hexmap_" + name), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\d+)\s*\}", {"true": 1, "false": 0}))
        for name in order
    ]
    hexin = [
        flat(c_struct_list(table_body(auto, "hexin_" + name), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\d+)\s*\}", {"true": 1, "false": 0}))
        for name in order
    ]
    specmap = [
        flat(c_struct_list(table_body(auto, "specmap_" + name), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\d+)\s*\}", {"true": 1, "false": 0}))
        for name in order
    ]
    specin = [
        flat(c_struct_list(table_body(auto, "specin_" + name), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\d+)\s*\}", {"true": 1, "false": 0}))
        for name in order
    ]
    hexedges = [
        flat(c_struct_list(table_body(auto, "hexedges_" + name), r"\{\s*(\d+\s*,\s*\d+)\s*\}"))
        for name in order
    ]
    specedges = [
        flat(c_struct_list(table_body(auto, "specedges_" + name), r"\{\s*(\d+\s*,\s*\d+)\s*\}"))
        for name in order
    ]
    poss = [
        flat(c_struct_list(table_body(auto, "poss_" + name), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\w+)\s*\}", names))
        for name in order
    ]
    poss_spectre = flat(
        c_struct_list(table_body(auto, "poss_spectre"), r"\{\s*(\w+\s*,\s*\d+\s*,\s*\w+)\s*\}", names)
    )
    angles = c_int_list(table_body(manual, "spectre_angles"))

    return f"""
/**
 * Constant tables for the spectre tiling. Generated from
 * `spectre-tables-manual.h` and `spectre-tables-auto.h` by
 * `tools/gen-grid-tables.py`; do not edit by hand.
 *
 * Map entries are flattened `(internal, hi, lo)` triples; edge rows are
 * `(startindex, len)` pairs; possibility rows are `(hi, lo, prob)`.
 */
internal object SpectreTables {{
    const val HEX_G = 0
    const val HEX_D = 1
    const val HEX_J = 2
    const val HEX_L = 3
    const val HEX_X = 4
    const val HEX_P = 5
    const val HEX_S = 6
    const val HEX_F = 7
    const val HEX_Y = 8

    const val HEX_COUNT = 9

    val letters = "GDJLXPSFY"

    val subhexes: Array<IntArray> = {array_of(subhexes, "    ")}

    val hexmap: Array<IntArray> = {array_of(hexmap, "    ")}
    val hexin: Array<IntArray> = {array_of(hexin, "    ")}
    val hexedges: Array<IntArray> = {array_of(hexedges, "    ")}

    val specmap: Array<IntArray> = {array_of(specmap, "    ")}
    val specin: Array<IntArray> = {array_of(specin, "    ")}
    val specedges: Array<IntArray> = {array_of(specedges, "    ")}

    val poss: Array<IntArray> = {array_of(poss, "    ")}
    val possSpectre = {int_array(poss_spectre, "    ")}

    val angles = {int_array(angles, "    ")}

    fun numSubhexes(hex: Int): Int = if (hex == HEX_G) 7 else 8

    fun numSpectres(hex: Int): Int = if (hex == HEX_G) 2 else 1
}}
"""


def main():
    text = (
        "// Generated file. Do not edit by hand.\n"
        "// Run tools/gen-grid-tables.py after an upstream sync.\n"
        "\n"
        "package name.boyle.chris.sgtpuzzles.engine\n"
        + hat_tables()
        + spectre_tables()
    )
    OUT.write_text(text, encoding="utf-8")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
