import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from summarize_runs import summarize, load_run

class SummaryTest(unittest.TestCase):
    def test_unannotated_runs_are_not_silently_removed(self):
        report = summarize([([], {'actual_success':True, 'wrong_guidance':0}), ([], None)])
        self.assertEqual(report['attempts'], 2)
        self.assertEqual(report['unannotated_attempts'], 1)
        self.assertIsNone(report['success_rate'])

    def test_failure_and_timeout_remain_in_denominators(self):
        events = [{'event':'request','data':{'attempt':1}}, {'event':'response','data':{'error':'timeout','latency_ms':30000}}]
        report = summarize([(events, {'actual_success':False,'wrong_guidance':1}), ([], {'actual_success':True,'wrong_guidance':0})])
        self.assertEqual(report['success_rate'], 0.5)
        self.assertEqual(report['latency_p95_ms'], 30000)
        self.assertEqual(report['response_errors'], 1)
        self.assertEqual(report['wrong_guidance'], 1)

    def test_prepared_cancelled_request_is_not_model_call(self):
        report = summarize([([{'event':'request_prepared','data':{}}, {'event':'request_cancelled','data':{}}], None)])
        self.assertEqual(report['model_calls'], 0)
        self.assertIsNone(report['latency_median_ms'])

    def test_exported_zip_is_read_without_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'run.zip'
            with zipfile.ZipFile(path,'w') as z:
                z.writestr('events.jsonl', json.dumps({'event':'start','data':{}})+'\n')
                z.writestr('annotation.json', json.dumps({'actual_success':False,'wrong_guidance':0}))
            rows, annotation = load_run(path)
            self.assertEqual(rows[0]['event'], 'start')
            self.assertFalse(annotation['actual_success'])

    def test_corrupt_log_causes_explicit_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,'events.jsonl').write_text('not-json\n')
            with self.assertRaises(json.JSONDecodeError):
                load_run(directory)

    def test_guidance_and_task_metrics_include_failed_attempts(self):
        events = [{'event':'guidance','data':{'guidance_latency_ms':1000}},
                  {'event':'guidance','data':{'guidance_latency_ms':3000}},
                  {'event':'end','data':{'result':'FAILED','duration_ms':300000}}]
        report = summarize([(events, {'actual_success':False,'wrong_guidance':1})])
        self.assertEqual(report['wrong_guidance_rate'], 0.5)
        self.assertEqual(report['guidance_latency_median_ms'], 2000)
        self.assertEqual(report['guidance_latency_p95_ms'], 3000)
        self.assertEqual(report['task_duration_samples'], 1)
        self.assertEqual(report['task_duration_max_ms'], 300000)

    def test_preparation_stop_does_not_fabricate_duration_or_error_rate(self):
        report = summarize([([{'event':'end','data':{'result':'STOPPED','duration_ms':None}}],
                             {'actual_success':False,'wrong_guidance':0})])
        self.assertEqual(report['task_duration_samples'], 0)
        self.assertIsNone(report['task_duration_median_ms'])
        self.assertIsNone(report['wrong_guidance_rate'])

if __name__ == '__main__':
    unittest.main()
