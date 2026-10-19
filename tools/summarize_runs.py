#!/usr/bin/env python3
"""Summarize exported Debug ZIPs without exposing page contents or API input."""
import argparse
import json
import math
import statistics
import zipfile
from pathlib import Path

def load_run(path):
    path = Path(path)
    if path.is_dir():
        events = (path / 'events.jsonl').read_text()
        annotation = json.loads((path / 'annotation.json').read_text()) if (path / 'annotation.json').exists() else None
    else:
        with zipfile.ZipFile(path) as z:
            events = z.read('events.jsonl').decode('utf-8')
            annotation = json.loads(z.read('annotation.json')) if 'annotation.json' in z.namelist() else None
    # Corrupt lines fail visibly; they must not disappear from the denominator.
    rows = [json.loads(line) for line in events.splitlines() if line.strip()]
    return rows, annotation

def summarize(runs):
    latencies = []
    successful_latencies = []
    calls = 0
    retries = 0
    errors = 0
    annotated = 0
    successes = 0
    wrong = 0
    guidance_times = []
    durations = []
    guidance_count = 0
    for events, annotation in runs:
        for row in events:
            kind, data = row['event'], row['data']
            if kind == 'guidance':
                guidance_count += 1
                if data.get('guidance_latency_ms') is not None:
                    guidance_times.append(data['guidance_latency_ms'])
            if kind == 'request':
                calls += 1
                retries += data.get('attempt', 1) > 1
            if kind == 'response':
                errors += 'error' in data
                if 'latency_ms' in data:
                    latencies.append(data['latency_ms'])
                    if 'error' not in data:
                        successful_latencies.append(data['latency_ms'])
        terminal = next((r['data'] for r in reversed(events) if r['event'] == 'end'), None)
        if terminal and terminal.get('duration_ms') is not None:
            durations.append(terminal['duration_ms'])
        if annotation is not None:
            assert isinstance(annotation['actual_success'], bool)
            assert isinstance(annotation['wrong_guidance'], int) and annotation['wrong_guidance'] >= 0
            annotated += 1
            successes += annotation['actual_success']
            wrong += annotation['wrong_guidance']
    def p95(values):
        return sorted(values)[math.ceil(len(values)*0.95)-1] if values else None
    return {'attempts':len(runs), 'annotated_attempts':annotated, 'unannotated_attempts':len(runs)-annotated,
            'confirmed_successes':successes,
            'success_rate':successes/len(runs) if runs and annotated == len(runs) else None,
            'wrong_guidance':wrong if runs and annotated == len(runs) else None,
            'guidance_count':guidance_count,
            'wrong_guidance_rate':wrong/guidance_count if guidance_count and annotated == len(runs) else None,
            'guidance_latency_samples':len(guidance_times),
            'guidance_latency_median_ms':statistics.median(guidance_times) if guidance_times else None,
            'guidance_latency_p95_ms':p95(guidance_times),
            'task_duration_samples':len(durations),
            'task_duration_median_ms':statistics.median(durations) if durations else None,
            'task_duration_p95_ms':p95(durations),
            'task_duration_max_ms':max(durations) if durations else None,
            'model_calls':calls, 'additional_attempts':retries, 'response_errors':errors,
            'latency_samples':len(latencies), 'latency_median_ms':statistics.median(latencies) if latencies else None,
            'latency_p95_ms':p95(latencies), 'latency_max_ms':max(latencies) if latencies else None,
            'successful_request_p95_ms':p95(successful_latencies)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', nargs='+', help='Debug 导出的 ZIP 或包含 events.jsonl 的目录')
    args = parser.parse_args()
    runs = [load_run(path) for path in args.runs]
    groups = {}
    for run in runs:
        case = run[1].get('case_id','UNANNOTATED') if run[1] else 'UNANNOTATED'
        groups.setdefault(case, []).append(run)
    print(json.dumps({'overall':summarize(runs), 'by_case':{k:summarize(v) for k,v in groups.items()},
                      'note':'有未标注运行时不计算成功率；不要混合不同模型/提示词/设备版本批次。'},ensure_ascii=False,indent=2))
if __name__ == '__main__':
    main()
