import json
import math
import pathlib
import unittest

from compare import CANDIDATES, Coaching, Estimator, Pipeline, events, generate, replay


class ComparisonTest(unittest.TestCase):
    def scenario(self, name):
        return next(s for s in json.loads(pathlib.Path(__file__).with_name("scenarios.json").read_text())["scenarios"] if s["name"] == name)

    def test_five_second_window_cannot_reach_eight_seconds(self):
        estimator = Estimator("control", 5, 8, "box")
        for i in range(120):
            estimator.accept(i * 1000, i * 3, {"speed": 3})
            self.assertIsNone(estimator.pace)
        estimator = Estimator("candidate", 5, 4, "box")
        for i in range(5):
            estimator.accept(i * 1000, i * 3, {"speed": 3})
        self.assertAlmostEqual(1000 / 3, estimator.pace)

    def test_window_boundary_uses_observations_without_interpolation(self):
        estimator = Estimator("candidate", 20, 8, "box")
        for i in [0, 4000, 8000, 12000, 16000, 20000]:
            estimator.accept(i, i / 300, {"speed": 1000 / 300})
        self.assertEqual(0, estimator.samples[0][0])
        estimator.accept(24000, 80, {"speed": 1000 / 300})
        self.assertEqual(4000, estimator.samples[0][0])
        estimator.accept(24500, 81.6666666667, {"speed": 1000 / 300})
        self.assertEqual(8000, estimator.samples[0][0])

    def test_timer_does_not_make_new_pace_and_expiry_resets_it(self):
        estimator = Estimator("baseline", 20, 8, "box")
        pipeline = Pipeline([estimator])
        source = list(events(self.scenario("steady_clean")))
        for event in source[:21]:
            pipeline.process(event)
        pace, meters = estimator.pace, pipeline.meters
        for now in range(21000, 31000, 1000):
            pipeline.process({"now": now, "fix": None, "action": "", "tick": True})
            self.assertEqual(pace, estimator.pace)
            self.assertEqual(meters, pipeline.meters)
        pipeline.process({"now": 31000, "fix": None, "action": "", "tick": True})
        self.assertIsNone(estimator.pace)
        self.assertFalse(estimator.samples)

    def test_filter_bias_is_shared_by_all_coordinate_candidates(self):
        rows, trace, coaches, pipeline = replay(self.scenario("steady_noise_2"))
        self.assertGreater(pipeline.meters, 600)
        values = rows[-1]["values"]
        self.assertLess(values["box20_ready8"], 320)
        self.assertTrue(abs(values["sensor_ready8"] - 360) < 5)
        self.assertTrue(all(math.isfinite(v) for v in values.values() if v is not None))

    def test_stale_out_of_order_jump_are_independent(self):
        rows, trace, coaches, pipeline = replay(self.scenario("stale_out_of_order_jump"))
        self.assertEqual({"stale": 1, "out_of_order": 1, "jump": 1}, dict(pipeline.rejected))
        self.assertIsNone(next(r for r in trace if r["now"] == 45000)["pace"])
        self.assertIsNone(next(r for r in trace if r["now"] == 90000)["pace"])

    def test_coaching_margin_sustain_request_and_virtual_start_are_separate(self):
        old, proposed = Coaching(2, 2000), Coaching(0, 0)
        for t in range(0, 16000, 1000):
            old.evaluate(t, 371, True, t)
            proposed.evaluate(t, 371, True, t)
        self.assertFalse(old.log)
        self.assertEqual(("alarm_start", 0, "SLOW"), proposed.log[0])
        self.assertIn(("voice_eligible", 10000, "SLOW"), proposed.log)
        self.assertIn(("virtual_request", 10000, "SLOW"), proposed.log)
        self.assertIn(("virtual_start", 12000, "SLOW"), proposed.log)
        proposed.evaluate(16000, 370, True, 16000)
        self.assertEqual(("alarm_stop", 16000, None), proposed.log[-1])

    def test_virtual_queue_delay_is_separate_from_voice_eligibility(self):
        coach = Coaching(0, 0, blocked=((8, 24),))
        for t in range(0, 31000, 1000):
            coach.evaluate(t, 420, True, t)
        self.assertIn(("voice_eligible", 10000, "SLOW"), coach.log)
        self.assertIn(("virtual_request", 24000, "SLOW"), coach.log)
        self.assertIn(("virtual_start", 26000, "SLOW"), coach.log)

    def test_repeat_is_based_on_virtual_start_not_request(self):
        for repeat in [10, 30, 120]:
            coach = Coaching(0, 0, repeat=repeat)
            for t in range(0, 160001, 1000):
                coach.evaluate(t, 420, True, t)
            requests = [t for e, t, d in coach.log if e == "virtual_request"]
            self.assertEqual(10000, requests[0])
            self.assertEqual(12000 + repeat * 1000, requests[1])

    def test_all_results_and_negative_control_are_finite_and_consistent(self):
        report, timeline = generate()
        self.assertEqual(21 * len(CANDIDATES), len(report["results"]))
        for result in report["results"]:
            if result["candidate"] == "box5_ready8_control" and result["valid_percent"] is not None:
                self.assertEqual(0, result["valid_percent"])
        json.dumps(report, allow_nan=False)
        json.dumps(timeline, allow_nan=False)


if __name__ == "__main__":
    unittest.main()
