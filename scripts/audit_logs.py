#!/usr/bin/env python3
"""Summarize local fkclaude JSONL without exposing payloads, identifiers or filenames."""
from __future__ import annotations

import argparse
from collections import Counter
import fnmatch
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

MAX_BYTES = 64 * 1024 * 1024
MAX_EVENTS = 100_000
MAX_LINE = 64 * 1024
MAX_FILES = 128
MAX_SESSIONS = 256
MAX_ENDPOINTS = 128
MAX_GROUPS = 100_000
CATEGORIES = frozenset(("system", "network", "conversation", "telemetry", "device", "identifier",
                       "location", "clipboard", "contacts", "calendar", "packages", "sensor",
                       "webview", "integrity", "unknown"))
NETWORK = frozenset(("network", "telemetry", "integrity", "webview", "unknown"))
OUTCOMES = frozenset(("attempted", "blocked", "response_received", "returned", "failed", "called",
                     "constructed", "observed", "installed", "unavailable", "unsupported",
                     "enabled", "disabled", "completed", "scheduled", "sent"))
ACTIONS = frozenset((
    "audit_started", "recording_changed", "audit_cleared", "target_adapter", "coverage",
    "activity_resumed", "hook_coverage", "observer_error", "api_access", "identifier_read",
    "system_property_read", "provider_query", "environment_file_probe", "environment_command",
    "dns_lookup", "socket_connect", "datagram_send", "connection_created", "http_request",
    "http_connect", "http_getOutputStream", "http_getInputStream", "http_getResponseCode",
    "navigation", "sift_collect", "sift_queue", "sift_dispatch", "sift_device_properties",
    "sift_app_state", "analytics_context", "product_analytics_event", "analytics_batch",
    "native_connect", "native_send", "native_sendto", "native_sendmsg", "native_sendmmsg",
    "native_write", "native_writev", "native_queue_dropped", "native_coverage",
    "native_poll_error", "web_fetch", "web_xhr", "web_beacon", "web_websocket",
    "fetch", "xhr", "beacon", "websocket", "sendBeacon", "webview_bridge"))
NATIVE_ACTIONS = frozenset(action for action in ACTIONS if action.startswith("native_")
                           and action not in ("native_queue_dropped", "native_coverage", "native_poll_error"))
BYTE_ACTIONS = NATIVE_ACTIONS - {"native_connect"}
METHODS = frozenset(("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE", "WS"))
PATH_WORDS = frozenset((
    "api", "v1", "v2", "v3", "v4", "accounts", "account", "organizations", "organization",
    "users", "user", "me", "mobile_events", "events", "event", "event_logging", "batch",
    "analytics", "telemetry", "track", "track_open", "experiences", "notification", "notifications",
    "push", "register", "unregister", "installations", "firebaseinstallations", "projects",
    "auth", "session", "sessions", "session_reattest", "device_key", "attestation", "attest",
    "login", "logout", "signin", "sign_in", "sign_up", "signup", "trusted_devices", "devices",
    "chat_conversations", "conversations", "conversation", "completion", "completions", "messages",
    "message", "append_message", "retry", "retry_completion", "stop", "title", "sync", "settings",
    "profile", "preferences", "bootstrap", "config", "configuration", "feature_flags", "attachments",
    "files", "file", "upload", "uploads", "download", "search", "list", "envelope", "store",
    "sentry", "rum", "logs", "log", "log_event", "metrics", "traces", "spans", "intake", "collect",
    "g", "mp", "batch_log", "crash", "reports", "report", "code", "status", "health", "ping", "token",
    "tokens", "refresh", "credits", "billing", "subscriptions", "models", "model", "voice",
    "realtime", "feedback", "flags", "initialize", "check", "identify", "device", "key", "keys",
    "challenge", "verify"))
DYNAMIC_PARENTS = frozenset(("accounts", "organizations", "users", "projects", "chat_conversations",
                             "conversations", "sessions", "attachments", "files", "devices", "trusted_devices"))


def _integer(value, minimum=0):
    return isinstance(value, int) and not isinstance(value, bool) and minimum <= value <= 2**63 - 1


def _number(value):
    if _integer(value):
        return value
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,18}", value):
        return int(value)
    return None


def _valid_event(event):
    if event.get("schema") != "fkclaude.audit.v1":
        return "schema"
    for key in ("session_id", "time", "category", "action", "source", "outcome", "evidence"):
        if not isinstance(event.get(key), str) or not 0 < len(event[key]) <= 240:
            return "type"
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", event["session_id"]) or not _integer(event.get("seq"), 1):
        return "type"
    for key in ("pid", "thread_id", "elapsed_ms"):
        if key in event and not _integer(event[key]):
            return "type"
    if len(event) > 32 or any(isinstance(value, (dict, list)) for key, value in event.items() if key != "details"):
        return "type"
    details = event.get("details")
    if not isinstance(details, dict) or len(details) > 64:
        return "type"
    for key, value in details.items():
        if not isinstance(key, str) or len(key) > 64:
            return "type"
        if not ((isinstance(value, str) and len(value) <= 4096) or _integer(value)):
            return "type"
    return None


def _host(value):
    if value == "[ip]":
        return value
    if not isinstance(value, str) or len(value) > 253:
        return "[unknown]"
    value = value.lower().rstrip(".")
    if re.fullmatch(r"[0-9.]+", value) or ":" in value:
        return "[ip]"
    if not re.fullmatch(r"[a-z0-9.-]+", value):
        return "[unknown]"
    labels = []
    for label in value.split("."):
        if not label:
            return "[unknown]"
        labels.append(":id" if len(label) > 31 or label.isdigit()
                      or re.search(r"[0-9a-f]{16,}", label)
                      or re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", label)
                      else label)
    return ".".join(labels)


def _route(value):
    if not isinstance(value, str):
        return "/:unknown"
    value = re.split(r"[?#]", value[:4096], maxsplit=1)[0]
    output, previous = [], ""
    for part in (part for part in value.split("/") if part):
        if len(output) == 18:
            output.append(":more")
            break
        lower = part.lower()
        if previous in DYNAMIC_PARENTS:
            output.append(":id")
            previous = ""
        else:
            output.append(lower if lower in PATH_WORDS or lower in (":id", ":segment", ":more", ":oversize")
                          else ":segment")
            previous = lower
    return "/" + "/".join(output)


def _endpoint(details):
    host, path = details.get("host"), details.get("path")
    if host is None and isinstance(details.get("url"), str):
        try:
            url = urlsplit(details["url"])
            if url.scheme in ("http", "https", "ws", "wss"):
                host, path = url.hostname, url.path
        except ValueError:
            pass
    if host is None and path is None:
        return None
    method = details.get("method")
    method = method if method in METHODS else "UNKNOWN"
    return "{} {} {}".format(method, _host(host), _route(path))


def _files(paths, counters, limits):
    found, seen, examined_dirs, examined_names = [], set(), 0, 0
    def scan_error(_error):
        counters["file_errors"] += 1
    for given in paths:
        root = Path(given)
        if root.is_dir():
            candidates = []
            for current, directories, names in os.walk(root, followlinks=False, onerror=scan_error):
                examined_dirs += 1
                if examined_dirs > 4096:
                    limits["directory_scan"] = True
                    break
                directories.sort()
                for name in sorted(names):
                    examined_names += 1
                    if examined_names > 16_384:
                        limits["directory_scan"] = True
                        break
                    if fnmatch.fnmatchcase(name, "*.jsonl*"):
                        candidates.append(Path(current) / name)
                    if len(candidates) >= MAX_FILES + 1:
                        limits["files"] = True
                        break
                if limits["directory_scan"] or len(candidates) > MAX_FILES:
                    break
        elif root.is_file():
            candidates = [root]
        else:
            counters["file_errors"] += 1
            continue
        for candidate in candidates:
            if not fnmatch.fnmatchcase(candidate.name, "*.jsonl*"):
                counters["non_jsonl_files_ignored"] += 1
                continue
            if candidate.is_symlink():
                counters["symlinks_ignored"] += 1
                continue
            try:
                stat = candidate.stat()
            except OSError:
                counters["file_errors"] += 1
                continue
            identity = (stat.st_dev, stat.st_ino)
            if identity in seen:
                counters["duplicate_files_ignored"] += 1
                continue
            if len(found) == MAX_FILES:
                limits["files"] = True
                break
            seen.add(identity)
            found.append(candidate)
        if limits["directory_scan"]:
            break
    return found


def analyze(paths, *, non_conversation=False, max_bytes=MAX_BYTES, max_events=MAX_EVENTS):
    """Return bounded summary data. Raw filenames, sessions and request IDs are not returned."""
    max_bytes = max(1, min(MAX_BYTES, int(max_bytes)))
    max_events = max(1, min(MAX_EVENTS, int(max_events)))
    counts, invalid = Counter(), Counter()
    limits = {name: False for name in ("bytes", "events", "files", "directory_scan", "sessions", "endpoints", "groups")}
    files = _files(paths, counts, limits)
    seen, sessions, groups = {}, {}, {}
    categories, actions, outcomes = Counter(), Counter(), Counter()
    phase_events, native_actions = Counter(), Counter()
    native_bytes = Counter(application=0, instrumentation=0, unknown=0)
    native_byte_events = Counter(application=0, instrumentation=0, unknown=0)
    endpoints = {}
    declared = Counter()
    stop = False

    def accept(raw, terminated):
        nonlocal stop
        counts["lines_read"] += 1
        if not raw.strip():
            counts["blank_lines"] += 1
            return
        try:
            event = json.loads(raw.decode("utf-8"))
        except UnicodeDecodeError:
            invalid["encoding"] += 1
            if not terminated:
                counts["incomplete_tail_lines"] += 1
            return
        except (json.JSONDecodeError, RecursionError):
            invalid["json"] += 1
            if not terminated:
                counts["incomplete_tail_lines"] += 1
            return
        if not isinstance(event, dict):
            invalid["type"] += 1
            return
        if not terminated:
            counts["valid_json_without_final_newline"] += 1
        if event.get("schema") == "fkclaude.audit.export.v1":
            if any(key in event and not _integer(event[key]) for key in
                   ("retained", "max_events", "queue_dropped", "disk_errors", "memory_evicted", "invalid_history")):
                invalid["type"] += 1
                return
            counts["export_manifests"] += 1
            for key in ("queue_dropped", "disk_errors", "memory_evicted", "invalid_history"):
                declared[key] = max(declared[key], event.get(key, 0))
            return
        error = _valid_event(event)
        if error:
            invalid[error] += 1
            return
        key = (event["session_id"], event["seq"])
        digest = hashlib.sha256(json.dumps(event, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).digest()
        if key in seen:
            counts["duplicate_events"] += 1
            if seen[key] != digest:
                invalid["duplicate_conflict"] += 1
            return
        if len(seen) >= max_events:
            limits["events"] = stop = True
            return
        seen[key] = digest
        sid = event["session_id"]
        if sid not in sessions and len(sessions) < MAX_SESSIONS:
            sessions[sid] = set()
        if sid in sessions:
            sessions[sid].add(event["seq"])
        else:
            limits["sessions"] = True
        category = event["category"] if event["category"] in CATEGORIES else "unknown"
        if category == "unknown" and event["category"] != "unknown":
            counts["unrecognized_category_events"] += 1
        if non_conversation and category not in NETWORK:
            counts["filtered_events"] += 1
            return
        counts["selected_events"] += 1
        action = event["action"] if event["action"] in ACTIONS else "other"
        outcome = event["outcome"] if event["outcome"] in OUTCOMES else "other"
        if action == "other":
            counts["unrecognized_action_events"] += 1
        if outcome == "other":
            counts["unrecognized_outcome_events"] += 1
        categories[category] += 1
        actions[action] += 1
        outcomes[outcome] += 1
        details = event["details"]
        if category in NETWORK:
            phase_events[outcome] += 1
        native = event["action"] in NATIVE_ACTIONS and (details.get("layer") == "native" or event["source"].startswith("libc."))
        if native:
            native_actions[action] += 1
            attribution = "instrumentation" if (details.get("classification") == "instrumentation"
                          or details.get("caller") == "fkclaude_ip_guard_probe") else (
                          "application" if details.get("caller") == "application" else "unknown")
            if action in BYTE_ACTIONS and outcome == "returned":
                amount = _number(details.get("bytes"))
                if amount is None:
                    counts["native_returns_without_valid_bytes"] += 1
                else:
                    native_bytes[attribution] += amount
                    native_byte_events[attribution] += 1
        correlation = details.get("request_id")
        group_key = None
        if isinstance(correlation, str) and re.fullmatch(r"(?:r|w|local-)?[0-9]{1,18}", correlation):
            # Web requests restart at w1 in each document. Include its local document/view identity.
            group_key = (sid, str(details.get("document_id", "")), str(details.get("view_id", "")), correlation)
            if group_key in groups or len(groups) < MAX_GROUPS:
                group = groups.setdefault(group_key, {"outcomes": set(), "native": native})
                group["outcomes"].add(outcome)
            else:
                limits["groups"] = True
                group_key = None
        elif category in NETWORK:
            counts["network_events_without_local_correlation"] += 1
        endpoint = _endpoint(details) if category in NETWORK else None
        if endpoint is not None:
            if endpoint not in endpoints and len(endpoints) >= MAX_ENDPOINTS:
                limits["endpoints"] = True
                counts["endpoint_events_omitted"] += 1
            else:
                row = endpoints.setdefault(endpoint, {"observation_events": 0, "outcomes": Counter(), "groups": set()})
                row["observation_events"] += 1
                row["outcomes"][outcome] += 1
                if group_key is not None:
                    row["groups"].add(group_key)

    for path in files:
        if stop:
            break
        try:
            opener = gzip.open if path.name.endswith(".gz") else open
            with opener(path, "rb") as stream:
                counts["files_read"] += 1
                while not stop:
                    remaining = max_bytes - counts["bytes_read"]
                    raw = stream.readline(min(MAX_LINE + 1, max(1, remaining + 1)))
                    if not raw:
                        break
                    counts["bytes_read"] += len(raw)
                    if len(raw) > remaining:
                        limits["bytes"] = stop = True
                        break
                    if len(raw) > MAX_LINE:
                        invalid["oversized_line"] += 1
                        counts["lines_read"] += 1
                        while raw and not raw.endswith(b"\n"):
                            remaining = max_bytes - counts["bytes_read"]
                            raw = stream.readline(min(MAX_LINE + 1, max(1, remaining + 1)))
                            counts["bytes_read"] += len(raw)
                            if len(raw) > remaining:
                                limits["bytes"] = stop = True
                                break
                        if not raw:
                            counts["incomplete_tail_lines"] += 1
                        continue
                    accept(raw, raw.endswith(b"\n"))
        except (OSError, EOFError, gzip.BadGzipFile):
            counts["file_errors"] += 1

    sequence_rows, missing_count = [], 0
    for index, numbers in enumerate(sessions.values(), 1):
        ordered = sorted(numbers)
        ranges, missing = [], 0
        for left, right in zip(ordered, ordered[1:]):
            if right > left + 1:
                missing += right - left - 1
                if len(ranges) < 8:
                    ranges.append([left + 1, right - 1])
        missing_count += missing
        if len(sequence_rows) < 32:
            sequence_rows.append({"session": "session_{:03d}".format(index), "observed": len(ordered),
                                  "first_seq": ordered[0], "last_seq": ordered[-1],
                                  "starts_after_one": ordered[0] > 1,
                                  "internal_missing_sequences": missing, "first_gap_ranges": ranges})
    endpoint_rows = [
        {"endpoint": endpoint, "observation_events": row["observation_events"],
         "correlated_operations": len(row["groups"]), "outcomes": dict(sorted(row["outcomes"].items()))}
        for endpoint, row in sorted(endpoints.items(), key=lambda pair: (-pair[1]["observation_events"], pair[0]))
    ]
    group_stages = {stage: sum(stage in group["outcomes"] for group in groups.values())
                    for stage in ("attempted", "blocked", "response_received", "returned", "failed")}
    return {
        "schema": "fkclaude.audit.summary.v1",
        "view": "non_conversation_network_including_unknown" if non_conversation else "all_events",
        "complete_input": not any(limits.values()) and not invalid and not counts["file_errors"],
        "input": {"files_read": counts["files_read"], "bytes_read": counts["bytes_read"],
                  "lines_read": counts["lines_read"], "unique_valid_events": len(seen),
                  "selected_events": counts["selected_events"], "duplicate_events": counts["duplicate_events"],
                  "export_manifests": counts["export_manifests"], "filtered_events": counts["filtered_events"],
                  "file_errors": counts["file_errors"], "non_jsonl_files_ignored": counts["non_jsonl_files_ignored"],
                  "duplicate_files_ignored": counts["duplicate_files_ignored"], "symlinks_ignored": counts["symlinks_ignored"]},
        "invalid": dict(sorted(invalid.items())),
        "truncated": {"incomplete_tail_lines": counts["incomplete_tail_lines"],
                      "valid_json_without_final_newline": counts["valid_json_without_final_newline"],
                      "limits_reached": limits},
        "categories": dict(sorted(categories.items())),
        "actions": dict(sorted(actions.items())),
        "outcomes": dict(sorted(outcomes.items())),
        "network_phase_events": dict(sorted(phase_events.items())),
        "correlation": {"operation_groups": len(groups), "groups_with_outcome": group_stages,
                        "network_events_without_local_correlation": counts["network_events_without_local_correlation"],
                        "group_key": "session_id+document_id+view_id+request_id",
                        "scope": "selected_events_across_observed_layers",
                        "independent_upload_count": None},
        "native_io": {"observed_calls_by_action": dict(sorted(native_actions.items())),
                      "observed_socket_return_bytes": dict(native_bytes),
                      "return_events_with_bytes": dict(native_byte_events),
                      "returns_without_valid_bytes": counts["native_returns_without_valid_bytes"],
                      "meaning": "successful_socket_return_values_not_unique_traffic_or_server_receipts"},
        "sequence": {"scope": "all_valid_unique_events_before_view_filter",
                     "sessions_tracked": len(sessions), "internal_missing_sequences": missing_count,
                     "sessions": sequence_rows, "session_rows_omitted": max(0, len(sessions) - len(sequence_rows))},
        "recorder_counter_maxima": dict(declared),
        "endpoints": endpoint_rows,
        "endpoint_events_omitted": counts["endpoint_events_omitted"],
        "unrecognized": {"category_events": counts["unrecognized_category_events"],
                         "action_events": counts["unrecognized_action_events"],
                         "outcome_events": counts["unrecognized_outcome_events"]},
        "limitations": [
            "Phase counts are observations. Multiple hooks and layers can describe one operation.",
            "A response or positive socket return does not establish server storage or independent uploads.",
            "Sequence gaps can reflect rotation, clearing, queue saturation, interrupted writes or missing files.",
            "The observed process can alter its own logs; these files are not a trusted remote audit authority.",
            "Only metadata is summarized. Payload values, file paths, session IDs and request IDs are omitted.",
            "Recorder counters are maxima across manifests, not sums across potentially duplicate exports.",
        ],
    }


def human_summary(report):
    data = report["input"]
    stages = report["network_phase_events"]
    lines = [
        "fkclaude \u672c\u5730\u5ba1\u8ba1\u6c47\u603b",
        "\u8f93\u5165\uff1a{} \u4e2a\u6587\u4ef6\uff0c{} \u6761\u552f\u4e00\u6709\u6548\u4e8b\u4ef6\uff0c{} \u6761\u9009\u4e2d\uff0c{} \u6761\u91cd\u590d\u3002".format(
            data["files_read"], data["unique_valid_events"], data["selected_events"], data["duplicate_events"]),
        "\u68c0\u67e5\uff1a{}\uff1b\u65e0\u6548\u8bb0\u5f55 {}\uff1b\u4e0d\u5b8c\u6574\u5c3e\u884c {}\uff1b\u5e8f\u53f7\u5185\u90e8\u7f3a\u53e3 {}\u3002".format(
            "\u5df2\u8bfb\u5b8c\u6240\u9009\u8f93\u5165" if report["complete_input"] else "\u5b58\u5728\u9519\u8bef\u6216\u8303\u56f4\u622a\u65ad",
            sum(report["invalid"].values()), report["truncated"]["incomplete_tail_lines"],
            report["sequence"]["internal_missing_sequences"]),
        "\u7f51\u7edc\u9636\u6bb5\u4e8b\u4ef6\uff1aattempted={} / blocked={} / response_received={} / failed={}\u3002".format(
            *(stages.get(stage, 0) for stage in ("attempted", "blocked", "response_received", "failed"))),
        "\u672c\u5730\u5173\u8054\u64cd\u4f5c\u7ec4\uff1a{}\uff1b\u72ec\u7acb\u4e0a\u4f20\u6b21\u6570\uff1a\u672a\u77e5\u3002".format(report["correlation"]["operation_groups"]),
        "\u539f\u751f socket \u8fd4\u56de\u5b57\u8282\u89c2\u5bdf\u503c\uff1aapplication={} / instrumentation={} / unknown={}\u3002".format(
            *(report["native_io"]["observed_socket_return_bytes"][kind]
              for kind in ("application", "instrumentation", "unknown"))),
        "\u7c7b\u522b\uff1a" + "\uff0c".join("{}={}".format(key, value) for key, value in report["categories"].items()),
    ]
    if report["endpoints"]:
        lines.append("\u76ee\u6807\u6a21\u677f\uff08\u4e8b\u4ef6\u6570\u5305\u542b\u9636\u6bb5\u4e0e\u5c42\u7ea7\u91cd\u590d\uff09\uff1a")
        for row in report["endpoints"][:12]:
            lines.append("  {}  {}  (\u5173\u8054\u7ec4 {})".format(row["observation_events"], row["endpoint"][:180],
                                                        row["correlated_operations"]))
    lines.extend((
        "attempted \u8868\u793a\u89c2\u5bdf\u5230\u53d1\u8d77\uff1bresponse_received \u8868\u793a\u5ba2\u6237\u7aef\u6536\u5230\u54cd\u5e94\uff1bsocket \u5b57\u8282\u662f\u8c03\u7528\u8fd4\u56de\u503c\u3002",
        "\u9636\u6bb5\u8ba1\u6570\u3001\u5173\u8054\u7ec4\u548c socket \u5b57\u8282\u5206\u522b\u7edf\u8ba1\uff0c\u672a\u63a8\u5b9a\u670d\u52a1\u7aef\u5b58\u50a8\uff0c\u4e5f\u672a\u5c06\u884c\u6570\u5f53\u4f5c\u4e0a\u4f20\u6b21\u6570\u3002",
        "\u5e8f\u53f7\u7f3a\u53e3\u53ef\u80fd\u6765\u81ea\u8f6e\u8f6c\u3001\u6e05\u7a7a\u3001\u961f\u5217\u9971\u548c\u3001\u5f02\u5e38\u4e2d\u65ad\u6216\u7f3a\u5c11\u6587\u4ef6\uff1b\u540c\u8fdb\u7a0b\u4ee3\u7801\u53ef\u6539\u5199\u81ea\u5df1\u7684\u65e5\u5fd7\u3002",
        "\u4f7f\u7528 --json \u83b7\u53d6\u673a\u5668\u53ef\u8bfb\u6c47\u603b\uff1b--non-conversation \u5305\u542b\u672a\u77e5\u7f51\u7edc\uff0c\u6392\u9664\u5bf9\u8bdd\u53ca\u672c\u5730\u8bbe\u5907\u8bfb\u53d6\u3002",
    ))
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="JSONL files or directories; only *.jsonl* files are read")
    parser.add_argument("--json", action="store_true", help="emit fkclaude.audit.summary.v1 JSON")
    parser.add_argument("--non-conversation", action="store_true",
                        help="include network/telemetry/integrity/webview/unknown events")
    args = parser.parse_args(argv)
    report = analyze(args.paths, non_conversation=args.non_conversation)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(human_summary(report))
    return 0 if report["complete_input"] else 2


if __name__ == "__main__":
    sys.exit(main())
