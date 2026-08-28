#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Wire-mocked unit tests for the mc CLI translation layer.

Every test mocks mc.wire with canned /bot responses shaped exactly as
the Java serializers emit them (EventBatchJson / BotStateJson /
BotCommands), so the tests pin the translation contract without a
live server. Run: python tool/harness/test_mc.py
"""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import mc


def batch(events, latest=0, reset_at=0):
    """One /bot events reply in the exact EventBatchJson shape."""
    return {"ok": True,
            "batch": {"latest": latest, "resetAt": reset_at, "dropped": 0,
                      "events": events}}


def event(kind, task_id=None, task=None, **extra_attrs):
    """One BotEvent node in the exact EventBatchJson shape."""
    attrs = dict(extra_attrs)
    if task_id is not None:
        attrs["taskId"] = task_id
    if task is not None:
        attrs["task"] = task
    return {"kind": kind, "day": 1, "t": 100, "urgent": False,
            "text": f"{kind}", "attrs": attrs}


class McCliTest(unittest.TestCase):
    """Base: temp cursor file, wire mock with recorded calls."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        cursor = Path(self._tmp.name) / "mc_cursor.txt"
        patcher = mock.patch.object(mc, "CURSOR_PATH", cursor)
        patcher.start()
        self.addCleanup(patcher.stop)
        # Isolate the materialized recipe cache: a dump on the
        # operator's machine must not flip local-first cat tests.
        recipes = Path(self._tmp.name) / "recipes"
        patcher = mock.patch.object(mc, "RECIPES_DIR", recipes)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.wire_calls: list[str] = []
        self.wire_responses: list[dict] = []
        patcher = mock.patch.object(mc, "wire", side_effect=self._wire)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _wire(self, command: str) -> dict:
        self.wire_calls.append(command)
        if self.wire_responses:
            return self.wire_responses.pop(0)
        return {"ok": True}

    def queue(self, *responses: dict) -> None:
        self.wire_responses.extend(responses)

    def run_verb(self, fn, *args, **kwargs):
        """Call a cmd_* verb, capture stdout/stderr, return
        (exit_code, stdout_text, stderr_text)."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = fn(*args, **kwargs)
        return code, out.getvalue(), err.getvalue()

    def cursor_value(self) -> int:
        return mc.read_cursor()


class VerbDisciplineTest(McCliTest):
    """read is documents-only until /items ships; typed errors, never
    substitution. Station snapshots ride cat as complete
    transactions (the lazy-session wart class stays dead)."""

    def test_read_on_any_path_suggests_cat_and_names_the_future(self):
        code, _, err = self.run_verb(mc.cmd_read, "/player/pos")
        self.assertEqual(code, 1)
        self.assertIn("cat", err)
        self.assertIn("/items", err)

    def test_read_on_station_path_also_suggests_cat(self):
        code, _, err = self.run_verb(mc.cmd_read, "/stations/chest@1,2,3/")
        self.assertEqual(code, 1)
        self.assertIn("cat", err)
        self.assertEqual(self.wire_calls, [])

    def test_cat_station_prefix_without_coords_is_typed_error(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/stations")
        self.assertEqual(code, 1)
        self.assertIn("invalid station path", err)

    def test_retired_actions_paths_are_typed_errors(self):
        for old in ("/actions/place", "/actions/use", "/actions/sleep",
                    "/actions/use-item", "/actions/sneak", "/actions/equip",
                    "/tasks/dig"):
            code, _, err = self.run_verb(mc.cmd_write, old, "1,2,3")
            self.assertEqual(code, 1, old)
            self.assertEqual(self.wire_calls, [])

    def test_cat_player_menu_is_unsupported_not_task_summary(self):
        self.queue({"ok": True, "state": {"task": "goto:t1"}})
        code, _, err = self.run_verb(mc.cmd_cat, "/player/menu")
        self.assertEqual(code, 1)
        self.assertIn("unsupported", err)


class CatTaskByIdTest(McCliTest):
    """cat /tasks/<id>: client-side derivation over /bot events 0."""

    def test_derives_status_from_last_matching_event(self):
        self.queue(batch([
            event("TASK_PAUSED", task_id="t14"),
            event("TASK_COMPLETED", task_id="t99"),  # unrelated task
            event("TASK_COMPLETED", task_id="t14", reason="goal reached"),
        ]))
        code, out, _ = self.run_verb(mc.cmd_cat, "/tasks/t14")
        self.assertEqual(code, 0)
        result = json.loads(out)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(len(result["events"]), 2)

    def test_unknown_when_no_matching_events(self):
        self.queue(batch([event("TASK_COMPLETED", task_id="t99")]))
        code, out, _ = self.run_verb(mc.cmd_cat, "/tasks/t14")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["status"], "unknown")

    def test_reports_gap_when_ring_buffer_lost_history(self):
        self.queue(batch([event("EVENT_GAP", count=5, since=0, oldest=30)]))
        code, out, _ = self.run_verb(mc.cmd_cat, "/tasks/t14")
        self.assertEqual(code, 0)
        result = json.loads(out)
        self.assertEqual(result["status"], "unknown")
        self.assertTrue(result["gap"])

    def test_strict_taskid_match_display_name_never_matches(self):
        # displayName lives under the "task" key ("goto:t14") and must
        # not correlate: fuzzy matching would be translation magic.
        self.queue(batch([event("TASK_CANCELLED", task="goto:t14")]))
        code, out, _ = self.run_verb(mc.cmd_cat, "/tasks/t14")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["status"], "unknown")

    def test_dropped_task_reports_dropped(self):
        self.queue(batch([event("TASK_DROPPED", task_id="t14")]))
        code, out, _ = self.run_verb(mc.cmd_cat, "/tasks/t14")
        self.assertEqual(json.loads(out)["status"], "dropped")


class WriteGotoTest(McCliTest):
    """write /tasks/goto translation and receipt handling."""

    def test_translates_with_server_defaults_when_omitted(self):
        self.queue({"ok": True, "task": "t1", "replay": False})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/tasks/goto", "100,64,200")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["/bot goto 100 64 200 0 1200"])

    def test_passes_explicit_params_and_key_verbatim(self):
        self.queue({"ok": True, "task": "t1", "replay": False})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/tasks/goto", "100, 64, 200",
            tol=2, timeout=600, key="k1")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["/bot goto 100 64 200 2 600 k1"])

    def test_rejects_malformed_value_without_touching_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/tasks/goto", "100,64")
        self.assertEqual(code, 1)
        self.assertIn("x,y,z", err)
        self.assertEqual(self.wire_calls, [])

    def test_exit_code_one_on_rejection(self):
        self.queue({"ok": False, "reason": "out of range"})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/tasks/goto", "100,64,200")
        self.assertEqual(code, 1)

    def test_receipt_key_is_task(self):
        self.queue({"ok": True, "task": "t1", "replay": False})
        code, out, err = self.run_verb(
            mc.cmd_write, "/tasks/goto", "100,64,200")
        self.assertEqual(code, 0)
        self.assertEqual(err, "")
        # The wire receipt carries the id under the legacy "task" key
        # (BotCommands.runGoto); pin it so a wire rename trips here
        # before it silently breaks the write->wait chain.
        self.assertEqual(json.loads(out).get("task"), "t1")


class WriteCancelTest(McCliTest):

    def test_translates_to_cancel(self):
        self.queue({"ok": True})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/tasks/t14/cancel", "operator stop")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot cancel t14"])

    def test_exit_code_one_on_unknown_task(self):
        self.queue({"ok": False, "reason": "no such task: t99"})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/tasks/t99/cancel", "x")
        self.assertEqual(code, 1)


class WaitTest(McCliTest):

    def wait(self, task_id, timeout_sec=0.05):
        # poll_interval 0 keeps the loop tight; the tiny timeout lets
        # non-matching cases fall through to the timeout exit fast.
        return self.run_verb(mc.cmd_wait, task_id,
                             timeout_sec=timeout_sec, poll_interval=0)

    def test_returns_zero_on_completed_for_matching_task(self):
        self.queue(batch([event("TASK_COMPLETED", task_id="t14")],
                         latest=1))
        code, out, _ = self.wait("t14")
        self.assertEqual(code, 0)
        self.assertIn("TASK_COMPLETED", out)

    def test_returns_one_on_failed_for_matching_task(self):
        self.queue(batch([event("TASK_FAILED", task_id="t14",
                                reason="NO_PATH")]))
        code, out, _ = self.wait("t14")
        self.assertEqual(code, 1)
        self.assertIn("TASK_FAILED", out)

    def test_cancelled_is_terminal_but_exits_one(self):
        self.queue(batch([event("TASK_CANCELLED", task_id="t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 1)

    def test_dropped_is_terminal_but_exits_one(self):
        self.queue(batch([event("TASK_DROPPED", task_id="t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 1)

    def test_rejected_exits_one(self):
        self.queue(batch([event("TASK_REJECTED", task_id="t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 1)

    def test_timeout_exit_124_and_cursor_not_advanced(self):
        self.queue(batch([event("TASK_COMPLETED", task_id="t99")],
                         latest=1))
        code, _, err = self.wait("t14")
        self.assertEqual(code, 124)
        self.assertIn("timeout", err)
        self.assertEqual(self.cursor_value(), 0)

    def test_advances_since_across_polls_but_not_disk_cursor(self):
        self.queue(
            batch([event("TASK_PAUSED", task_id="t14")], latest=5),
            batch([event("TASK_COMPLETED", task_id="t14")], latest=9),
        )
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["/bot events 0", "/bot events 5"])
        self.assertEqual(self.cursor_value(), 0)

    def test_matches_only_taskid_not_display_name(self):
        self.queue(batch([event("TASK_COMPLETED", task="goto:t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 124)


class EventsCursorTest(McCliTest):

    def test_cursorless_drain_advances_bookmark(self):
        self.queue(batch([], latest=7))
        code, _, _ = self.run_verb(mc.cmd_events)
        self.assertEqual(code, 0)
        self.assertEqual(self.cursor_value(), 7)

    def test_explicit_since_is_a_peek_and_never_advances(self):
        self.queue(batch([], latest=9))
        code, _, _ = self.run_verb(mc.cmd_events, 3)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 3"])
        self.assertEqual(self.cursor_value(), 0)


class RecipeMaterializationTest(McCliTest):
    """dump-recipes pagination and the local-first cat /recipes."""

    def page(self, offset, recipes, total):
        return {"ok": True, "total": total, "offset": offset,
                "count": len(recipes), "recipes": recipes}

    def test_dump_pages_through_and_writes_files(self):
        self.queue(
            self.page(0, [
                {"recipeId": "minecraft:stick",
                 "resultItemId": "minecraft:stick", "resultCount": 4,
                 "patternWidth": 1,
                 "placements": {"0": ["minecraft:oak_planks"],
                                "1": ["minecraft:oak_planks"]}},
                {"recipeId": "minecraft:wooden_pickaxe",
                 "resultItemId": "minecraft:wooden_pickaxe",
                 "resultCount": 1, "patternWidth": 3,
                 "placements": {"0": ["minecraft:oak_planks"],
                                "1": ["minecraft:oak_planks"],
                                "2": ["minecraft:oak_planks"],
                                "4": ["minecraft:stick"],
                                "7": ["minecraft:stick"]}},
            ], 3),
            self.page(2, [
                {"recipeId": "minecraft:anvil",
                 "resultItemId": "minecraft:anvil", "resultCount": 1,
                 "patternWidth": 3,
                 "placements": {"0": ["minecraft:iron_block"]}},
            ], 3),
        )
        code, out, err = self.run_verb(mc.cmd_admin, "dump-recipes")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["recipes list 0 50", "recipes list 2 50"])
        pickaxe = mc.RECIPES_DIR / "wooden_pickaxe"
        self.assertTrue(pickaxe.exists())
        text = pickaxe.read_text(encoding="utf-8")
        self.assertIn("station: crafting_table", text)
        self.assertIn("minecraft:oak_planks x3", text)
        self.assertIn("minecraft:stick x2", text)
        self.assertIn("wooden_pickaxe x1", text)
        stick = mc.RECIPES_DIR / "stick"
        self.assertIn("oak_planks x2", stick.read_text(encoding="utf-8"))

    def test_dump_two_by_two_grid_is_inventory_station(self):
        self.queue(self.page(0, [
            {"recipeId": "minecraft:stick",
             "resultItemId": "minecraft:stick", "resultCount": 4,
             "patternWidth": 1,
             "placements": {"0": ["minecraft:oak_planks"],
                            "1": ["minecraft:oak_planks"]}},
        ], 1))
        code, _, _ = self.run_verb(mc.cmd_admin, "dump-recipes")
        self.assertEqual(code, 0)
        self.assertIn("station: inventory",
            (mc.RECIPES_DIR / "stick").read_text(encoding="utf-8"))

    def test_dump_shapeless_uses_count_rule_for_station(self):
        # Three ingredient indices with placeholder width 3: the
        # geometry replica would see column 2 and demand a table, but
        # the shapeless flag switches to the vanilla count rule
        # (n<=4 fits the inventory 2x2).
        self.queue(self.page(0, [
            {"recipeId": "minecraft:mushroom_stew",
             "resultItemId": "minecraft:mushroom_stew",
             "resultCount": 1, "patternWidth": 3, "shapeless": True,
             "placements": {"0": ["minecraft:brown_mushroom"],
                            "1": ["minecraft:red_mushroom"],
                            "2": ["minecraft:bowl"]}},
            {"recipeId": "minecraft:oak_planks",
             "resultItemId": "minecraft:oak_planks",
             "resultCount": 4, "patternWidth": 3, "shapeless": True,
             "placements": {"0": ["minecraft:oak_log"]}},
        ], 2))
        code, _, _ = self.run_verb(mc.cmd_admin, "dump-recipes")
        self.assertEqual(code, 0)
        stew = (mc.RECIPES_DIR / "mushroom_stew").read_text(encoding="utf-8")
        self.assertIn("station: inventory", stew)
        self.assertIn("minecraft:bowl x1", stew)
        planks = (mc.RECIPES_DIR / "oak_planks").read_text(encoding="utf-8")
        self.assertIn("station: inventory", planks)
        self.assertIn("minecraft:oak_log x1", planks)
        self.assertIn("oak_planks x4", planks)

    def test_cat_prefers_local_file_zero_wire(self):
        recipe = mc.RECIPES_DIR / "wooden_pickaxe"
        recipe.parent.mkdir(parents=True, exist_ok=True)
        recipe.write_text("\n".join([
            "station: crafting_table", "inputs:",
            "  oak_planks x3", "  stick x2", "output:",
            "  wooden_pickaxe x1"]) + "\n", encoding="utf-8")
        code, out, _ = self.run_verb(mc.cmd_cat, "/recipes/wooden_pickaxe")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, [])
        self.assertIn("crafting_table", out)

    def test_cat_falls_back_to_wire_when_not_dumped(self):
        self.queue({"ok": True, "recipes": []})
        code, _, _ = self.run_verb(mc.cmd_cat, "/recipes/nothing_here")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ['recipes "nothing_here"'])


class WriteBlocksPlaceTest(McCliTest):
    """Doc 10: write /blocks/<x,y,z> <blockid>[@face] -> place (sync).
    The value is the contract - the receipt's post-state block must
    match it (the server places the HELD BlockItem)."""

    def test_place_defaults_face_up(self):
        self.queue({"ok": True, "placed": True, "at": [1, 61, 2],
                    "block": "minecraft:stone"})
        code, out, _ = self.run_verb(mc.cmd_write, "/blocks/1,61,1",
                                     "minecraft:stone")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["place 1 61 1 up"])
        self.assertTrue(json.loads(out)["placed"])

    def test_place_face_suffix_translates_lowercase(self):
        self.queue({"ok": True, "placed": True, "at": [1, 61, 2],
                    "block": "minecraft:oak_stairs"})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/1,61,1",
                                   "minecraft:oak_stairs@SOUTH")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["place 1 61 1 south"])

    def test_place_post_state_mismatch_exits_one(self):
        # Asked for planks but the held item was stone: the value is a
        # contract, not a suggestion.
        self.queue({"ok": True, "placed": True, "at": [1, 61, 2],
                    "block": "minecraft:stone"})
        code, _, err = self.run_verb(mc.cmd_write, "/blocks/1,61,1",
                                     "minecraft:oak_planks")
        self.assertEqual(code, 1)
        self.assertIn("mismatch", err)
        self.assertIn("hotbar", err)

    def test_place_rejection_exits_one(self):
        self.queue({"ok": False, "placed": False,
                    "reason": "cell not air: minecraft:stone"})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/1,61,2", "stone")
        self.assertEqual(code, 1)

    def test_place_bad_face_rejected_before_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/blocks/1,61,2",
                                     "stone@sideways")
        self.assertEqual(code, 1)
        self.assertIn("face", err)
        self.assertEqual(self.wire_calls, [])

    def test_place_empty_value_rejected_before_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/blocks/1,61,2", " ")
        self.assertEqual(code, 1)
        self.assertIn("blockid", err)
        self.assertEqual(self.wire_calls, [])


class WriteBlocksInteractTest(McCliTest):
    """/blocks sub-resources: /use runs the block's interaction,
    /sleep is the device-completed bed rest."""

    def test_use_translates_face_with_default(self):
        self.queue({"ok": True, "used": True})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/4,60,9/use", "")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["use 4 60 9 up"])

    def test_use_passes_face_lowercase(self):
        self.queue({"ok": True, "used": True})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/4,60,9/use",
                                   "NORTH")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["use 4 60 9 north"])

    def test_use_bad_face_rejected_before_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/blocks/4,60,9/use",
                                     "left")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_sleep_translates_and_ignores_value(self):
        self.queue({"ok": True, "slept": True})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/6,61,6/sleep",
                                   "ignored")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["sleep 6 61 6"])

    def test_use_rejection_exits_one(self):
        self.queue({"ok": False, "used": False, "reason": "out of reach"})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/4,60,9/use", "up")
        self.assertEqual(code, 1)


class WritePlayerStateTest(McCliTest):
    """Player mutations address /player: sneak latch, hotbar
    selection, held-item use against the POV ray."""

    def test_sneak_translates_on_off(self):
        self.queue({"ok": True})
        code, _, _ = self.run_verb(mc.cmd_write, "/player/sneak", "on")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["sneak on"])

    def test_sneak_bad_value_rejected_before_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/player/sneak", "maybe")
        self.assertEqual(code, 1)
        self.assertIn("on", err)
        self.assertEqual(self.wire_calls, [])

    def test_hotbar_translates_slot(self):
        self.queue({"ok": True, "selectedSlot": 2,
                    "item": "minecraft:stone", "count": 64})
        code, out, _ = self.run_verb(mc.cmd_write, "/player/hotbar", "2")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["equip 2"])
        self.assertEqual(json.loads(out)["selectedSlot"], 2)

    def test_hotbar_out_of_range_rejected_before_wire(self):
        code, _, err = self.run_verb(mc.cmd_write, "/player/hotbar", "9")
        self.assertEqual(code, 1)
        self.assertIn("0..8", err)
        self.assertEqual(self.wire_calls, [])

    def test_hotbar_non_integer_rejected_before_wire(self):
        code, _, _ = self.run_verb(mc.cmd_write, "/player/hotbar", "first")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_held_use_translates_and_ignores_value(self):
        self.queue({"ok": True, "used": True})
        code, _, _ = self.run_verb(mc.cmd_write, "/player/held/use", "")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["use-item"])

    def test_held_use_rejection_exits_one(self):
        self.queue({"ok": False, "used": False, "reason": "empty hand"})
        code, _, _ = self.run_verb(mc.cmd_write, "/player/held/use", "")
        self.assertEqual(code, 1)


class DigAsBlockWriteTest(McCliTest):
    """Doc 10: write /blocks/<x,y,z> air -> /bot dig (job family).
    Breaking is multi-tick, so the receipt is a taskId."""

    def test_air_write_translates_with_default_timeout(self):
        self.queue({"ok": True, "task": "t7", "replay": False})
        code, _, err = self.run_verb(mc.cmd_write, "/blocks/3,61,3", "air")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot dig 3 61 3 1200"])
        self.assertIn("taskId: t7", err)

    def test_air_write_passes_explicit_timeout(self):
        self.queue({"ok": True, "task": "t8", "replay": False})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/-3,60,-4", "air",
                                   timeout=600)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot dig -3 60 -4 600"])

    def test_air_write_rejection_exits_one(self):
        self.queue({"ok": False, "reason": "dig wants integer args"})
        code, _, _ = self.run_verb(mc.cmd_write, "/blocks/1,2,3", "air")
        self.assertEqual(code, 1)


class MineTaskTest(McCliTest):
    """0014: write /tasks/mine -> /bot mine (composite task family)."""

    def test_mine_translates_with_default_timeout(self):
        self.queue({"ok": True, "task": "t9", "replay": False})
        code, _, err = self.run_verb(mc.cmd_write, "/tasks/mine",
                                      "minecraft:stone:3")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ['/bot mine "minecraft:stone" 3 2400'])
        self.assertIn("taskId: t9", err)

    def test_mine_passes_explicit_timeout(self):
        self.queue({"ok": True, "task": "t10", "replay": False})
        code, _, _ = self.run_verb(mc.cmd_write, "/tasks/mine",
                                    "minecraft:dirt:5", timeout=600)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ['/bot mine "minecraft:dirt" 5 600'])

    def test_mine_handles_registry_id_with_colon(self):
        # blockType is a registry id (minecraft:stone) which contains a
        # colon; rsplit(":", 1) must split on the LAST colon only.
        self.queue({"ok": True, "task": "t11", "replay": False})
        code, _, _ = self.run_verb(mc.cmd_write, "/tasks/mine",
                                    "minecraft:deepslate:2")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ['/bot mine "minecraft:deepslate" 2 2400'])

    def test_mine_rejects_malformed_value(self):
        code, _, err = self.run_verb(mc.cmd_write, "/tasks/mine", "stone")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])
        self.assertIn("blockType:count", err)

    def test_mine_rejects_non_integer_count(self):
        code, _, _ = self.run_verb(mc.cmd_write, "/tasks/mine",
                                    "minecraft:stone:abc")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_mine_rejects_non_positive_count(self):
        code, _, _ = self.run_verb(mc.cmd_write, "/tasks/mine",
                                    "minecraft:stone:0")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_mine_rejection_exits_one(self):
        self.queue({"ok": False, "reason": "mine wants blockType count"})
        code, _, _ = self.run_verb(mc.cmd_write, "/tasks/mine",
                                    "minecraft:stone:1")
        self.assertEqual(code, 1)


class HealthFieldTest(McCliTest):
    """0013 slice 2: healthHearts + freeSlots ride /bot status."""

    def test_cat_health(self):
        self.queue({"ok": True, "state": {"healthHearts": 17,
                                          "freeSlots": 12}})
        code, out, _ = self.run_verb(mc.cmd_cat, "/player/health")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["healthHearts"], 17)

    def test_cat_inventory_free(self):
        self.queue({"ok": True, "state": {"freeSlots": 30}})
        code, out, _ = self.run_verb(mc.cmd_cat, "/player/inventory/free")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out), 30)


class WorldReadsTest(McCliTest):
    """0013 slice 1: block / blocks / entities / nearby / follow."""

    def test_cat_block_translates(self):
        self.queue({"ok": True, "pos": [1, 61, 2],
                    "block": "minecraft:stone"})
        code, out, _ = self.run_verb(mc.cmd_cat, "/blocks/1,61,2")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["block 1 61 2"])
        self.assertEqual(json.loads(out)["block"], "minecraft:stone")

    def test_cat_block_negative_coords(self):
        self.queue({"ok": True, "pos": [-3, 60, -4],
                    "block": "minecraft:chest"})
        code, _, _ = self.run_verb(mc.cmd_cat, "/blocks/-3,60,-4")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["block -3 60 -4"])

    def test_cat_block_rejects_malformed_path(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/blocks/1,2")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_cat_nearby_aggregates_and_excludes_self(self):
        self.queue(
            {"ok": True, "state": {"pos": [0, 61, 0],
                                   "task": "idle"}},
            {"ok": True, "truncated": False, "entities": [
                {"id": "self-uuid", "type": "mcbotserver:bot_body",
                 "pos": [0, 61, 0], "health": 20.0,
                 "maxHealth": 20.0, "dist": 0.0, "self": True},
                {"id": "z-1", "type": "minecraft:zombie",
                 "pos": [4, 61, 0], "health": 20.0,
                 "maxHealth": 20.0, "dist": 4.0, "self": False},
            ]},
        )
        code, out, _ = self.run_verb(mc.cmd_cat, "/nearby")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["/bot status", "entities 8 32"])
        result = json.loads(out)
        self.assertEqual(result["pos"], [0, 61, 0])
        self.assertEqual(len(result["nearby"]), 1)
        self.assertEqual(result["nearby"][0]["type"],
                         "minecraft:zombie")

    def test_ls_entities_prints_lines(self):
        self.queue({"ok": True, "truncated": True, "entities": [
            {"id": "c-1", "type": "minecraft:cow", "pos": [3, 61, 3],
             "health": 10.0, "maxHealth": 10.0, "dist": 4.2,
             "self": False},
        ]})
        code, out, err = self.run_verb(mc.cmd_ls, "/entities/")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["entities"])
        self.assertIn("minecraft:cow@3,61,3 id=c-1 hp=10.0/10.0 dist=4.2", out)
        self.assertIn("truncated", err)

    def test_events_follow_terminates_on_idle(self):
        self.queue(batch([], latest=5))
        code, out, err = self.run_verb(mc.cmd_events, follow=True,
                                       idle=0)
        self.assertEqual(code, 0)
        self.assertIn("follow idle", err)

    def test_events_follow_prints_new_events_then_idles(self):
        self.queue(
            batch([], latest=5),
            batch([event("TASK_COMPLETED", task_id="t1")], latest=6),
        )
        code, out, err = self.run_verb(mc.cmd_events, follow=True,
                                       idle=0)
        self.assertEqual(code, 0)
        self.assertIn("TASK_COMPLETED", out)
        # Follow peeks: the bookmark must not advance past the drain.
        self.assertLessEqual(mc.read_cursor(), 6)

    def test_events_without_follow_unchanged(self):
        self.queue(batch([], latest=7))
        code, _, _ = self.run_verb(mc.cmd_events)
        self.assertEqual(code, 0)
        self.assertEqual(self.cursor_value(), 7)


class StreamResetTest(McCliTest):
    """Cross-restart cursor trap, found live by shadow_compare: the
    event stream does not survive bot restarts (boundary D), so a
    stored bookmark from a previous epoch points past the new head and
    wait would stare at nothing forever. resetAt is the wire signal."""

    def write_stale_bookmark(self, event_id, epoch):
        mc.CURSOR_PATH.write_text(f"{event_id} {epoch}\n",
                                  encoding="utf-8")

    def test_wait_reanchors_on_epoch_change(self):
        self.write_stale_bookmark(500, 3)
        # First poll (stale since=500) carries the NEW epoch; the
        # re-anchored second poll (since=0) delivers the terminal.
        self.queue(
            batch([], latest=500, reset_at=7),
            batch([event("TASK_COMPLETED", task_id="t14")],
                  latest=12, reset_at=7),
        )
        code, _, err = self.run_verb(mc.cmd_wait, "t14",
                                     timeout_sec=0.5, poll_interval=0)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 500",
                                           "/bot events 0"])
        self.assertIn("reset", err)
        # wait re-anchors in-memory only; the bookmark stays untouched.
        self.assertEqual(mc.read_cursor_state(), (500, 3))

    def test_events_reanchors_and_rewrites_bookmark(self):
        self.write_stale_bookmark(500, 3)
        self.queue(
            batch([], latest=500, reset_at=7),
            batch([], latest=12, reset_at=7),
        )
        code, _, err = self.run_verb(mc.cmd_events)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 500",
                                           "/bot events 0"])
        self.assertEqual(mc.read_cursor_state(), (12, 7))
        self.assertIn("reset", err)

    def test_cursor_state_roundtrip_and_legacy_format(self):
        mc.write_cursor_state(5, 9)
        self.assertEqual(mc.read_cursor_state(), (5, 9))
        mc.CURSOR_PATH.write_text("5\n", encoding="utf-8")
        # Legacy single-int file parses as epoch 0, which never
        # matches a live stream (resetAt starts at 1) - one-time
        # re-anchor on first comparison, by design.
        self.assertEqual(mc.read_cursor_state(), (5, 0))

    def test_wait_reanchors_when_cursor_beyond_head(self):
        # resetAt does NOT signal a JVM restart (fresh queue starts at
        # 1 again - found live: epochs collide across boots). The
        # reliable signal is the id space restarting: a bookmark
        # beyond the stream head is impossible in a monotonic stream.
        self.write_stale_bookmark(837, 1)
        self.queue(
            batch([], latest=375, reset_at=1),
            batch([event("TASK_COMPLETED", task_id="t14")],
                  latest=380, reset_at=1),
        )
        code, _, err = self.run_verb(mc.cmd_wait, "t14",
                                     timeout_sec=0.5, poll_interval=0)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 837",
                                           "/bot events 0"])
        self.assertIn("beyond stream head", err)

    def test_events_reanchors_when_cursor_beyond_head(self):
        self.write_stale_bookmark(837, 1)
        self.queue(
            batch([], latest=375, reset_at=1),
            batch([], latest=379, reset_at=1),
        )
        code, _, err = self.run_verb(mc.cmd_events)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 837",
                                           "/bot events 0"])
        self.assertEqual(mc.read_cursor_state(), (379, 1))
        self.assertIn("beyond stream head", err)


class AdminVerbTest(McCliTest):

    def test_admin_stop_sweeps_all_tasks(self):
        self.queue({"ok": True, "cancelled": 2})
        code, _, _ = self.run_verb(mc.cmd_admin, "stop")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot stop"])

    def test_admin_reset_clears_crash_latch(self):
        self.queue({"ok": True, "crashed": False})
        code, _, _ = self.run_verb(mc.cmd_admin, "reset")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot reset"])

    def test_unknown_action_rejected(self):
        code, _, _ = self.run_verb(mc.cmd_admin, "reboot")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])


class MainDispatchTest(McCliTest):

    def test_main_routes_admin_stop(self):
        self.queue({"ok": True, "cancelled": 0})
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            code = mc.main(["admin", "stop"])
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot stop"])

    def test_main_cat_unknown_path_exits_one(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            code = mc.main(["cat", "/unknown/path"])
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])

    def test_main_write_accepts_negative_coordinates(self):
        # Found live by shadow_compare: "-60,64,0" parses as an option
        # token; the reorder shim must route it through as the value.
        self.queue({"ok": True, "task": "t9", "replay": False})
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            code = mc.main(["write", "/tasks/goto", "-60,64,0",
                            "--tol", "1"])
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot goto -60 64 0 1 1200"])


class StationPathParseTest(unittest.TestCase):
    """parse_station_path: /stations/<type>@<x,y,z>/<role>."""

    def test_full_path_with_role(self):
        result = mc.parse_station_path("/stations/furnace@10,64,28/input")
        self.assertIsNotNone(result)
        self.assertEqual(result["type"], "furnace")
        self.assertEqual(result["x"], 10)
        self.assertEqual(result["y"], 64)
        self.assertEqual(result["z"], 28)
        self.assertEqual(result["role"], "INPUT")

    def test_path_without_role(self):
        result = mc.parse_station_path("/stations/chest@1,2,3/")
        self.assertIsNotNone(result)
        self.assertEqual(result["type"], "chest")
        self.assertEqual(result["role"], None)

    def test_role_is_case_insensitive(self):
        result = mc.parse_station_path("/stations/furnace@1,2,3/Fuel")
        self.assertEqual(result["role"], "FUEL")

    def test_stations_root_returns_none(self):
        # /stations/ itself is handled by cmd_ls, not parse.
        self.assertIsNone(mc.parse_station_path("/stations/"))
        self.assertIsNone(mc.parse_station_path("/stations"))

    def test_missing_at_returns_none(self):
        self.assertIsNone(mc.parse_station_path("/stations/chest1,2,3/"))

    def test_malformed_coords_returns_none(self):
        self.assertIsNone(mc.parse_station_path("/stations/chest@1,2/"))
        self.assertIsNone(mc.parse_station_path("/stations/chest@a,b,c/"))

    def test_negative_coordinates_parse(self):
        result = mc.parse_station_path("/stations/chest@-60,64,0/")
        self.assertIsNotNone(result)
        self.assertEqual(result["x"], -60)
        self.assertEqual(result["z"], 0)


class LsStationsTest(McCliTest):
    """ls /stations/ -> scan 16 50."""

    def test_translates_to_scan_with_defaults(self):
        self.queue({"ok": True, "containers": [
            {"type": "chest", "x": 10, "y": 64, "z": 20},
            {"type": "furnace", "x": 12, "y": 64, "z": 22},
        ], "truncated": False})
        code, out, _ = self.run_verb(mc.cmd_ls, "/stations/")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["scan 16 50"])
        lines = out.strip().split("\n")
        self.assertEqual(lines, ["chest@10,64,20", "furnace@12,64,22"])

    def test_reports_truncated_on_stderr(self):
        self.queue({"ok": True, "containers": [], "truncated": True})
        code, _, err = self.run_verb(mc.cmd_ls, "/stations/")
        self.assertEqual(code, 0)
        self.assertIn("truncated", err)

    def test_scan_failure_exits_one(self):
        self.queue({"ok": False, "reason": "out of range"})
        code, _, err = self.run_verb(mc.cmd_ls, "/stations/")
        self.assertEqual(code, 1)
        self.assertIn("scan failed", err)


class CatStationTest(McCliTest):
    """cat /stations/<t>@<pos>/[<role>] -> open + snapshot + close
    (complete transaction - doc 10 fold, read is documents-only)."""

    def test_full_snapshot_opens_reads_closes(self):
        # Mocks mirror the REAL wire shape: the snapshot nests under
        # "menu" in every menu-command reply (MenuCommands/MenuViewJson).
        self.queue(
            {"ok": True, "menu": {
                "type": "chest", "sourcePos": [1, 2, 3],
                "containerSize": 1,
                "slots": [{"role": "CONTAINER", "index": 0,
                           "item": {"id": "minecraft:iron_ingot",
                                    "count": 8}}]}},
            {"ok": True},  # close
        )
        code, out, _ = self.run_verb(mc.cmd_cat, "/stations/chest@1,2,3/")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3", "menu close"])
        result = json.loads(out)
        self.assertEqual(result["type"], "chest")
        self.assertEqual(len(result["slots"]), 1)

    def test_role_filter_is_client_side(self):
        self.queue(
            {"ok": True, "menu": {
                "type": "furnace", "sourcePos": [1, 2, 3],
                "containerSize": 3,
                "slots": [
                    {"role": "INPUT", "index": 0,
                     "item": {"id": "minecraft:iron_ore", "count": 8}},
                    {"role": "FUEL", "index": 1,
                     "item": {"id": "minecraft:coal", "count": 16}},
                    {"role": "OUTPUT", "index": 2,
                     "item": {"id": "minecraft:iron_ingot", "count": 3}},
                ]}},
            {"ok": True},
        )
        code, out, _ = self.run_verb(
            mc.cmd_cat, "/stations/furnace@1,2,3/output")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3", "menu close"])
        result = json.loads(out)
        self.assertEqual(result["role"], "output")
        self.assertEqual(len(result["slots"]), 1)
        self.assertEqual(result["slots"][0]["item"]["id"],
                         "minecraft:iron_ingot")

    def test_open_failure_exits_one_without_snapshot_or_close(self):
        self.queue({"ok": False, "reason": "out of reach"})
        code, _, err = self.run_verb(mc.cmd_cat, "/stations/chest@1,2,3/")
        self.assertEqual(code, 1)
        self.assertIn("cannot open", err)
        self.assertEqual(self.wire_calls, ["menu open 1 2 3"])

    def test_role_filter_with_no_matching_role_is_empty(self):
        self.queue(
            {"ok": True, "menu": {
                "type": "chest", "sourcePos": [1, 2, 3],
                "containerSize": 1,
                "slots": [{"role": "CONTAINER", "index": 0,
                           "item": {"id": "minecraft:stone",
                                    "count": 5}}]}},
            {"ok": True},
        )
        code, out, _ = self.run_verb(
            mc.cmd_cat, "/stations/chest@1,2,3/output")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3", "menu close"])
        self.assertEqual(json.loads(out)["slots"], [])

    def test_invalid_path_exits_one(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/stations/chest1,2,3/")
        self.assertEqual(code, 1)
        self.assertIn("invalid station path", err)
        self.assertEqual(self.wire_calls, [])


class WriteStationTest(McCliTest):
    """write /stations/<t>@<pos>/<role> <value> -> deposit/take/craft."""

    def test_deposit_input_translates_to_menu_deposit(self):
        self.queue(
            {"ok": True},  # open
            {"ok": True, "placed": 8},
            {"ok": True},  # close
        )
        code, out, _ = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/input", "iron_ingot:8")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3",
                          "menu deposit INPUT \"iron_ingot\" 8",
                          "menu close"])
        self.assertEqual(json.loads(out)["placed"], 8)

    def test_deposit_fuel_translates_to_fuel_role(self):
        self.queue({"ok": True}, {"ok": True, "placed": 16}, {"ok": True})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/stations/furnace@1,2,3/fuel", "coal:16")
        self.assertEqual(code, 0)
        self.assertIn('menu deposit FUEL "coal" 16', self.wire_calls)

    def test_take_output_all_omits_count(self):
        self.queue({"ok": True}, {"ok": True, "taken": [
            {"item": "iron_ingot", "count": 3}]}, {"ok": True})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/stations/furnace@1,2,3/output", "all")
        self.assertEqual(code, 0)
        self.assertIn("menu take OUTPUT", self.wire_calls)
        # "all" must NOT append a count argument.
        self.assertNotIn("menu take OUTPUT all", self.wire_calls)

    def test_take_output_n_appends_count(self):
        self.queue({"ok": True}, {"ok": True, "taken": []}, {"ok": True})
        code, _, _ = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/output", "8")
        self.assertEqual(code, 0)
        self.assertIn("menu take OUTPUT 8", self.wire_calls)

    def test_craft_recipe_translates_to_menu_craft(self):
        self.queue({"ok": True},
                   {"ok": True, "result": "wooden_pickaxe", "count": 1},
                   {"ok": True})
        code, out, _ = self.run_verb(
            mc.cmd_write, "/stations/crafting@1,2,3/recipe", "wooden_pickaxe")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3",
                          "menu craft \"wooden_pickaxe\"",
                          "menu close"])
        self.assertEqual(json.loads(out)["result"], "wooden_pickaxe")

    def test_deposit_without_colon_rejected_before_wire(self):
        code, _, err = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/input", "iron_ingot")
        self.assertEqual(code, 1)
        self.assertIn("item:count", err)
        self.assertEqual(self.wire_calls, [])

    def test_take_with_invalid_value_rejected(self):
        code, _, err = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/output", "half")
        self.assertEqual(code, 1)
        self.assertIn("all", err)
        self.assertEqual(self.wire_calls, [])

    def test_unknown_role_rejected(self):
        code, _, err = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/armor", "diamond:1")
        self.assertEqual(code, 1)
        self.assertIn("unknown role", err)
        self.assertEqual(self.wire_calls, [])

    def test_missing_role_rejected(self):
        code, _, err = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/", "iron_ingot:8")
        self.assertEqual(code, 1)
        self.assertIn("role segment", err)
        self.assertEqual(self.wire_calls, [])

    def test_open_failure_exits_one_without_operation(self):
        self.queue({"ok": False, "reason": "out of reach"})
        code, _, err = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/input", "iron_ingot:8")
        self.assertEqual(code, 1)
        self.assertIn("cannot open", err)
        self.assertEqual(self.wire_calls, ["menu open 1 2 3"])

    def test_operation_failure_still_closes(self):
        self.queue(
            {"ok": True},
            {"ok": False, "reason": "not enough", "have": 3},
            {"ok": True},
        )
        code, out, _ = self.run_verb(
            mc.cmd_write, "/stations/chest@1,2,3/input", "iron_ingot:8")
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out)["reason"], "not enough")
        self.assertEqual(self.wire_calls,
                         ["menu open 1 2 3",
                          "menu deposit INPUT \"iron_ingot\" 8",
                          "menu close"])


class CatRecipesTest(McCliTest):
    """cat /recipes/<item> -> recipes <item>."""

    def test_translates_to_recipes(self):
        self.queue({"ok": True, "recipeId": "minecraft:stick",
                    "result": "stick", "count": 4,
                    "inputs": [{"item": "plank", "count": 2}]})
        code, out, _ = self.run_verb(mc.cmd_cat, "/recipes/stick")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ['recipes "stick"'])
        self.assertEqual(json.loads(out)["result"], "stick")

    def test_empty_item_rejected(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/recipes/")
        self.assertEqual(code, 1)
        self.assertIn("item id", err)
        self.assertEqual(self.wire_calls, [])

    def test_recipes_failure_exits_one(self):
        self.queue({"ok": False, "reason": "unknown item"})
        code, _, _ = self.run_verb(mc.cmd_cat, "/recipes/nonexistent")
        self.assertEqual(code, 1)


class ResidueQueueTest(McCliTest):
    """0015 near-term queue residue: events --only, ls /, help, and
    the isatty stream discipline (piped stdout is one-line JSON)."""

    def test_events_only_rides_the_wire_narrowing(self):
        self.queue(batch([event("TASK_COMPLETED", task_id="t1")], latest=1))
        code, _, _ = self.run_verb(mc.cmd_events, 0, only="TASK")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot events 0 only TASK"])

    def test_events_without_only_omits_the_clause(self):
        self.queue(batch([event("TASK_COMPLETED", task_id="t1")], latest=1))
        self.run_verb(mc.cmd_events, 0)
        self.assertEqual(self.wire_calls, ["/bot events 0"])

    def test_ls_root_lists_canonical_roots(self):
        code, out, _ = self.run_verb(mc.cmd_ls, "/")
        self.assertEqual(code, 0)
        for root in ("/tasks/", "/player/", "/blocks/", "/entities/",
                     "/nearby/", "/recipes/", "/stations/", "/events"):
            self.assertIn(root, out)
        self.assertNotIn("/actions/", out, "the drawer is retired")

    def test_help_overview_and_per_verb(self):
        code, out, _ = self.run_verb(mc.cmd_help)
        self.assertEqual(code, 0)
        self.assertIn("wait", out)
        code2, out2, _ = self.run_verb(mc.cmd_help, "wait")
        self.assertEqual(code2, 0)
        self.assertIn("example:", out2)
        self.assertIn("124", out2)

    def test_unknown_help_verb_is_typed_error(self):
        code, _, err = self.run_verb(mc.cmd_help, "frobnicate")
        self.assertEqual(code, 1)
        self.assertIn("frobnicate", err)

    def test_piped_stdout_is_one_line_json(self):
        self.queue({"ok": True, "selectedSlot": 3, "item": "", "count": 0})
        code, out, _ = self.run_verb(mc.cmd_write, "/player/hotbar", "3")
        self.assertEqual(code, 0)
        self.assertEqual(out.count(chr(10)), 1,
                         "piped answers are one-line JSON")

    def test_tty_stdout_is_indented(self):
        out, err = io.StringIO(), io.StringIO()
        self.queue({"ok": True, "selectedSlot": 3, "item": "", "count": 0})
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with mock.patch.object(out, "isatty", return_value=True):
                code = mc.cmd_write("/player/hotbar", "3")
        self.assertEqual(code, 0)
        self.assertGreater(out.getvalue().count(chr(10)), 1,
                           "a tty answer is human-indented")


class LsUniversalityTest(McCliTest):
    """Doc 2 utility-noun orthogonality: every root answers ls with
    an enumeration or a typed explanation - the conformance gate
    that keeps the surface self-describing (no per-root verbs)."""

    def test_player_root_lists_fields_zero_wire(self):
        code, out, _ = self.run_verb(mc.cmd_ls, "/player")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, [])
        for field in ("inventory", "pos", "health", "sneak", "hotbar",
                      "held/use"):
            self.assertIn(field, out)

    def test_recipes_root_lists_dumped_cache(self):
        mc.RECIPES_DIR.mkdir(parents=True, exist_ok=True)
        (mc.RECIPES_DIR / "stick").write_text("station: inventory\n",
                                              encoding="utf-8")
        (mc.RECIPES_DIR / "oak_planks").write_text("station: inventory\n",
                                                   encoding="utf-8")
        code, out, _ = self.run_verb(mc.cmd_ls, "/recipes/")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, [])
        self.assertIn("stick", out)
        self.assertIn("oak_planks", out)

    def test_recipes_root_empty_cache_is_typed_hint(self):
        if mc.RECIPES_DIR.is_dir():
            for f in mc.RECIPES_DIR.iterdir():
                f.unlink()
        code, _, err = self.run_verb(mc.cmd_ls, "/recipes/")
        self.assertEqual(code, 1)
        self.assertIn("dump-recipes", err)

    def test_blocks_root_type_rejects_with_addressing_hint(self):
        code, _, err = self.run_verb(mc.cmd_ls, "/blocks")
        self.assertEqual(code, 1)
        self.assertIn("not enumerable", err)
        self.assertIn("cat /blocks/", err)

    def test_events_root_type_rejects_with_stream_hint(self):
        code, _, err = self.run_verb(mc.cmd_ls, "/events")
        self.assertEqual(code, 1)
        self.assertIn("stream", err)

    def test_entities_listing_prints_the_attackable_id(self):
        self.queue({"ok": True, "entities": [
            {"id": "cow-uuid-1", "type": "minecraft:cow",
             "pos": [4, 64, 2], "health": 10, "maxHealth": 10,
             "dist": 4.6, "self": False}]})
        code, out, _ = self.run_verb(mc.cmd_ls, "/entities/")
        self.assertEqual(code, 0)
        self.assertIn("id=cow-uuid-1", out,
                      "attack targets need the id column")


class WriteEntitiesAttackTest(McCliTest):
    """Ledger 39: write /entities/<id>/attack -> /bot attack (job).
    The directed engagement - the harness names the entity, the bot
    walks to it and fights until the scan says it is gone."""

    def test_attack_translates_with_default_timeout(self):
        self.queue({"ok": True, "task": "t9", "replay": False})
        code, _, err = self.run_verb(mc.cmd_write,
                                     "/entities/cow-uuid-1/attack", "")
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot attack cow-uuid-1 1200"])
        self.assertIn("taskId: t9", err)

    def test_attack_passes_explicit_timeout(self):
        self.queue({"ok": True, "task": "t10", "replay": False})
        code, _, _ = self.run_verb(mc.cmd_write,
                                   "/entities/cow-uuid-1/attack", "",
                                   timeout=600)
        self.assertEqual(code, 0)
        self.assertEqual(self.wire_calls, ["/bot attack cow-uuid-1 600"])

    def test_attack_rejection_exits_one(self):
        self.queue({"ok": False, "reason": "attack wants a target id"})
        code, _, _ = self.run_verb(mc.cmd_write,
                                   "/entities/cow-uuid-1/attack", "")
        self.assertEqual(code, 1)

    def test_entity_read_paths_still_reject_writes(self):
        code, _, err = self.run_verb(mc.cmd_write, "/entities/cow-uuid-1",
                                     "pet")
        self.assertEqual(code, 1)
        self.assertEqual(self.wire_calls, [])


if __name__ == "__main__":
    unittest.main()
