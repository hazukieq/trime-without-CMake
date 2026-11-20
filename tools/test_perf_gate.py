"""Performance logs must retain real preedit keys and process boundaries."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("perf_gate", Path(__file__).with_name("perf-gate.py"))
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


class PerfLogTest(unittest.TestCase):
    def test_raw_preedit_spaces_and_control_separators_keep_full_samples(self):
        data = perf.parse(
            "perfgate: process-start t=1\n"
            "twelvekey:build i48i5i73\x00\x1e0\x009,1\x00i4 8i 5i 73‸|full|e1 cached=false ms=154.8\n"
            "twelvekey:build 9ai\x00tai‸|bar|e2 cached=false ms=7.8\n"
            "twelvekey:build i48i5i73\x00i4 8i 5i 73‸|full|e1 cached=true ms=0.0\n"
        )
        self.assertEqual(1, data['build_full_n'])
        self.assertEqual(154.8, data['build_full_p90'])
        self.assertEqual(1, data['build_n'])
        self.assertEqual(7.8, data['build_p90'])
        rows = perf.evaluate(data, perf.DEFAULT_THRESHOLDS)
        self.assertFalse(next(r for r in rows if r['name'] == 'build_full_p90')['ok'])

    def test_previous_process_and_cache_hits_cannot_hide_current_failure(self):
        data = perf.parse(
            "perfgate: process-start t=1\n"
            "twelvekey:build old|bar cached=false ms=9999\n"
            "perfgate: process-start t=2\n"
            "twelvekey:build new|bar cached=false ms=301\n"
            "twelvekey:build new|bar cached=true ms=0\n"
            "twelvekey:latency 310ms input=[9ai]\n"
        )
        self.assertEqual(1, data['build_n'])
        self.assertEqual(301, data['build_max'])
        self.assertEqual(310, data['latency_max'])


if __name__ == '__main__':
    unittest.main()
