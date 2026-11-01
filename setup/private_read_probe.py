#!/usr/bin/env python3
"""Read-only capability checks for four WHOOP private endpoints; no raw data output."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
import math
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener
from uuid import UUID
from zoneinfo import ZoneInfo

import private_api_poc as cli
import whoop_private_api as api

PATHS = {
    "workout": "/core-details-bff/v1/cardio-details",
    "sleep_need": "/coaching-service/v2/sleepneed",
    "strain": "/home-service/v1/deep-dive/strain",
    "sleep": "/home-service/v1/deep-dive/sleep/last-night",
}
ZONES = {"RESTORATIVE", "VERY_LIGHT", "LIGHT", "MODERATE", "HARD", "MAX"}
STAGES = {"AWAKE", "LIGHT_SLEEP", "REM_SLEEP", "SWS_SLEEP"}
MAX_BYTES = 4 * 1024 * 1024


def objects(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from objects(value)
    elif isinstance(node, list):
        for value in node:
            yield from objects(value)


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        result = float(value)
    except ValueError:
        return None
    return result if math.isfinite(result) else None


def at(node, *keys):
    for key in keys:
        if not isinstance(node, dict):
            return None
        node = node.get(key)
    return node


def sequence(value):
    return value if isinstance(value, list) else []


def fraction(value):
    parsed = number(value)
    return parsed is not None and 0 <= parsed <= 1


def instant(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo is not None else None
    except ValueError:
        return None


def summarize_probe(name: str, payload: dict) -> dict:
    root = payload if isinstance(payload, dict) else {}
    if name == "workout":
        tiles = sequence(at(root, "key_metric_carousel", "key_metric_tile"))
        def metric(icon):
            return any(isinstance(tile, dict) and tile.get("key_metric_tile_icon") == icon
                       and number(tile.get("key_metric_tile_stat_value_display")) is not None for tile in tiles)
        points = [point for point in objects(root.get("graph_response"))
                  if at(point, "data_scrubber_details", "unit_display") == "bpm"
                  and number(at(point, "data_scrubber_details", "value_display")) is not None
                  and fraction(point.get("position_x"))]
        zones = [zone for zone in sequence(at(root, "bar_graph_container", "heart_rate_zones"))
                 if isinstance(zone, dict) and zone.get("id") in ZONES
                 and isinstance(zone.get("bar_graph_tile_time_display"), str)]
        return {
            "has_strain": number(at(root, "horizontal_stat", "stat_main_value_display")) is not None,
            "has_calories": metric("CALORIES"), "has_avg_hr": metric("HEART_RATE"),
            "has_max_hr": metric("MAX_HEART_RATE"), "hr_point_count": len(points), "zone_count": len(zones),
        }
    if name == "sleep_need":
        breakdown = root.get("need_breakdown")
        recommendations = root.get("recommended_time_in_bed_formatted")
        return {
            "breakdown_available": all(number(at(breakdown, key)) is not None for key in ("baseline", "debt", "strain", "naps")),
            "recommendation_tier_count": sum(number(at(recommendations, tier, "recommended_time_in_bed")) is not None
                                              for tier in ("70", "85", "100")),
        }
    if name == "strain":
        gauge = next((node["content"] for node in objects(root)
                      if node.get("type") == "SCORE_GAUGE"
                      and at(node, "content", "id") == "STRAIN_SCORE_GAUGE"), {})
        return {
            "score_available": number(gauge.get("score_display")) is not None,
            "target_available": fraction(gauge.get("score_target")),
            "optimal_range_available": fraction(gauge.get("lower_optimal_percentage")) and fraction(gauge.get("higher_optimal_percentage")),
        }
    if name == "sleep":
        params = at(root, "header_section", "destination", "parameters")
        start, end = instant(at(params, "start_time")), instant(at(params, "end_time"))
        bars = [bar for node in objects(root) if node.get("type") == "BAR_GRAPH_CARD"
                and at(node, "content", "duration_display")
                for bar in sequence(at(node, "content", "heart_rate_zones"))
                if isinstance(bar, dict) and bar.get("id") in STAGES]
        points = [node for node in objects(root)
                  if at(node, "data_scrubber_details", "scrubber_style") in STAGES and fraction(node.get("position_x"))]
        def card_available(prefix):
            return any(isinstance(node.get("card_title"), str)
                       and node["card_title"].upper().startswith(prefix)
                       and any(isinstance(stat, dict) and isinstance(stat.get("current_stat_text"), str)
                               and stat["current_stat_text"].strip()
                               for stat in sequence(node.get("arrow_stat")))
                       for node in objects(root))
        wake_events = [node.get("content") for node in objects(root)
                       if node.get("type") == "DETAILS_METRIC_TILES"
                       and at(node, "content", "title") == "WAKE EVENTS"]
        return {
            "window_available": start is not None and end is not None and end > start,
            "stage_bar_count": len(bars), "stage_point_count": len(points),
            "sleep_duration_card_available": card_available("HOURS OF SLEEP"),
            "efficiency_card_available": card_available("SLEEP EFFICIENCY"),
            "consistency_card_available": card_available("SLEEP CONSISTENCY"),
            "wake_events_tile_available": bool(wake_events),
        }
    raise ValueError("Unsupported probe name")


def fetch_probe(name, query, tokens):
    # Only four fixed GET routes are allowed; no arbitrary URL or HTTP method input.
    url = "https://api.prod.whoop.com" + PATHS[name] + "?" + urlencode({"apiVersion": "7", **query})
    request = Request(url, headers=api.PrivateWhoopClient._headers(tokens), method="GET")
    try:
        with build_opener(api._NoRedirect()).open(request, timeout=30) as response:
            raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                return {"http_status": response.status, "error": "oversized_response"}
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                return {"http_status": response.status, "error": "unexpected_shape"}
            return {"http_status": response.status, "features": summarize_probe(name, payload)}
    except HTTPError as error:
        status = error.code
        error.close()
        return {"http_status": status, "error": "request_rejected"}
    except (URLError, OSError):
        return {"error": "network_failure"}
    except (ValueError, TypeError, RecursionError):
        return {"error": "invalid_response"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workout-draft", required=True, help="Local draft ID of the submitted workout")
    parser.add_argument("--date", default=datetime.now(ZoneInfo("Europe/London")).date().isoformat())
    parser.add_argument("--only", choices=PATHS, help="Check just one of the four read endpoints")
    args = parser.parse_args()
    try:
        date = datetime.strptime(args.date, "%Y-%m-%d").date().isoformat()
        with cli.exclusive_session(cli.PRIVATE_DIR):
            draft = api.read_private_json(cli.draft_path(cli.PRIVATE_DIR, args.workout_draft))
            if draft.get("status") != "submitted":
                raise api.PrivateApiError("Choose a successfully submitted workout draft.")
            activity_id = str(UUID(draft["activity_id"]))
            tokens = cli.ready_session(api.PrivateWhoopClient(), cli.PRIVATE_DIR)
            results = {}
            for name in ([args.only] if args.only else PATHS):
                print(f"Checking {name}...", flush=True)
                query = {"activityId": activity_id} if name == "workout" else ({"date": date} if name in {"strain", "sleep"} else {})
                results[name] = fetch_probe(name, query, tokens)
                print(json.dumps({"probe": name, **results[name]}), flush=True)
            api.write_private_json(cli.PRIVATE_DIR / "read-probes.json", {
                "checked_at": cli.timestamp(), "results": results,
            })
            return 0 if all(item.get("http_status") == 200 and "error" not in item
                            for item in results.values()) else 1
    except api.PrivateApiError as error:
        print(str(error))
        return 1
    except Exception:
        print("Read-only probe failed. No account data was changed.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
