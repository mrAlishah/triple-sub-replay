from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import claude_instructions as ci


def key(text, section="Branding"):
    return ci.parse("## %s\n\n%s\n" % (section, text))[0].key


class DriftTest(unittest.TestCase):
    def test_core_rules_and_skill_match_agents_md(self):
        found = ci.problems()
        self.assertEqual([], found, "\n".join(found) + "\nFix: python3 tools/claude_instructions.py sync")


class KeyTest(unittest.TestCase):
    def test_cosmetic_changes_keep_the_key(self):
        base = key("- Never rename the backup format value.")
        self.assertEqual(base, key("*   Never  rename the\n  backup format value."))
        self.assertEqual(base, key("7. Never rename the backup format value."))
        self.assertEqual(key("- x", "Branding (fork rule, 2026-10-31)"), key("- x", "Branding (fork rule, 2026-12-01)"))

    def test_content_changes_change_the_key(self):
        self.assertNotEqual(key("- Never rename the backup format value."),
                            key("- Always rename the backup format value."))
        self.assertNotEqual(key("- x", "Branding"), key("- x", "Testing conventions"))

    def test_every_item_kind_is_one_unit(self):
        units = ci.parse("## S\n\n- a\n  - nested\n1. b\nplain\n```sh\nrun\n```\n")
        self.assertEqual(4, len(units))
        self.assertIn("nested", units[0].text)
        self.assertTrue(units[3].text.startswith("```"))

    def test_unsupported_markdown_is_rejected(self):
        for bad in ("| a | b |", "> quote", "<div>x</div>"):
            with self.assertRaises(ci.ParseError):
                ci.parse("## S\n\n%s\n" % bad)


class CodexCoreTest(unittest.TestCase):
    def test_codex_core_routes_all_targets_and_preserves_core_units(self):
        core = ci.codex_core(ci.CORE.read_text(encoding="utf-8"))
        units = ci.parse(ci.AGENTS.read_text(encoding="utf-8"))
        mapping = ci.load_map()
        by_key = {e["h"]: e for e in mapping["units"]}
        normalized = ci.re.sub(r"\s+", " ", core)
        for unit in units:
            if by_key[unit.key]["dest"] == "core":
                self.assertIn(unit.norm, normalized)
        for dest in mapping["targets"]:
            kind, name = ci.split_dest(dest)
            path = ci.target_path(kind, name).relative_to(ci.ROOT).as_posix()
            self.assertIn(path, core)
        self.assertIn("before reading or inspecting", core.lower())
        self.assertIn("including through Serena", core)
        self.assertIn("Do not commit, push, merge, rebase, reset, deploy or release", core)
        self.assertIn("Do not create or update a pull request, including one targeting upstream, without separate explicit authorization.", core)

    def test_anchor_change_fails_closed(self):
        with self.assertRaises(ValueError):
            ci.codex_core("# changed")

    def test_check_detects_changed_override(self):
        from tempfile import TemporaryDirectory
        from unittest.mock import patch
        with TemporaryDirectory() as directory:
            target = Path(directory) / "AGENTS.override.md"
            target.write_text("stale", encoding="utf-8")
            with patch.object(ci, "CODEX_CORE", target):
                self.assertTrue(any("stale or missing" in x for x in ci.problems()))


class DocSectionTest(unittest.TestCase):
    def test_section_stops_at_next_heading_of_same_or_higher_level(self):
        text = ci.doc_section("docs/project/tech-stack.md", "Captions")
        self.assertTrue(text.startswith("### Captions"))
        self.assertNotIn("### Translation", text)

    def test_missing_heading_fails_closed(self):
        with self.assertRaises(ValueError):
            ci.doc_section("docs/project/tech-stack.md", "No such heading")

    def test_slug_matches_github_anchors(self):
        self.assertEqual("app-updates-remote-switches-and-problem-reports",
                         ci.slug("App updates, remote switches and problem reports"))

    def test_every_embedded_section_is_in_its_rule(self):
        m = ci.load_map()
        for dest, cfg in m["targets"].items():
            for path, heading in cfg.get("docs", []):
                target = ci.target_path(*ci.split_dest(dest))
                rule = target.read_text(encoding="utf-8")
                expected = ci.rebase_links(ci.doc_section(path, heading), path, str(target.relative_to(ci.ROOT)))
                self.assertIn(expected, rule)


class LinkTest(unittest.TestCase):
    def test_rebase_resolves_from_the_generated_file(self):
        dest = ".claude/rules/x.md"
        self.assertEqual("[a](../../docs/specs/s.md#h)",
                         ci.rebase_links("[a](../specs/s.md#h)", "docs/project/tech-stack.md", dest))
        self.assertEqual("[a](../../docs/project/tech-stack.md#h)",
                         ci.rebase_links("[a](docs/project/tech-stack.md#h)", "AGENTS.md", dest))
        self.assertEqual("[a](../../docs/project/tech-stack.md#h)",
                         ci.rebase_links("[a](#h)", "docs/project/tech-stack.md", dest))

    def test_urls_and_absolute_links_are_untouched(self):
        text = "[a](https://example.com/x#y) [b](/abs) [c](mailto:a@b.c)"
        self.assertEqual(text, ci.rebase_links(text, "AGENTS.md", ".claude/rules/x.md"))

    def test_generated_files_have_no_broken_links(self):
        units = ci.parse(ci.AGENTS.read_text(encoding="utf-8"))
        for path, text in ci.generated_files(units, ci.load_map()).items():
            self.assertEqual([], ci.broken_links(path, text))

    def test_unrebased_text_is_reported(self):
        path = ci.ROOT / ".claude/rules/x.md"
        self.assertTrue(ci.broken_links(path, "[a](docs/project/tech-stack.md#first-launch)"))
        self.assertTrue(ci.broken_links(path, "[a](../../docs/project/tech-stack.md#no-such-heading)"))


if __name__ == "__main__":
    unittest.main()
