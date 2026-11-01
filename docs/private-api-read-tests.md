# Private API read tests

On 2026-11-01, four selected private read endpoints returned HTTP 200 and usable fields against the live account. These were GET requests only; no activity or other account data was changed. Journal and alarms were outside this test.

| Capability | GET path | Observed response features |
| --- | --- | --- |
| Workout detail | `/core-details-bff/v1/cardio-details` | Strain, calories, average/max heart rate, heart-rate graph points, and six heart-rate zone rows |
| Sleep need | `/coaching-service/v2/sleepneed` | Baseline/debt/strain/nap breakdown and three time-in-bed recommendation tiers |
| Strain target | `/home-service/v1/deep-dive/strain` | Current score, target marker, and optimal-range bounds |
| Detailed sleep | `/home-service/v1/deep-dive/sleep/last-night` | Sleep window, four stage rows, stage-labelled graph points, duration/efficiency/consistency cards, and a wake-events tile |

These are observations from one account and date, not a compatibility guarantee or a complete domain parser. The sleep card checks establish presence of nonempty display fields; the wake-events check establishes tile presence only. They do not validate every displayed value or establish clinical accuracy. No private-API feature has been added to the installed app by these tests.

## Repeatable probe

With an existing local account session and a successfully submitted local workout draft:

```sh
uv run --frozen python setup/private_read_probe.py \
  --workout-draft <local-submitted-draft-id> --date YYYY-MM-DD
```

Use `--only workout`, `--only sleep_need`, `--only strain`, or `--only sleep` to check one route. The date selects strain and sleep data; sleep need is requested without a date, and workout detail uses the draft's stored activity receipt. Every request includes `apiVersion=7`.

The helper prints only HTTP status, feature-presence flags, and structural counts. It overwrites `local/private-api/read-probes.json` with the latest run's summary, not raw responses. This file and the separate credential/session files stay within the ignored local directory. Session preparation may refresh an expired account session; this test did not establish long-term session refresh reliability. HTTP 200 alone is not proof that every feature is available: inspect the feature flags as well.

## Integration notes

- These responses contain presentation-oriented BFF JSON, not stable public domain schemas. Keep the documented Developer API as the source for canonical records and metrics; isolate any private mapping from UI code.
- Sleep need uses `need_breakdown` and `recommended_time_in_bed_formatted`. The observed numeric durations are milliseconds. Recommendation keys `70`, `85`, and `100` are separate tiers; do not silently choose one as a universal recommendation.
- Strain uses a `SCORE_GAUGE` whose content ID is `STRAIN_SCORE_GAUGE`. The observed `score_target`, `lower_optimal_percentage`, and `higher_optimal_percentage` are fractions from 0 to 1, not direct 0-to-21 strain scores. Validate their interpretation against WHOOP's presentation before exposing converted targets.
- Heart-rate and sleep graphs use normalized display coordinates such as `position_x`. They are not a documented raw sensor stream or fixed sampling interval. In particular, padded sleep chart axes must not be treated as the exact sleep window when deriving timestamps.
- Keep individual zone rows distinct. Aggregated time in zones 1-3 or 4-5 must not be assigned to a single zone.
- Missing fields must remain unavailable rather than becoming zero. Sleep latency and other untested fields are not implied by the presence of detailed sleep cards.
- Do not persist raw health responses in fixtures, logs, or source control. Use synthetic schema fixtures and keep account credentials separate from Developer OAuth credentials.

Next work requires a separate integration decision: provisioning private credentials on-device, secure storage, session expiry/recovery, and mapping these unstable schemas. The current test is evidence of readable capabilities, not approval to expand the installed app's credential or network surface.
