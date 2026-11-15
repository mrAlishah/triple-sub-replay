#!/usr/bin/env python3
"""Keep CLAUDE.md, .claude/rules/* and the PR skill reconciled with AGENTS.md.

AGENTS.md stays authoritative and unchanged. Every item in it (a bullet, numbered item,
paragraph or code block) is hashed and listed in docs/development/claude-instructions-map.json
with the place Claude reads it: verbatim in CLAUDE.md ("core"), or verbatim in a generated
rule or skill file. Rule and skill files are generated; only CLAUDE.md is hand-written.

  python3 tools/claude_instructions.py check     exit 1 on any unreconciled difference
  python3 tools/claude_instructions.py sync      regenerate files, classify what is reliable
  python3 tools/claude_instructions.py ack ID    confirm a reviewed CLAUDE.md digest
"""
import collections
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENTS = ROOT / "AGENTS.md"
CORE = ROOT / "CLAUDE.md"
MAP = ROOT / "docs/development/claude-instructions-map.json"
CORE_MAX_BYTES = 16384
CORE_MAX_LINES = 200
GENERATED = ("<!-- Generated from AGENTS.md by tools/claude_instructions.py. "
             "Do not edit; run `python3 tools/claude_instructions.py sync`. -->")
FENCE = re.compile(r"^\s*```")
LIST = re.compile(r"^(?:[-*]|\d+\.)\s+")
MARKER = re.compile(r"^\s*(?:[-*]|\d+\.)\s+")


class ParseError(Exception):
    pass


class Unit:
    def __init__(self, section, text, line):
        self.section, self.text, self.line = section, text, line

    @property
    def norm(self):
        # Whitespace, line wrapping and the list marker are cosmetic; everything else is content.
        return re.sub(r"\s+", " ", MARKER.sub("", self.text, count=1)).strip()

    @property
    def canon(self):
        # What generated files contain: the content without its cosmetic variations.
        if FENCE.match(self.text):
            return self.text.rstrip()
        return ("- " if MARKER.match(self.text) else "") + self.norm

    @property
    def key(self):
        return hashlib.sha256((self.section + "\n" + self.norm).encode("utf-8")).hexdigest()

    @property
    def start(self):
        return self.norm[:48]

    @property
    def end(self):
        return self.norm[-48:]


def parse(text):
    section, units, cur, fence = "", [], None, None
    for n, line in enumerate(text.splitlines(), 1):
        if fence is not None:
            fence[1].append(line)
            if FENCE.match(line):
                units.append(Unit(section, "\n".join(fence[1]), fence[0]))
                fence = None
            continue
        if FENCE.match(line):
            cur, fence = None, (n, [line])
            continue
        heading = re.match(r"^#{1,6}\s+(.*\S)\s*$", line)
        if heading:
            section, cur = heading.group(1).split(" (")[0].strip(), None
            continue
        if not line.strip():
            cur = None
            continue
        if re.match(r"^\s*[|>]", line) or line.lstrip().startswith("<"):
            raise ParseError(n, "table, quote or HTML is not supported")
        if LIST.match(line) or not (line[0].isspace() and cur is not None):
            cur = Unit(section, line, n)
            units.append(cur)
        else:
            cur.text += "\n" + line
    if fence is not None:
        raise ParseError(fence[0], "unterminated code fence")
    return units


def load_map():
    return json.loads(MAP.read_text(encoding="utf-8"))


def target_path(kind, name):
    if kind == "rule":
        return ROOT / ".claude/rules" / (name + ".md")
    return ROOT / ".claude/skills" / name / "SKILL.md"


def split_dest(dest):
    kind, _, name = dest.partition(":")
    return kind, name


def render(kind, name, cfg, units):
    out = ["---"]
    if kind == "rule":
        out += ["paths:"] + ['  - "%s"' % p for p in cfg["paths"]]
    else:
        out += ["name: " + name, 'description: "%s"' % cfg["description"].replace('"', '\\"')]
    out += ["---", GENERATED, "", "# " + cfg["title"], ""]
    section = None
    for u in units:
        if u.section != section:
            section = u.section
            out += ["## " + section, ""]
        out += [u.canon, ""]
    return "\n".join(out).rstrip() + "\n"


def generated_files(units, m):
    by_key = {e["h"]: e for e in m["units"]}
    files = {}
    for dest, cfg in m["targets"].items():
        kind, name = split_dest(dest)
        mine = [u for u in units if by_key.get(u.key, {}).get("dest") == dest]
        files[target_path(kind, name)] = render(kind, name, cfg, mine)
    return files


def problems():
    try:
        units = parse(AGENTS.read_text(encoding="utf-8"))
    except ParseError as e:
        return ["AGENTS.md line %s: %s. Cannot classify reliably; simplify it or extend "
                "tools/claude_instructions.py." % e.args]
    m = load_map()
    out = []
    have = collections.Counter(u.key for u in units)
    mapped = collections.Counter(e["h"] for e in m["units"])
    for k in have - mapped:
        u = next(x for x in units if x.key == k)
        out.append("AGENTS.md item not in the map [%s] L%d: %r" % (u.section, u.line, u.start))
    for k in mapped - have:
        e = next(x for x in m["units"] if x["h"] == k)
        out.append("map item no longer in AGENTS.md [%s]: %r" % (e["section"], e["start"]))
    if out:
        out.append("Run: python3 tools/claude_instructions.py sync")

    core = CORE.read_text(encoding="utf-8")
    core_norm = re.sub(r"\s+", " ", core)
    by_key = {e["h"]: e for e in m["units"]}
    for e in m["units"]:
        d = e["dest"]
        if d != "core" and d not in m["targets"]:
            out.append("unclassified item [%s] %r (dest %r)" % (e["section"], e["start"], d))
        if e.get("review"):
            out.append("digest %s needs review: update its CLAUDE.md summary, then run ack %s"
                       % (e["digest"], e["digest"]))
        if e.get("digest") and "<!-- digest:%s -->" % e["digest"] not in core:
            out.append("CLAUDE.md lacks the tag <!-- digest:%s -->" % e["digest"])
    for u in units:
        if by_key.get(u.key, {}).get("dest") == "core" and u.norm not in core_norm:
            out.append("CLAUDE.md lacks core item [%s] L%d: %r" % (u.section, u.line, u.start))

    corpus = core
    files = generated_files(units, m)
    for path, expected in files.items():
        actual = path.read_text(encoding="utf-8") if path.exists() else None
        if actual != expected:
            out.append("%s is stale or missing; run sync" % path.relative_to(ROOT))
        corpus += expected
    for stray in sorted((ROOT / ".claude/rules").glob("*.md")):
        if stray not in files:
            out.append("%s is not generated; an unscoped rule loads at every launch"
                       % stray.relative_to(ROOT))

    size = len(core.encode("utf-8"))
    if size > CORE_MAX_BYTES or len(core.splitlines()) > CORE_MAX_LINES:
        out.append("CLAUDE.md is %d bytes / %d lines; limit %d / %d"
                   % (size, len(core.splitlines()), CORE_MAX_BYTES, CORE_MAX_LINES))
    if re.search(r"^@\S", core, re.M):
        out.append("CLAUDE.md must not import files; an import loads them at every launch")
    agents_text = AGENTS.read_text(encoding="utf-8")
    for label in ("Repository", "Ref", "Entrypoint", "Local checkout"):
        found = re.search(r"^\s*(?:[-*]\s+)?%s: `([^`]+)`" % label, agents_text, re.M)
        if not found:
            out.append("AGENTS.md has no '%s' locator line" % label)
        elif found.group(1) not in core:
            out.append("CLAUDE.md lacks the SoT locator %s: %s" % (label, found.group(1)))
    for dest, cfg in m["targets"].items():
        kind, name = split_dest(dest)
        ref = ".claude/rules/%s.md" % name if kind == "rule" else name
        if ref not in core:
            out.append("CLAUDE.md routing table does not mention %s" % ref)
        for pattern in cfg.get("paths", []):
            if not any(ROOT.glob(pattern)):
                out.append("%s: path pattern %r matches no file" % (dest, pattern))
        if kind == "skill" and len(cfg["description"]) > 1536:
            out.append("%s: description is over 1536 characters" % dest)
    for literal in m["must_keep"]:
        if literal not in agents_text:
            out.append("must_keep literal not in AGENTS.md any more: %r" % literal)
        if literal not in corpus:
            out.append("must_keep literal missing from CLAUDE.md and rules: %r" % literal)
    return out


def sync():
    units = parse(AGENTS.read_text(encoding="utf-8"))
    m = load_map()
    old = {e["h"]: e for e in m["units"]}
    gone = [e for e in m["units"] if e["h"] not in {u.key for u in units}]
    section_dests = collections.defaultdict(set)
    for e in m["units"]:
        section_dests[e["section"]].add(e["dest"])
    entries, notes = [], []
    for u in units:
        if u.key in old:
            entries.append(dict(old[u.key]))
            continue
        same = [g for g in gone if g["section"] == u.section]
        twin = [g for g in same if g["start"] == u.start or g.get("end") == u.end]
        added = [x for x in units if x.section == u.section and x.key not in old]
        if len(twin) != 1 and len(same) == 1 and len(added) == 1:
            twin = same  # the section lost one item and gained one: treat as an edit
        dests = section_dests.get(u.section, set())
        entry = {"h": u.key, "section": u.section, "start": u.start, "end": u.end}
        if len(twin) == 1:
            entry["dest"] = twin[0]["dest"]
            if twin[0].get("digest"):
                entry["digest"], entry["review"] = twin[0]["digest"], True
                notes.append("edited item has a CLAUDE.md digest: review %s" % entry["digest"])
            notes.append("edited [%s] %r -> %s" % (u.section, u.start, entry["dest"]))
        elif len(dests) == 1 and "?" not in dests:
            entry["dest"] = next(iter(dests))
            notes.append("new [%s] %r -> %s (section has one destination)"
                         % (u.section, u.start, entry["dest"]))
        else:
            entry["dest"] = "?"
            notes.append("NEEDS A HUMAN [%s] %r: set \"dest\" in the map" % (u.section, u.start))
        entries.append(entry)
    m["units"] = entries
    MAP.write_text(json.dumps(m, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    for path, text in generated_files(units, m).items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print("\n".join(notes) or "map already matches AGENTS.md; generated files rewritten")
    left = problems()
    print("\n".join(left))
    return 1 if left else 0


def ack(digest):
    m = load_map()
    hit = [e for e in m["units"] if e.get("digest") == digest]
    for e in hit:
        e["review"] = False
    MAP.write_text(json.dumps(m, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print("acknowledged %d item(s) for %s" % (len(hit), digest))
    return 0 if hit else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "check":
        found = problems()
        print("\n".join(found) or "CLAUDE.md, rules and skill match AGENTS.md")
        sys.exit(1 if found else 0)
    if cmd == "sync":
        sys.exit(sync())
    if cmd == "ack" and len(sys.argv) == 3:
        sys.exit(ack(sys.argv[2]))
    sys.exit(__doc__)
