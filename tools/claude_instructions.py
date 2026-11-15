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
import posixpath
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENTS = ROOT / "AGENTS.md"
CORE = ROOT / "CLAUDE.md"
MAP = ROOT / "docs/development/claude-instructions-map.json"
CODEX_CORE = ROOT / "AGENTS.override.md"
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


LINK = re.compile(r"(\]\()([^)\s]+)(\))")


def rebase_links(text, src, dest):
    """Rewrite relative links written for the file `src` so they resolve from the generated file `dest` (repo-relative paths)."""
    def fix(m):
        target = m.group(2)
        if re.match(r"^(?:[a-z][a-z0-9+.-]*:|/)", target, re.I):
            return m.group(0)
        path, hash_, fragment = target.partition("#")
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(src), path)) if path else src
        return m.group(1) + posixpath.relpath(resolved, posixpath.dirname(dest)) + hash_ + fragment + m.group(3)
    return LINK.sub(fix, text)


def render(kind, name, cfg, units):
    dest = str(target_path(kind, name).relative_to(ROOT))
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
        out += [rebase_links(u.canon, "AGENTS.md", dest), ""]
    for path, heading in cfg.get("docs", []):
        out += ["## Full text: %s, section '%s'" % (path, heading), "",
                rebase_links(doc_section(path, heading), path, dest), ""]
    return "\n".join(out).rstrip() + "\n"


def doc_section(path, heading):
    """Verbatim text of one heading block of a documentation file, down to the next heading of its level or higher."""
    lines = (ROOT / path).read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        h = re.match(r"^(#{1,6})\s+(.*\S)\s*$", line)
        if h and h.group(2) == heading:
            out = [line]
            for nxt in lines[i + 1:]:
                n = re.match(r"^(#{1,6})\s", nxt)
                if n and len(n.group(1)) <= len(h.group(1)):
                    break
                out.append(nxt)
            return "\n".join(out).rstrip()
    raise ValueError("%s has no heading %r" % (path, heading))


def broken_links(path, text):
    out = []
    for _, target, _ in LINK.findall(text):
        if re.match(r"^(?:[a-z][a-z0-9+.-]*:|/)", target, re.I):
            continue
        name, _, fragment = target.partition("#")
        file = (path.parent / name).resolve() if name else path
        if not file.exists():
            out.append("%s: link %s does not resolve" % (path.relative_to(ROOT), target))
        elif fragment and file.suffix == ".md":
            heads = {slug(h) for h in re.findall(r"^#{1,6}\s+(.*\S)\s*$", file.read_text(encoding="utf-8"), re.M)}
            if fragment not in heads:
                out.append("%s: link %s has no such heading" % (path.relative_to(ROOT), target))
    return out


def slug(heading):
    return re.sub(r"\s", "-", re.sub(r"[^\w\s-]", "", heading.lower()))


def generated_files(units, m):
    by_key = {e["h"]: e for e in m["units"]}
    files = {}
    for dest, cfg in m["targets"].items():
        kind, name = split_dest(dest)
        mine = [u for u in units if by_key.get(u.key, {}).get("dest") == dest]
        files[target_path(kind, name)] = render(kind, name, cfg, mine)
    return files


def codex_core(core):
    replacements = (
        ("# CLAUDE.md (TripleSub Replay, Claude Code core)", "# AGENTS.override.md (TripleSub Replay, Codex core)"),
        ("This file is a derived core of `AGENTS.md`, enforced by a CI drift check: `python3 tools/claude_instructions.py check` fails when this file, a rule or the skill drifts from `AGENTS.md`. `AGENTS.md` stays authoritative and unchanged (other agents read it whole); on any conflict it wins. Do not read all of `AGENTS.md` for routine work: use the routing table. `.claude/rules/*` and the PR skill are generated verbatim from `AGENTS.md`. After `AGENTS.md` changes, run `python3 tools/claude_instructions.py sync`, review what it reports, then commit.",
         "This file is generated from the maintained `CLAUDE.md` core by `tools/claude_instructions.py`; do not edit it. `AGENTS.md` remains authoritative and wins on conflict. The existing drift check validates the core and generated rule files against it. Read the applicable full rules below before dependent work. If the applicable rule is unclear, read the relevant part of `AGENTS.md`. After upstream changes, reconcile the map, digests and generated files with `python3 tools/claude_instructions.py sync` and review unresolved changes."),
        ("**Before your first Serena or other MCP call on a file in the table below, and before proposing or making any change there, Read its rule file.** Path-scoped rules autoload only when a built-in Read, Write or Edit touches a matching file; Serena and other MCP tools never trigger them. A task that spans areas needs each rule. The digests below summarize; the rule file has the full text.",
         "**Before reading or inspecting a relevant source file, including through Serena or any other MCP, and before proposing or making a change, read each applicable rule file below.** Codex does not automatically load Claude path-scoped rules. For cross-area tasks read every applicable rule. These paths are examples, not an exhaustive classifier; use the rule based on the behavior affected. If uncertain, read the relevant section of `AGENTS.md`. The digests below summarize; the rule files contain the full instructions."),
        ("skill `pr-handoff-release` (Skill tool)", "`.claude/skills/pr-handoff-release/SKILL.md` (read as a file)"),
        ("follow skill `pr-handoff-release`: load it before opening, updating or handing off a PR.", "follow `.claude/skills/pr-handoff-release/SKILL.md`: read it before opening, updating or handing off a PR."),
    )
    for before, after in replacements:
        if core.count(before) != 1:
            raise ValueError("CLAUDE.md Codex transformation anchor missing or ambiguous: " + before[:80])
        core = core.replace(before, after, 1)
    anchor = "## Project\n"
    if core.count(anchor) != 1:
        raise ValueError("CLAUDE.md project heading missing or ambiguous")
    core = core.replace(anchor, "## Git authorization\n\nBefore coding verify the assigned worktree, branch and existing changes. Do not commit, push, merge, rebase, reset, deploy or release without explicit authorization for that action. Do not create or update a pull request, including one targeting upstream, without separate explicit authorization. Approval to implement does not authorize these actions. Preserve unrelated work and fork behavior; resolve upstream conflicts by meaning.\n\n" + anchor, 1)
    return core


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

    try:
        expected_codex = codex_core(core)
        actual_codex = CODEX_CORE.read_text(encoding="utf-8") if CODEX_CORE.exists() else None
        if actual_codex != expected_codex:
            out.append("AGENTS.override.md is stale or missing; run sync")
        if len(expected_codex.encode("utf-8")) > 18432:
            out.append("AGENTS.override.md exceeds 18432 bytes")
    except ValueError as e:
        out.append(str(e))

    corpus = core
    try:
        files = generated_files(units, m)
    except ValueError as e:
        return out + [str(e)]
    tech = (ROOT / "docs/project/tech-stack.md").read_text(encoding="utf-8")
    anchors = {slug(h) for h in re.findall(r"^#{1,6}\s+(.*\S)\s*$", tech, re.M)}
    for a in sorted(set(re.findall(r"tech-stack\.md#([\w-]+)", AGENTS.read_text(encoding="utf-8")))):
        if a not in anchors:
            out.append("AGENTS.md links to tech-stack.md#%s, which is not a heading there" % a)
    for path, expected in files.items():
        out += broken_links(path, expected)
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
    docs_text = "".join(doc_section(p, h) for c in m["targets"].values() for p, h in c.get("docs", []))
    for literal in m["must_keep"]:
        if literal not in agents_text and literal not in docs_text:
            out.append("must_keep literal in neither AGENTS.md nor an embedded tech-stack section: %r" % literal)
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
    codex = codex_core(CORE.read_text(encoding="utf-8"))
    MAP.write_text(json.dumps(m, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    CODEX_CORE.write_text(codex, encoding="utf-8")
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
