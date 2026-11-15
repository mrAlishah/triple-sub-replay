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


if __name__ == "__main__":
    unittest.main()
