import json
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.error import HTTPError
import unittest

import private_read_probe as probe


class SummarizeProbeTest(unittest.TestCase):
    def test_workout_full_payload_reports_only_feature_presence_and_counts(self):
        payload = {
            "horizontal_stat": {"stat_main_value_display": "13.4"},
            "key_metric_carousel": {"key_metric_tile": [
                {"key_metric_tile_icon": "CALORIES", "key_metric_tile_stat_value_display": "456"},
                {"key_metric_tile_icon": "HEART_RATE", "key_metric_tile_stat_value_display": "142"},
                {"key_metric_tile_icon": "MAX_HEART_RATE", "key_metric_tile_stat_value_display": "178"},
            ]},
            "graph_response": {"plots": [{"plot": {"segments": [{"points": [
                {"position_x": 0.1, "data_scrubber_details": {"unit_display": "bpm", "value_display": "120"}},
                {"position_x": 0.9, "data_scrubber_details": {"unit_display": "bpm", "value_display": "165"}},
            ]}]}}]},
            "bar_graph_container": {"heart_rate_zones": [
                {"id": "RESTORATIVE", "bar_graph_tile_time_display": "00:01"},
                {"id": "HARD", "bar_graph_tile_time_display": "00:02"},
                {"id": "UNKNOWN_PRIVATE_ZONE", "bar_graph_tile_time_display": "00:03"},
            ]},
            "account_id": "synthetic-account-identifier-001",
            "health_note": "synthetic-health-string-do-not-return",
        }

        result = probe.summarize_probe("workout", payload)

        self.assertEqual({
            "has_strain": True, "has_calories": True, "has_avg_hr": True,
            "has_max_hr": True, "hr_point_count": 2, "zone_count": 2,
        }, result)
        self.assert_redacted(result, payload)

    def test_sleep_need_full_payload_counts_numeric_breakdown_and_recommendations(self):
        payload = {
            "need_breakdown": {"baseline": 7.5, "debt": "1.25", "strain": 0, "naps": "0.5"},
            "recommended_time_in_bed_formatted": {
                "70": {"recommended_time_in_bed": "8.0"},
                "85": {"recommended_time_in_bed": 8.5},
                "100": {"recommended_time_in_bed": "not-a-number"},
            },
            "account_id": "synthetic-account-identifier-002",
            "health_note": "synthetic-sleep-need-string-do-not-return",
        }

        result = probe.summarize_probe("sleep_need", payload)

        self.assertEqual({"breakdown_available": True, "recommendation_tier_count": 2}, result)
        self.assert_redacted(result, payload)

    def test_strain_full_payload_finds_nested_gauge_and_valid_fractions(self):
        payload = {"sections": [{"items": [{
            "type": "SCORE_GAUGE",
            "content": {
                "id": "STRAIN_SCORE_GAUGE", "score_display": "12.7",
                "score_target": "0.8", "lower_optimal_percentage": 0.25,
                "higher_optimal_percentage": "0.75",
            },
        }]}], "account_id": "synthetic-account-identifier-003",
            "health_note": "synthetic-strain-string-do-not-return"}

        result = probe.summarize_probe("strain", payload)

        self.assertEqual({
            "score_available": True, "target_available": True,
            "optimal_range_available": True,
        }, result)
        self.assert_redacted(result, payload)

    def test_sleep_full_payload_reports_window_stage_bars_and_points(self):
        payload = {
            "header_section": {"destination": {"parameters": {
                "start_time": "2026-10-31T22:30:00Z", "end_time": "2026-11-01T06:45:00+00:00",
            }}},
            "cards": [{"type": "BAR_GRAPH_CARD", "content": {
                "duration_display": "synthetic-duration-do-not-return",
                "heart_rate_zones": [
                    {"id": "AWAKE"}, {"id": "LIGHT_SLEEP"},
                    {"id": "REM_SLEEP"}, {"id": "SWS_SLEEP"}, {"id": "UNKNOWN_STAGE"},
                ],
            }}, {
                "card_title": "Hours of sleep",
                "arrow_stat": [{"current_stat_text": "synthetic-hours"}],
            }, {
                "card_title": "Sleep efficiency",
                "arrow_stat": [{"current_stat_text": "synthetic-efficiency"}],
            }, {
                "card_title": "Sleep consistency",
                "arrow_stat": [{"current_stat_text": "synthetic-consistency"}],
            }, {
                "type": "DETAILS_METRIC_TILES",
                "content": {"title": "WAKE EVENTS"},
            }],
            "graph": {"points": [
                {"position_x": 0.2, "data_scrubber_details": {"scrubber_style": "AWAKE"}},
                {"position_x": "0.8", "data_scrubber_details": {"scrubber_style": "REM_SLEEP"}},
                {"position_x": 0.4, "data_scrubber_details": {"scrubber_style": "UNKNOWN_STAGE"}},
            ]},
            "account_id": "synthetic-account-identifier-004",
            "health_note": "synthetic-sleep-string-do-not-return",
        }

        result = probe.summarize_probe("sleep", payload)

        self.assertEqual({
            "window_available": True, "stage_bar_count": 4, "stage_point_count": 2,
            "sleep_duration_card_available": True, "efficiency_card_available": True,
            "consistency_card_available": True, "wake_events_tile_available": True,
        }, result)
        self.assert_redacted(result, payload)

    def test_missing_and_partial_payloads_do_not_fabricate_availability_or_counts(self):
        cases = {
            "workout": {
                "horizontal_stat": {"stat_main_value_display": ""},
                "key_metric_carousel": {"key_metric_tile": [
                    {"key_metric_tile_icon": "CALORIES", "key_metric_tile_stat_value_display": "bad"},
                ]},
                "graph_response": {"plots": []},
                "bar_graph_container": {"heart_rate_zones": []},
            },
            "sleep_need": {
                "need_breakdown": {"baseline": 8, "debt": 1, "strain": None, "naps": 0},
                "recommended_time_in_bed_formatted": {"70": {"recommended_time_in_bed": 0}},
            },
            "strain": {"type": "SCORE_GAUGE", "content": {
                "id": "STRAIN_SCORE_GAUGE", "score_display": "nan",
                "score_target": 1.1, "lower_optimal_percentage": 0.2,
                "higher_optimal_percentage": "Infinity",
            }},
            "sleep": {"header_section": {"destination": {"parameters": {
                "start_time": "not-an-iso-date", "end_time": "2026-01-01T00:00:00Z",
            }}}, "cards": [{"type": "BAR_GRAPH_CARD", "content": {
                "duration_display": "", "heart_rate_zones": [{"id": "AWAKE"}],
            }}]},
        }
        expected = {
            "workout": {"has_strain": False, "has_calories": False, "has_avg_hr": False,
                        "has_max_hr": False, "hr_point_count": 0, "zone_count": 0},
            "sleep_need": {"breakdown_available": False, "recommendation_tier_count": 1},
            "strain": {"score_available": False, "target_available": False,
                       "optimal_range_available": False},
            "sleep": {
                "window_available": False, "stage_bar_count": 0, "stage_point_count": 0,
                "sleep_duration_card_available": False, "efficiency_card_available": False,
                "consistency_card_available": False, "wake_events_tile_available": False,
            },
        }
        for name, payload in cases.items():
            with self.subTest(probe=name):
                self.assertEqual(expected[name], probe.summarize_probe(name, payload))

        for name in probe.PATHS:
            with self.subTest(probe=name, payload="missing"):
                self.assertTrue(all(value is False or value == 0
                                    for value in probe.summarize_probe(name, {}).values()))

    def test_sleep_malformed_or_incomplete_cards_are_unavailable(self):
        payload = {"cards": [
            {"card_title": "Hours of sleep", "arrow_stat": "not-a-list"},
            {"card_title": "Sleep efficiency", "arrow_stat": [{"current_stat_text": "  "}]},
            {"card_title": "Sleep consistency", "arrow_stat": [{"current_stat_text": 8}]},
            {"type": "DETAILS_METRIC_TILES", "content": {"title": "OTHER METRICS"}},
        ]}

        result = probe.summarize_probe("sleep", payload)

        self.assertEqual({
            "window_available": False, "stage_bar_count": 0, "stage_point_count": 0,
            "sleep_duration_card_available": False, "efficiency_card_available": False,
            "consistency_card_available": False, "wake_events_tile_available": False,
        }, result)

    def test_malformed_graphs_and_nonfinite_or_nonnumeric_values_are_ignored(self):
        workout = {
            "horizontal_stat": {"stat_main_value_display": float("inf")},
            "key_metric_carousel": {"key_metric_tile": [
                {"key_metric_tile_icon": "HEART_RATE", "key_metric_tile_stat_value_display": "NaN"},
            ]},
            "graph_response": {"plots": [None, {"plot": {"segments": [
                {"points": "not-a-list"}, {"points": [
                    {"position_x": 0.5, "data_scrubber_details": {"unit_display": "bpm", "value_display": "bad"}},
                    {"position_x": float("nan"), "data_scrubber_details": {"unit_display": "bpm", "value_display": 140}},
                    {"position_x": 1.2, "data_scrubber_details": {"unit_display": "bpm", "value_display": 140}},
                ]},
            ]}}]},
            "bar_graph_container": {"heart_rate_zones": "not-a-list"},
        }
        sleep = {"header_section": {"destination": {"parameters": {
            "start_time": "2026-11-01T00:00:00Z", "end_time": "2026-11-01T01:00:00Z",
        }}}, "graph": {"points": [
            {"position_x": "not-a-number", "data_scrubber_details": {"scrubber_style": "AWAKE"}},
            {"position_x": float("inf"), "data_scrubber_details": {"scrubber_style": "REM_SLEEP"}},
            {"position_x": 0.5, "data_scrubber_details": {"scrubber_style": "LIGHT_SLEEP"}},
        ]}}
        self.assertEqual(0, probe.summarize_probe("workout", workout)["hr_point_count"])
        self.assertEqual(0, probe.summarize_probe("workout", workout)["zone_count"])
        self.assertEqual(1, probe.summarize_probe("sleep", sleep)["stage_point_count"])

    def test_unknown_probe_name_is_rejected(self):
        with self.assertRaises(ValueError):
            probe.summarize_probe("unknown", {})

    def assert_redacted(self, result, payload):
        rendered = json.dumps(result)
        self.assertNotIn("synthetic-account-identifier", rendered)
        self.assertNotIn("synthetic-health-string", rendered)
        self.assertNotIn("synthetic-sleep-need-string", rendered)
        self.assertNotIn("synthetic-strain-string", rendered)
        self.assertNotIn("synthetic-sleep-string", rendered)
        self.assertNotIn("synthetic-duration-do-not-return", rendered)
        self.assertNotIn("UNKNOWN_PRIVATE_ZONE", rendered)
        self.assertNotIn("UNKNOWN_STAGE", rendered)
        self.assertNotIn("STRAIN_SCORE_GAUGE", rendered)
        self.assertNotIn("CALORIES", rendered)
        self.assertNotIn("2026-10-31T22:30:00", rendered)
        self.assertNotIn("456", rendered)
        self.assertNotIn("12.7", rendered)
        self.assertNotIn("payload", rendered)


class FetchProbeTest(unittest.TestCase):
    def setUp(self):
        self.tokens = probe.api.SessionTokens(
            access_token="synthetic-access-token",
            refresh_token="synthetic-refresh-token",
            expires_at=0,
        )

    @patch("private_read_probe.build_opener")
    def test_success_uses_fixed_get_destination_and_emits_only_summary(self, build_opener):
        payload = {
            "account_id": "synthetic-account-identifier",
            "health_note": "synthetic-health-value-do-not-return",
            "sections": [{"items": [{
                "type": "SCORE_GAUGE",
                "content": {"id": "STRAIN_SCORE_GAUGE", "score_display": "13.2"},
            }]}],
        }
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps(payload).encode()
        opener = build_opener.return_value
        opener.open.return_value = response

        result = probe.fetch_probe("strain", {"date": "2026-11-01"}, self.tokens)

        request = opener.open.call_args.args[0]
        self.assertEqual(
            "https://api.prod.whoop.com/home-service/v1/deep-dive/strain"
            "?apiVersion=7&date=2026-11-01",
            request.full_url,
        )
        self.assertEqual("GET", request.get_method())
        self.assertEqual("Bearer synthetic-access-token", request.get_header("Authorization"))
        self.assertEqual({
            "http_status": 200,
            "features": {
                "score_available": True,
                "target_available": False,
                "optimal_range_available": False,
            },
        }, result)
        self.assertNotIn("synthetic-account-identifier", json.dumps(result))
        self.assertNotIn("synthetic-health-value", json.dumps(result))

    @patch("private_read_probe.build_opener")
    def test_http_rejection_body_is_not_exposed(self, build_opener):
        body = b'{"message":"synthetic-private-response-value"}'
        build_opener.return_value.open.side_effect = HTTPError(
            "https://api.prod.whoop.com/home-service/v1/deep-dive/strain?apiVersion=7",
            403, "Forbidden", None, BytesIO(body),
        )

        result = probe.fetch_probe("strain", {}, self.tokens)

        self.assertEqual({"http_status": 403, "error": "request_rejected"}, result)
        self.assertNotIn("synthetic-private-response-value", json.dumps(result))

    @patch("private_read_probe.build_opener")
    def test_malformed_json_is_sanitized(self, build_opener):
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"health_note":"synthetic-private-response-value"'
        build_opener.return_value.open.return_value = response

        result = probe.fetch_probe("sleep", {}, self.tokens)

        self.assertEqual({"error": "invalid_response"}, result)
        self.assertNotIn("synthetic-private-response-value", json.dumps(result))

    @patch("private_read_probe.build_opener")
    def test_non_dict_json_is_sanitized(self, build_opener):
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'["synthetic-private-response-value"]'
        build_opener.return_value.open.return_value = response

        result = probe.fetch_probe("sleep", {}, self.tokens)

        self.assertEqual({"http_status": 200, "error": "unexpected_shape"}, result)
        self.assertNotIn("synthetic-private-response-value", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
