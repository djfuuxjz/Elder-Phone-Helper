#!/usr/bin/env python3
import json
from pathlib import Path
import xml.etree.ElementTree as E
root = Path(__file__).resolve().parents[1]
reports = sorted((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert reports, '没有测试结果，不能报告通过'
suites = []
for file in reports:
    suite = E.parse(file).getroot()
    suites.append({'name': suite.attrib['name'], **{k:int(suite.attrib[k]) for k in ['tests','failures','errors','skipped']}})
totals = {k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']}
assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == totals['skipped'] == 0, totals
report = {'totals':totals, 'suites':suites, 'scope':'本地逻辑、HTTP 模拟服务及 Robolectric 页面测试；不代表真机微信/真实 API 验收'}
(root / 'docs/test-results/summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
