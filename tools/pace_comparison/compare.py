import argparse
import csv
import io
import hashlib
import json
import math
import pathlib
import statistics
from collections import Counter, deque

ROOT = pathlib.Path(__file__).resolve().parent
RADIUS = 6371008.8
CANDIDATES = [
    ("box5_ready4", 5, 4, "box"),
    ("box10_ready8", 10, 8, "box"),
    ("box20_ready8", 20, 8, "box"),
    ("box30_ready8", 30, 8, "box"),
    ("ema_tau5_ready8", 60, 8, "ema5"),
    ("ema_tau10_ready8", 60, 8, "ema10"),
    ("sensor_ready8", 20, 8, "sensor"),
    ("box5_ready8_control", 5, 8, "box"),
    ("box10_ready4_control", 10, 4, "box"),
    ("box20_ready4_control", 20, 4, "box"),
    ("box30_ready4_control", 30, 4, "box")
]


def speed_at(scenario, t):
    pace = next(p for start, p in reversed(scenario["legs"]) if start <= t)
    return 1000 / pace if pace else 0.0


def position_at(scenario, t):
    legs = scenario["legs"]
    return sum(max(0, min(t, legs[i + 1][0] if i + 1 < len(legs) else t) - start)
               * (1000 / pace if pace else 0) for i, (start, pace) in enumerate(legs))


def inside(scenario, key, t):
    bounds = scenario.get(key)
    return bounds is not None and bounds[0] <= t < bounds[1]


def fix_at(scenario, t, received):
    phase = scenario.get("phase", 0)
    noise = scenario.get("noise_m", 0)
    x = position_at(scenario, t) + noise * math.sin(t * 1.73 + phase)
    y = noise * math.cos(t * 1.11 + phase)
    x += scenario.get("low_frequency_m", 0) * math.sin(t * 0.23)
    if scenario.get("jump_at") == t:
        x += 80
    velocity = speed_at(scenario, t)
    provided = None if scenario.get("speed_mode") == "missing" else max(
        0, velocity + scenario.get("speed_bias", 0) + scenario.get("speed_noise", 0.03) * math.sin(t * 1.37))
    if velocity == 0 and scenario.get("speed_mode") != "missing":
        provided = 0.0
    return {"at": round(t * 1000), "received": received, "lat": math.degrees(y / RADIUS),
            "lon": math.degrees(x / RADIUS), "accuracy": 40 if inside(scenario, "bad_accuracy", t) else scenario.get("accuracy", 5),
            "speed": provided}


def events(scenario):
    fixes = {}
    now = 0
    index = 0
    intervals = scenario.get("intervals_ms", [1000])
    while now <= scenario["duration"] * 1000:
        t = now / 1000
        if not inside(scenario, "outage", t):
            fixes[now] = fix_at(scenario, t, now)
        now += intervals[index % len(intervals)]
        index += 1
    if "stale_at" in scenario:
        at = scenario["stale_at"] * 1000
        fixes[at] = fix_at(scenario, scenario["stale_at"] - 4, at)
    if "out_of_order_at" in scenario:
        at = scenario["out_of_order_at"] * 1000
        fixes[at] = fix_at(scenario, scenario["out_of_order_at"] - 2, at)
    times = sorted(set(range(0, scenario["duration"] * 1000 + 1, 1000)) | fixes.keys())
    for now in times:
        action = ""
        if scenario.get("pause", [None, None])[0] == now / 1000:
            action = "pause"
        elif scenario.get("pause", [None, None])[1] == now / 1000:
            action = "resume"
        yield {"now": now, "action": action, "fix": fixes.get(now), "tick": now % 1000 == 0}


def distance(a, b):
    lat1, lat2 = math.radians(a["lat"]), math.radians(b["lat"])
    dlat, dlon = lat2 - lat1, math.radians(b["lon"] - a["lon"])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return RADIUS * 2 * math.atan2(math.sqrt(h), math.sqrt(max(0, 1 - h)))


class Estimator:
    def __init__(self, name, window, ready, mode):
        self.name, self.window, self.ready, self.mode = name, window * 1000, ready * 1000, mode
        self.reset()

    def reset(self):
        self.samples = deque()
        self.ema = None
        self.pace = None

    def accept(self, active, meters, fix):
        previous = self.samples[-1] if self.samples else None
        self.samples.append((active, meters))
        while len(self.samples) > 1 and active - self.samples[0][0] > self.window:
            self.samples.popleft()
        dt = active - self.samples[0][0]
        dm = meters - self.samples[0][1]
        if previous is not None and active > previous[0] and self.mode.startswith("ema"):
            elapsed = (active - previous[0]) / 1000
            velocity = (meters - previous[1]) / elapsed
            alpha = 1 - math.exp(-elapsed / float(self.mode[3:]))
            self.ema = velocity if self.ema is None else self.ema + alpha * (velocity - self.ema)
        if dt < self.ready or dm < 8:
            self.pace = None
        elif self.mode == "box":
            self.pace = dt / dm
        elif self.mode.startswith("ema"):
            self.pace = 1000 / self.ema if self.ema and self.ema > 0 else None
        else:
            v = fix["speed"]
            self.pace = 1000 / v if v is not None and math.isfinite(v) and v > 0 else None


class Pipeline:
    def __init__(self, estimators):
        self.estimators = estimators
        self.clock = None
        self.active = 0
        self.meters = 0.0
        self.last = None
        self.running = True
        self.resume_at = -1
        self.segment = 0
        self.raw_meters = 0.0
        self.raw_last = None
        self.reason = "start"
        self.rejected = Counter()

    def reset(self):
        for estimator in self.estimators:
            estimator.reset()

    def clock_update(self, now):
        if self.clock is not None and now <= self.clock:
            return
        previous = self.clock
        self.clock = now
        if not self.running or previous is None:
            return
        self.active += now - previous
        if self.last is None or now - self.last["at"] > 10000:
            self.reset()
            self.reason = "expired"

    def process(self, event):
        now, fix = event["now"], event["fix"]
        self.reason = "held"
        if event["action"] == "pause":
            self.running = False
            self.last = None
            self.reset()
        elif event["action"] == "resume":
            self.running = True
            self.clock = now
            self.resume_at = now
            self.last = None
            self.segment += 1
            self.reset()
        if fix is not None:
            self.ingest(fix)
        if event["tick"]:
            self.clock_update(now)

    def ingest(self, fix):
        if fix["at"] < self.resume_at or self.last and fix["at"] <= self.last["at"]:
            self.reason = "out_of_order"
            self.rejected["out_of_order"] += 1
            return
        if fix["received"] - fix["at"] not in range(0, 3001):
            self.last = None
            self.reset()
            self.reason = "stale"
            self.rejected["stale"] += 1
            return
        self.clock_update(fix["at"])
        if not self.running:
            self.reason = "paused"
            return
        if not math.isfinite(fix["accuracy"]) or not 0 <= fix["accuracy"] <= 25:
            self.last = None
            self.reset()
            self.reason = "accuracy"
            self.rejected["accuracy"] += 1
            return
        previous = self.last
        seconds = (fix["at"] - previous["at"]) / 1000 if previous else 0
        delta = distance(previous, fix) if previous else 0
        if self.raw_last:
            self.raw_meters += distance(self.raw_last, fix)
        self.raw_last = fix
        if previous and seconds < 15 and delta / seconds > 12:
            for estimator in self.estimators:
                estimator.pace = None
            self.reason = "jump"
            self.rejected["jump"] += 1
            return
        v = fix["speed"]
        moving = v >= 0.7 if v is not None and math.isfinite(v) and v >= 0 else delta > max(2.5, fix["accuracy"] / 2)
        added = delta if previous and 0.001 <= seconds <= 15 and moving and delta >= 0.8 else 0
        if previous is None or seconds > 15:
            self.segment += 1
            self.reset()
        self.meters += added
        self.last = fix
        for estimator in self.estimators:
            estimator.accept(self.active, self.meters, fix)
        self.reason = "accepted"


def direction(pace, target=360, tolerance=10):
    if pace is None or not 0 < pace <= 3600:
        return None
    return "FAST" if pace < target - tolerance else "SLOW" if pace > target + tolerance else None


class Coaching:
    def __init__(self, margin, sustain, repeat=30, start_delay=2, duration=4, blocked=()):
        self.margin, self.sustain, self.repeat = margin, sustain, repeat * 1000
        self.start_delay, self.duration, self.blocked = start_delay * 1000, duration * 1000, blocked
        self.direction, self.since, self.alarm = None, None, None
        self.last_spoken = None
        self.pending = None
        self.eligible = False
        self.log = []

    def evaluate(self, now, pace, running, gps_at, target=360, tolerance=10):
        if self.pending and now >= self.pending[1] and not self.pending[2]:
            self.last_spoken = now
            self.pending[2] = True
            self.log.append(("virtual_start", now, self.pending[3]))
        if self.pending and now >= self.pending[1] + self.duration:
            self.pending = None
        valid = running and gps_at is not None and 0 <= now - gps_at <= 10000 and pace is not None and 0 < pace <= 3600
        candidate = direction(pace, target, tolerance) if valid else None
        new = candidate if candidate == self.direction or candidate and abs(pace - target) > tolerance + self.margin else None
        if new != self.direction or self.since is None:
            self.since = now if new else None
        self.direction = new
        elapsed = now - self.since if self.since is not None else 0
        alarm = new if new and elapsed >= self.sustain else None
        if alarm != self.alarm:
            self.log.append(("alarm_start" if alarm else "alarm_stop", now, alarm))
        self.alarm = alarm
        due = bool(new and elapsed >= 10000 and self.pending is None and (self.last_spoken is None or now - self.last_spoken >= self.repeat))
        if due and not self.eligible:
            self.log.append(("voice_eligible", now, new))
        self.eligible = due
        blocked = any(a * 1000 <= now < b * 1000 for a, b in self.blocked)
        if due and not blocked:
            self.log.append(("virtual_request", now, new))
            self.pending = [now, now + self.start_delay, False, new]
        if not running:
            self.direction, self.since, self.last_spoken, self.pending = None, None, None, None


def valid_pace(pace):
    return pace is not None and math.isfinite(pace) and 0 < pace <= 3600


def replay(scenario):
    estimators = [Estimator(*definition) for definition in CANDIDATES]
    pipeline = Pipeline(estimators)
    coaches = {(e.name, policy): Coaching(margin, sustain, blocked=((60, 68),))
               for e in estimators for policy, margin, sustain in [("legacy_9500d1f", 2, 2000), ("issue34_proposed", 0, 0)]}
    rows, trace = [], []
    for event in events(scenario):
        pipeline.process(event)
        truth_speed = speed_at(scenario, event["now"] / 1000)
        values = {e.name: e.pace for e in estimators}
        for (name, policy), coach in coaches.items():
            coach.evaluate(event["now"], values[name], pipeline.running, pipeline.last["at"] if pipeline.last else None)
        if event["tick"]:
            rows.append({"now": event["now"], "truth": 1000 / truth_speed if truth_speed else None,
                         "running": pipeline.running, "values": values})
        trace.append({**event, "active": pipeline.active, "meters": pipeline.meters,
                      "pace": values["box20_ready8"], "segment": pipeline.segment,
                      "reason": pipeline.reason})
    return rows, trace, coaches, pipeline


def convergence(rows, scenario, name):
    results = []
    for i, (start, pace) in enumerate(scenario["legs"]):
        if start == 0 or pace == 0:
            continue
        end = scenario["legs"][i + 1][0] if i + 1 < len(scenario["legs"]) else scenario["duration"] + 1
        subset = [r for r in rows if start * 1000 <= r["now"] < end * 1000 and r["running"]]
        hit = None
        for j in range(len(subset) - 2):
            if all(valid_pace(r["values"][name]) and abs(r["values"][name] - pace) <= pace * 0.05 for r in subset[j:j + 3]):
                hit = subset[j]["now"] / 1000 - start
                break
        results.append({"change_s": start, "delay_s": hit})
    return results


def summarize(scenario, rows, coaches, pipeline):
    results = []
    for name, window, ready, mode in CANDIDATES:
        moving = [r for r in rows if r["running"] and r["truth"] is not None]
        valid = [r for r in moving if valid_pace(r["values"][name])]
        errors = [abs(r["values"][name] - r["truth"]) for r in valid]
        states = [direction(r["values"][name]) if r["running"] else None for r in rows]
        flips = sum(a != b for a, b in zip(states, states[1:]))
        false_states = sum(r["running"] and direction(r["truth"]) is None and s is not None for r, s in zip(rows, states))
        record = {"scenario": scenario["name"], "candidate": name, "mae_s_per_km": round(statistics.mean(errors), 3) if errors else None,
                  "valid_percent": round(100 * len(valid) / len(moving), 3) if moving else None,
                  "first_valid_s": next((r["now"] / 1000 for r in rows if valid_pace(r["values"][name])), None),
                  "boundary_transitions": flips, "false_outside_seconds": false_states,
                  "wrong_direction_seconds": sum(s is not None and s != direction(r["truth"]) for r, s in zip(rows, states)),
                  "round_trips": sum(a is not None and b is None for a, b in zip(states, states[1:])),
                  "convergence": convergence(rows, scenario, name)}
        for policy in ["legacy_9500d1f", "issue34_proposed"]:
            log = coaches[(name, policy)].log
            starts = [t for event, t, _ in log if event == "alarm_start"]
            false_starts = sum(d != direction(1000 / speed_at(scenario, t / 1000) if speed_at(scenario, t / 1000) else None) for event, t, d in log if event == "alarm_start")
            record[policy] = {"alarm_starts": len(starts), "false_alarm_starts": false_starts,
                              "events": [[event, t / 1000, d] for event, t, d in log]}
        results.append(record)
    return results


def generate():
    data = json.loads((ROOT / "scenarios.json").read_text())
    summary, pipelines = [], []
    for scenario in data["scenarios"]:
        rows, trace, coaches, pipeline = replay(scenario)
        summary.extend(summarize(scenario, rows, coaches, pipeline))
        truth_m = position_at(scenario, scenario["duration"])
        pipelines.append({"scenario": scenario["name"], "truth_m": round(truth_m, 3),
                          "recorded_m": round(pipeline.meters, 3), "raw_polyline_m": round(pipeline.raw_meters, 3),
                          "rejected": {reason: pipeline.rejected[reason] for reason in ["accuracy", "stale", "out_of_order", "jump"]}})
    compact = [{k: v for k, v in r.items() if k not in ["legacy_9500d1f", "issue34_proposed"]} |
               {f"{p}_{k}": r[p][k] for p in ["legacy_9500d1f", "issue34_proposed"] for k in ["alarm_starts", "false_alarm_starts"]} for r in summary]
    report = {"baseline_commit": data["baseline_commit"], "fixture_sha256": hashlib.sha256((ROOT / "scenarios.json").read_bytes()).hexdigest(),
              "virtual_audio": {"start_delay_s": 2, "duration_s": 4, "blocked_s": [[60, 68]], "repeat_s": 30},
              "pipeline": pipelines, "results": compact}
    event_selection = [r for r in summary if r["scenario"] in ["accelerate_decelerate", "boundary_truth", "outage_return", "pause_resume", "sparse_window_boundary"] and r["candidate"] in ["box5_ready4", "box10_ready8", "box20_ready8", "box30_ready8"]]
    timeline = [{"scenario": r["scenario"], "candidate": r["candidate"], p: r[p]["events"]} for r in event_selection for p in ["legacy_9500d1f", "issue34_proposed"]]
    return report, timeline


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.trace:
        data = json.loads((ROOT / "scenarios.json").read_text())
        print(json.dumps([{ "name": s["name"], "trace": replay(s)[1]} for s in data["scenarios"]], allow_nan=False))
        return
    report, timeline = generate()
    table = io.StringIO(newline="")
    writer = csv.DictWriter(table, fieldnames=list(report["results"][0]), lineterminator="\n")
    writer.writeheader()
    for result in report["results"]:
        writer.writerow(result | {"convergence": json.dumps(result["convergence"], separators=(",", ":"))})
    metadata = {k: v for k, v in report.items() if k != "results"}
    files = {"metadata.json": json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
             "results.csv": table.getvalue(),
             "timelines.json": "[\n" + ",\n".join(json.dumps(row, separators=(",", ":")) for row in timeline) + "\n]\n"}
    for name, content in files.items():
        path = ROOT / name
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f"재현 결과 불일치: {name}")
        else:
            path.write_text(content)
    print(f"{len(report['pipeline'])} scenarios × {len(CANDIDATES)} candidates × 2 policies; deterministic results {'verified' if args.check else 'written'}")


if __name__ == "__main__":
    main()
