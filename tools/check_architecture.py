#!/usr/bin/env python3
"""Verify non-negotiable architectural boundaries against production sources."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
def verify():
    sources = list((ROOT / 'app/src/main/java').rglob('*.kt'))
    prohibited = re.compile(r'\b(dispatchGesture|performGlobalAction|performAction)\s*\(')
    violations = [str(p.relative_to(ROOT)) for p in sources if prohibited.search(p.read_text())]
    assert not violations, f'发现代用户操作路径: {violations}'
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    app = manifest.find('application')
    assert app.get(ANDROID + 'usesCleartextTraffic') == 'false'
    assert app.get(ANDROID + 'allowBackup') == 'false'
    assert app.get(ANDROID + 'dataExtractionRules')
    service = app.find('service')
    assert service.get(ANDROID + 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
    debug = next(a for a in app.findall('activity') if a.get(ANDROID + 'name') == '.ui.DebugActivity')
    assert debug.get(ANDROID + 'exported') == 'false'
    workflow = next(a for a in app.findall('activity') if a.get(ANDROID + 'name') == '.ui.WorkflowActivity')
    assert workflow.get(ANDROID + 'exported') == 'false'
    config = ET.parse(ROOT / 'app/src/main/res/xml/accessibility.xml').getroot()
    assert config.get(ANDROID + 'canTakeScreenshot') == 'true'
    assert config.get(ANDROID + 'canPerformGestures') != 'true'
    permissions = {p.get(ANDROID + 'name') for p in manifest.findall('uses-permission')}
    assert permissions == {'android.permission.INTERNET'}, permissions
    report = {'production_files_scanned': len(sources), 'automatic_actions': 'absent',
              'cleartext_network': 'disabled', 'debug_activity': 'private', 'gesture_capability': 'not_requested', 'result': 'passed'}
    return report
if __name__ == '__main__':
    report = verify()
    (ROOT / 'docs/test-results/architecture.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))
