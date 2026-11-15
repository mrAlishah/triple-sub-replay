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


if __name__ == "__main__":
    unittest.main()
