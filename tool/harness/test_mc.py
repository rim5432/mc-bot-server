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


def batch(events, latest=0):
    """One /bot events reply in the exact EventBatchJson shape."""
    return {"ok": True,
            "batch": {"latest": latest, "resetAt": 0, "dropped": 0,
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
    """cat/read misselection is a typed error, never substitution."""

    def test_cat_station_path_suggests_read(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/stations/chest@1,2,3/")
        self.assertEqual(code, 1)
        self.assertIn("read", err)

    def test_cat_station_prefix_without_coords_suggests_read(self):
        code, _, err = self.run_verb(mc.cmd_cat, "/stations")
        self.assertEqual(code, 1)
        self.assertIn("read", err)

    def test_read_flat_state_suggests_cat(self):
        code, _, err = self.run_verb(mc.cmd_read, "/player/pos")
        self.assertEqual(code, 1)
        self.assertIn("cat", err)

    def test_read_station_path_reports_wire_pending(self):
        code, _, err = self.run_verb(mc.cmd_read, "/stations/chest@1,2,3/")
        self.assertEqual(code, 1)
        self.assertIn("pending", err)
        self.assertNotIn("`mc cat", err)

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

    def test_echoes_task_id_on_success(self):
        self.queue({"ok": True, "task": "t1", "replay": False})
        code, _, err = self.run_verb(
            mc.cmd_write, "/tasks/goto", "100,64,200")
        self.assertEqual(code, 0)
        self.assertIn("taskId: t1", err)


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

    def test_cancelled_is_terminal(self):
        self.queue(batch([event("TASK_CANCELLED", task_id="t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 0)

    def test_dropped_is_terminal(self):
        self.queue(batch([event("TASK_DROPPED", task_id="t14")]))
        code, _, _ = self.wait("t14")
        self.assertEqual(code, 0)

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
            code = mc.main(["cat", "/recipes/stick"])
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


if __name__ == "__main__":
    unittest.main()
