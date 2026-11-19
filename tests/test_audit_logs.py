import contextlib
import gzip
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "scripts" / "audit_logs.py"
SPEC = importlib.util.spec_from_file_location("fkclaude_audit_logs", MODULE)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def event(seq=1, session="session-a", category="network", action="http_request",
          outcome="attempted", details=None, **overrides):
    result = {"schema": "fkclaude.audit.v1", "session_id": session, "seq": seq,
              "time": "2026-11-17T00:00:00Z", "elapsed_ms": seq, "pid": 123,
              "thread_id": 1, "category": category, "action": action, "source": "AuditHooks",
              "outcome": outcome, "evidence": "observed", "details": details or {}}
    result.update(overrides)
    return result


class AuditLogAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, records, tail=b""):
        path = self.root / name
        path.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in records) + tail)
        return path

    def test_duplicate_merge_and_conflicts_use_session_and_sequence(self):
        first = self.write("first.jsonl", [event(1), event(2)])
        second = self.write("second.jsonl.1", [event(1), event(2)])
        clean = audit.analyze([first, second])
        self.assertEqual(2, clean["input"]["unique_valid_events"])
        self.assertEqual(2, clean["input"]["duplicate_events"])
        self.assertTrue(clean["complete_input"])
        third = self.write("third.jsonl", [event(1, outcome="blocked")])
        conflict = audit.analyze([first, third])
        self.assertEqual(1, conflict["invalid"]["duplicate_conflict"])
        self.assertEqual(2, conflict["input"]["unique_valid_events"])
        self.assertFalse(conflict["complete_input"])

    def test_unknown_category_is_retained_and_invalid_json_is_reported(self):
        path = self.write("events.jsonl", [
            event(1, category="new_category"),
            event(2, schema="unexpected.schema"),
            event(seq=True),
        ], tail=b"{not json}\n")
        report = audit.analyze([path], non_conversation=True)
        self.assertEqual({"unknown": 1}, report["categories"])
        self.assertEqual(1, report["unrecognized"]["category_events"])
        self.assertEqual({"json": 1, "schema": 1, "type": 1}, report["invalid"])
        self.assertFalse(report["complete_input"])

    def test_camera_and_microphone_are_local_categories_in_every_view(self):
        path = self.write("media.ndjson", [
            event(1, category="camera", action="api_access", outcome="called"),
            event(2, category="microphone", action="api_access", outcome="called"),
            event(3),
        ])
        all_events = audit.analyze([path])
        self.assertEqual({"camera": 1, "microphone": 1, "network": 1}, all_events["categories"])
        self.assertEqual(0, all_events["unrecognized"]["category_events"])
        self.assertEqual(3, all_events["input"]["selected_events"])
        network = audit.analyze([path], non_conversation=True)
        self.assertEqual({"network": 1}, network["categories"])
        self.assertEqual(1, network["input"]["selected_events"])
        self.assertEqual(2, network["input"]["filtered_events"])
        self.assertEqual({"attempted": 1}, network["network_phase_events"])
        self.assertEqual(0, network["unrecognized"]["category_events"])

    def test_redacted_dns_templates_survive_export_analysis(self):
        template = ":id.datadog.pool.ntp.org"
        for hostname in (template, "api.:id.example.com", ":id.:id.example.com"):
            with self.subTest(hostname=hostname):
                self.assertEqual(hostname, audit._host(hostname))
                self.assertEqual(hostname, audit._host(audit._host(hostname)))
        self.assertEqual(template, audit._host(":ID.DATADOG.POOL.NTP.ORG."))
        path = self.write("hosts.ndjson", [
            event(1, details={"host": template, "path": "/api/v2/rum", "method": "POST"}),
            event(2, details={"url": "https://" + template + ":443/api/v2/rum",
                              "method": "POST"}),
            event(3, details={"url": "https://PRIVATE-USER:PRIVATE-PASSWORD@api.:id.example.com"
                              ":443/api/v2/rum?q=PRIVATE-QUERY#PRIVATE-FRAGMENT",
                              "method": "POST"}),
        ])
        report = audit.analyze([path])
        self.assertTrue(report["complete_input"])
        by_endpoint = {row["endpoint"]: row for row in report["endpoints"]}
        self.assertEqual(2, by_endpoint["POST " + template + " /api/v2/rum"]["observation_events"])
        self.assertIn("POST api.:id.example.com /api/v2/rum", by_endpoint)
        self.assertNotIn("PRIVATE-", json.dumps(report))

    def test_ip_and_unrecognized_colon_hosts_still_redact(self):
        for host in ("192.0.2.1", "2001:db8::1", "[2001:db8::1]", "::1",
                     "private:secret.example.com", ":private.example.com",
                     ":id:secret.example.com", "api.example.com:secret", "[ip]"):
            with self.subTest(host=host):
                self.assertEqual("[ip]", audit._host(host))
        for host in (":id..example.com", ":id.example.com/private", ":id.@example.com"):
            with self.subTest(invalid_host=host):
                self.assertEqual("[unknown]", audit._host(host))
        path = self.write("ip.ndjson", [
            event(1, details={"url": "https://192.0.2.1:443/api"}),
            event(2, details={"url": "https://[2001:db8::1]:443/api"}),
        ])
        report = audit.analyze([path])
        self.assertEqual("UNKNOWN [ip] /api", report["endpoints"][0]["endpoint"])
        self.assertEqual(2, report["endpoints"][0]["observation_events"])
        self.assertNotIn("192.0.2.1", json.dumps(report))
        self.assertNotIn("2001:db8", json.dumps(report))

    def test_network_stages_and_native_returns_are_separate_measurements(self):
        http = {"request_id": "r1", "method": "POST", "host": "claude.ai",
                "path": "/event_logging/v2/batch", "transport": "okhttp"}
        blocked = dict(http, request_id="r2")
        native = {"layer": "native", "caller": "application", "bytes": "100"}
        probe = {"layer": "native", "caller": "fkclaude_ip_guard_probe", "bytes": "20",
                 "classification": "instrumentation"}
        path = self.write("events.jsonl", [
            event(1, details=http),
            event(2, details=http, outcome="response_received"),
            event(3, details=blocked, outcome="blocked"),
            event(4, action="native_send", outcome="returned", source="libc.send", details=native),
            event(5, action="native_write", outcome="failed", source="libc.write", details=dict(native, bytes="900")),
            event(6, action="native_write", outcome="attempted", source="libc.write", details=dict(native, bytes="800")),
            event(7, action="native_write", outcome="returned", source="libc.write", details=probe),
        ])
        report = audit.analyze([path])
        self.assertEqual(2, report["network_phase_events"]["attempted"])
        self.assertEqual(1, report["network_phase_events"]["response_received"])
        self.assertEqual(2, report["correlation"]["operation_groups"])
        self.assertIsNone(report["correlation"]["independent_upload_count"])
        self.assertEqual({"application": 100, "instrumentation": 20, "unknown": 0},
                         report["native_io"]["observed_socket_return_bytes"])
        self.assertEqual(3, report["endpoints"][0]["observation_events"])
        self.assertEqual(2, report["endpoints"][0]["correlated_operations"])

    def test_request_ids_do_not_merge_between_sessions_documents_or_views(self):
        paths = self.write("web.jsonl", [
            event(1, session="process-a", details={"request_id": "r1"}),
            event(1, session="process-b", details={"request_id": "r1"}),
            event(2, session="process-a", details={"request_id": "w1", "document_id": "d1", "view_id": "1"}),
            event(3, session="process-a", details={"request_id": "w1", "document_id": "d2", "view_id": "1"}),
            event(4, session="process-a", details={"request_id": "w1", "document_id": "d2", "view_id": "2"}),
            event(5, session="process-a", outcome="response_received",
                  details={"request_id": "w1", "document_id": "d2", "view_id": "2"}),
        ])
        report = audit.analyze([paths])
        self.assertEqual(5, report["correlation"]["operation_groups"])
        self.assertEqual(1, report["correlation"]["groups_with_outcome"]["response_received"])

    def test_incomplete_tail_differs_from_valid_json_without_newline(self):
        bad = self.write("broken.jsonl", [event()], tail=b'{"schema":"fkclaude.audit.v1","seq":')
        report = audit.analyze([bad])
        self.assertEqual(1, report["invalid"]["json"])
        self.assertEqual(1, report["truncated"]["incomplete_tail_lines"])
        valid = self.root / "valid.jsonl"
        valid.write_text(json.dumps(event()))
        report = audit.analyze([valid])
        self.assertEqual(1, report["input"]["unique_valid_events"])
        self.assertEqual(1, report["truncated"]["valid_json_without_final_newline"])
        self.assertEqual(0, report["truncated"]["incomplete_tail_lines"])
        self.assertTrue(report["complete_input"])

    def test_sequence_check_runs_before_the_view_filter(self):
        path = self.write("events.jsonl", [
            event(1), event(2, category="device"), event(3, category="conversation"), event(5)
        ])
        report = audit.analyze([path], non_conversation=True)
        self.assertEqual(2, report["input"]["selected_events"])
        self.assertEqual(1, report["sequence"]["internal_missing_sequences"])
        self.assertEqual([[4, 4]], report["sequence"]["sessions"][0]["first_gap_ranges"])
        self.assertTrue(report["complete_input"])  # A gap is an observation, not a tampering verdict.

    def test_report_never_echoes_paths_payloads_raw_ids_or_unknown_labels(self):
        path = self.write("PRIVATE-FILENAME.jsonl", [
            event(1, session="PRIVATE-SESSION", action="PRIVATE-ACTION", details={
                "url": "https://user:PRIVATE-PASSWORD@claude.ai/api/organizations/PRIVATE-ACCOUNT/"
                       "chat_conversations/PRIVATE-CHAT?q=PRIVATE-PROMPT#PRIVATE-FRAGMENT",
                "body": "PRIVATE-BODY",
                "Authorization": "PRIVATE-TOKEN",
            }),
            event(2, session="PRIVATE-SESSION", category="PRIVATE-CATEGORY"),
        ])
        report = audit.analyze([path])
        serialized = json.dumps(report)
        self.assertNotIn("PRIVATE-", serialized)
        self.assertEqual("UNKNOWN claude.ai /api/organizations/:id/chat_conversations/:id",
                         report["endpoints"][0]["endpoint"])
        self.assertEqual(1, report["actions"]["other"])
        self.assertEqual(1, report["categories"]["unknown"])

    def test_directory_selection_ignores_non_jsonl_and_duplicate_file_arguments(self):
        path = self.write("events.jsonl", [event()])
        self.write("not-a-log.txt", [event(2)])
        self.write("inventory.json", [event(3)])
        report = audit.analyze([self.root, path])
        self.assertEqual(1, report["input"]["files_read"])
        self.assertEqual(1, report["input"]["unique_valid_events"])
        self.assertEqual(1, report["input"]["duplicate_files_ignored"])
        self.assertTrue(report["complete_input"])

    def test_saf_ndjson_entrypoint_and_rotated_directory_inputs(self):
        manifest = {"schema": "fkclaude.audit.export.v1", "retained": 2,
                    "max_events": 1000, "queue_dropped": 0, "disk_errors": 0}
        exported = self.write("13-saf-export.ndjson", [manifest, event(1), event(2)])
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            status = audit.main([str(exported), "--json"])
        report = json.loads(captured.getvalue())
        self.assertEqual(0, status)
        self.assertEqual(1, report["input"]["files_read"])
        self.assertEqual(1, report["input"]["export_manifests"])
        self.assertEqual(2, report["input"]["unique_valid_events"])
        self.assertEqual(0, report["input"]["non_jsonl_files_ignored"])

        self.write("rotated.ndjson.1", [event(3)])
        self.write("unrelated.txt", [event(4)])
        directory_report = audit.analyze([self.root])
        self.assertTrue(directory_report["complete_input"])
        self.assertEqual(2, directory_report["input"]["files_read"])
        self.assertEqual(3, directory_report["input"]["unique_valid_events"])

    def test_input_budgets_and_oversized_lines_are_explicit(self):
        path = self.write("events.jsonl", [event(1), event(2)])
        byte_limited = audit.analyze([path], max_bytes=10)
        self.assertTrue(byte_limited["truncated"]["limits_reached"]["bytes"])
        self.assertFalse(byte_limited["complete_input"])
        event_limited = audit.analyze([path], max_events=1)
        self.assertTrue(event_limited["truncated"]["limits_reached"]["events"])
        self.assertEqual(1, event_limited["input"]["unique_valid_events"])
        huge = self.root / "huge.jsonl"
        huge.write_bytes(b"x" * (audit.MAX_LINE + 4) + b"\n" + json.dumps(event()).encode() + b"\n")
        report = audit.analyze([huge])
        self.assertEqual(1, report["invalid"]["oversized_line"])
        self.assertEqual(1, report["input"]["unique_valid_events"])

    def test_export_retained_detects_whole_line_truncation_and_extra_events(self):
        for name, expected, rows in (
            ("missing-last", 3, [event(1), event(2)]),
            ("manifest-only", 3, []),
            ("extra", 2, [event(1), event(2), event(3)]),
        ):
            with self.subTest(case=name):
                manifest = {"schema": "fkclaude.audit.export.v1", "retained": expected}
                path = self.write(name + ".ndjson", [manifest, *rows])
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    status = audit.main([str(path), "--json"])
                report = json.loads(output.getvalue())
                self.assertEqual(2, status)
                self.assertFalse(report["complete_input"])
                self.assertEqual(1, report["invalid"]["export_retained_mismatch"])
                self.assertEqual(1, report["export_validation"]["mismatched_blocks"])
                self.assertEqual(expected, report["export_validation"]["declared_event_rows"])
                self.assertEqual(len(rows), report["export_validation"]["observed_valid_event_rows"])
                self.assertIn("1 块计数不符", audit.human_summary(report))
                self.assertEqual(0, report["sequence"]["internal_missing_sequences"])

    def test_each_export_block_is_checked_instead_of_combined_totals(self):
        path = self.write("joined.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 2}, event(1),
            {"schema": "fkclaude.audit.export.v1", "retained": 1}, event(2), event(3),
        ])
        report = audit.analyze([path])
        exports = report["export_validation"]
        self.assertEqual(3, exports["declared_event_rows"])
        self.assertEqual(3, exports["observed_valid_event_rows"])
        self.assertEqual(2, exports["mismatched_blocks"])
        self.assertEqual(2, report["invalid"]["export_retained_mismatch"])
        self.assertFalse(report["complete_input"])

    def test_export_counts_precede_deduplication_and_stop_at_file_boundaries(self):
        manifest = {"schema": "fkclaude.audit.export.v1", "retained": 3}
        rows = [manifest, event(1), event(2), event(3)]
        first = self.write("first.ndjson", rows)
        second = self.write("second.ndjson", rows)
        rotation = self.write("audit.1.jsonl", [event(1), event(2)])
        report = audit.analyze([first, second, rotation])
        self.assertTrue(report["complete_input"])
        self.assertEqual(3, report["input"]["unique_valid_events"])
        self.assertEqual(5, report["input"]["duplicate_events"])
        self.assertEqual(2, report["export_validation"]["matched_blocks"])
        self.assertEqual(6, report["export_validation"]["declared_event_rows"])
        self.assertEqual(6, report["export_validation"]["observed_valid_event_rows"])
        joined = self.write("duplicate-blocks.ndjson", rows + rows)
        joined_report = audit.analyze([joined])
        self.assertTrue(joined_report["complete_input"])
        self.assertEqual(2, joined_report["export_validation"]["matched_blocks"])
        self.assertEqual(3, joined_report["input"]["duplicate_events"])

    def test_export_count_validation_precedes_view_filter(self):
        path = self.write("filtered.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 3},
            event(1), event(2, category="camera"), event(3, category="conversation"),
        ])
        report = audit.analyze([path], non_conversation=True)
        self.assertTrue(report["complete_input"])
        self.assertEqual(1, report["input"]["selected_events"])
        self.assertEqual(2, report["input"]["filtered_events"])
        self.assertEqual(1, report["export_validation"]["matched_blocks"])
        self.assertEqual(3, report["export_validation"]["observed_valid_event_rows"])

    def test_empty_exports_and_manifest_free_rotations_remain_valid(self):
        empty = self.write("empty.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 0}])
        report = audit.analyze([empty])
        self.assertTrue(report["complete_input"])
        self.assertEqual(1, report["export_validation"]["matched_blocks"])
        first = self.write("audit.jsonl", [event(2), event(3)])
        rotation = self.write("audit.1.jsonl", [event(1), event(2)])
        report = audit.analyze([first, rotation])
        self.assertTrue(report["complete_input"])
        self.assertEqual(3, report["input"]["unique_valid_events"])
        self.assertEqual(1, report["input"]["duplicate_events"])
        self.assertEqual(0, report["input"]["export_manifests"])
        self.assertEqual(0, report["export_validation"]["matched_blocks"])

    def test_complete_export_does_not_require_a_final_newline(self):
        path = self.write("no-newline.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 1}, event(1)])
        path.write_bytes(path.read_bytes().removesuffix(b"\n"))
        report = audit.analyze([path])
        self.assertTrue(report["complete_input"])
        self.assertEqual(1, report["export_validation"]["matched_blocks"])
        self.assertEqual(1, report["truncated"]["valid_json_without_final_newline"])

    def test_budget_limited_exports_are_not_marked_as_matching(self):
        path = self.write("limited.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 3},
            event(1), event(2), event(3),
        ])
        report = audit.analyze([path], max_events=1)
        self.assertFalse(report["complete_input"])
        self.assertTrue(report["truncated"]["limits_reached"]["events"])
        self.assertEqual(1, report["export_validation"]["incomplete_blocks"])
        self.assertEqual(0, report["export_validation"]["matched_blocks"])
        self.assertEqual(0, report["export_validation"]["mismatched_blocks"])

    def test_corrupt_gzip_is_reported_and_later_files_are_still_read(self):
        good = self.write("good.ndjson", [
            {"schema": "fkclaude.audit.export.v1", "retained": 2}, event(1), event(2)])
        compressed = bytearray(gzip.compress(good.read_bytes()))
        # Reserved DEFLATE block type: a damaged stream raises zlib.error.
        compressed[10] = (compressed[10] & ~6) | 6
        damaged = self.root / "PRIVATE-damaged.ndjson.gz"
        damaged.write_bytes(compressed)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = audit.main([str(damaged), str(good), "--json"])
        report = json.loads(output.getvalue())
        self.assertEqual(2, status)
        self.assertFalse(report["complete_input"])
        self.assertEqual(1, report["input"]["file_errors"])
        self.assertEqual(2, report["input"]["files_read"])
        self.assertEqual(2, report["input"]["unique_valid_events"])
        self.assertEqual(1, report["export_validation"]["matched_blocks"])
        self.assertNotIn("PRIVATE-", output.getvalue())

    def test_export_counters_are_maxima_not_added_duplicate_totals(self):
        manifest = {"schema": "fkclaude.audit.export.v1", "retained": 1,
                    "queue_dropped": 7, "disk_errors": 2}
        first = self.write("a.jsonl", [manifest, event()])
        second = self.write("b.jsonl", [manifest, event()])
        report = audit.analyze([first, second])
        self.assertEqual(2, report["input"]["export_manifests"])
        self.assertEqual(7, report["recorder_counter_maxima"]["queue_dropped"])
        self.assertEqual(2, report["recorder_counter_maxima"]["disk_errors"])

    def test_actual_observer_statuses_are_not_collapsed_or_counted_as_socket_calls(self):
        records = [
            event(1, category="system", action="native_observer_installed", outcome="partial",
                  details={"installed": "6", "requested": "7", "layer": "native"}),
            event(2, category="system", action="native_observer_unavailable", outcome="unsupported"),
            event(3, category="system", action="native_observer_control_failed", outcome="failed"),
            event(4, category="system", action="native_observer_error", outcome="failed"),
            event(5, category="system", action="environment_policy", outcome="observed"),
            event(6, category="telemetry", action="sift_queue", outcome="queue_result"),
            event(7, category="system", action="native_queue_dropped", outcome="dropped",
                  details={"queue_dropped": "4", "layer": "native"}),
            event(8, category="system", action="audit_panel_request", outcome="requested",
                  source="AuditPanel", details={"phase": "intent"}),
            event(9, category="system", action="audit_panel_open", outcome="visible",
                  source="AuditPanel", details={"phase": "dialog"}),
        ]
        report = audit.analyze([self.write("observer.jsonl", records)])
        self.assertEqual(0, report["unrecognized"]["action_events"])
        self.assertEqual(0, report["unrecognized"]["outcome_events"])
        self.assertEqual(1, report["outcomes"]["partial"])
        self.assertEqual(1, report["outcomes"]["dropped"])
        self.assertEqual(1, report["outcomes"]["queue_result"])
        self.assertEqual(1, report["outcomes"]["requested"])
        self.assertEqual(1, report["outcomes"]["visible"])
        self.assertEqual({}, report["native_io"]["observed_calls_by_action"])
        self.assertEqual(0, sum(report["native_io"]["observed_socket_return_bytes"].values()))
        self.assertIsNone(report["correlation"]["independent_upload_count"])

    def test_native_drop_deltas_are_deduplicated_before_network_filter(self):
        first = event(1, category="system", action="native_queue_dropped", outcome="dropped",
                      details={"queue_dropped": "5", "layer": "native"})
        second = event(2, category="system", action="native_queue_dropped", outcome="dropped",
                       details={"queue_dropped": "7", "layer": "native"})
        native = event(3, action="native_sendto", outcome="returned", source="libc.sendto",
                       details={"bytes": "100", "layer": "native", "caller": "application"})
        manifest = {"schema": "fkclaude.audit.export.v1", "queue_dropped": 90}
        a = self.write("a.jsonl", [manifest, first, second, native])
        b = self.write("b.jsonl", [first])
        report = audit.analyze([a, b], non_conversation=True)
        self.assertEqual(1, report["input"]["selected_events"])
        self.assertEqual(1, report["input"]["duplicate_events"])
        self.assertEqual(2, report["native_io"]["queue_drop_reports"])
        self.assertEqual(12, report["native_io"]["queue_dropped_records_reported"])
        self.assertEqual(90, report["recorder_counter_maxima"]["queue_dropped"])
        self.assertEqual(100, report["native_io"]["observed_socket_return_bytes"]["application"])
        self.assertEqual(0, report["native_io"]["invalid_queue_drop_reports"])
        self.assertIsNone(report["correlation"]["independent_upload_count"])
        self.assertTrue(report["complete_input"])

        malformed = self.write("invalid.jsonl", [
            event(4, category="system", action="native_queue_dropped", outcome="dropped",
                  details={"queue_dropped": "private-invalid-counter"})])
        report = audit.analyze([a, b, malformed], non_conversation=True)
        self.assertEqual(12, report["native_io"]["queue_dropped_records_reported"])
        self.assertEqual(1, report["native_io"]["invalid_queue_drop_reports"])
        self.assertEqual(1, report["invalid"]["native_drop_count"])
        self.assertFalse(report["complete_input"])
        self.assertNotIn("private-invalid-counter", json.dumps(report))

    def test_invalid_type_or_missing_path_is_reported_without_path_contents(self):
        path = self.write("bad.jsonl", [event(details={"nested": {"secret": "value"}})])
        missing = self.root / "PRIVATE-MISSING.jsonl"
        report = audit.analyze([path, missing])
        self.assertEqual(1, report["invalid"]["type"])
        self.assertEqual(1, report["input"]["file_errors"])
        self.assertNotIn("PRIVATE-MISSING", json.dumps(report))
        self.assertFalse(report["complete_input"])


if __name__ == "__main__":
    unittest.main()
