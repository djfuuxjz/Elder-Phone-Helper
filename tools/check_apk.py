#!/usr/bin/env python3
"""Inspect a built APK; fail on wrong identity, minimum SDK, signature or corrupt ZIP."""
from pathlib import Path
import hashlib
import json
import re
import zipfile

root = Path(__file__).resolve().parents[1]
apk = root / 'dist/phone-assistant-0.2.0-debug.apk'
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert 'AndroidManifest.xml' in z.namelist() and 'classes.dex' in z.namelist()
badging = (root / 'docs/test-results/apk-badging-0.2.0.txt').read_text()
assert "name='cn.elder.assistant'" in badging
assert "versionCode='9'" in badging and "versionName='0.2.0'" in badging
# aapt2 releases use either sdkVersion or minSdkVersion for the same manifest field.
minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
assert minimum and int(minimum.group(1)) == 30
assert "targetSdkVersion:'35'" in badging
signature = (root / 'docs/test-results/apk-signature-0.2.0.txt').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
report = {'filename':apk.name, 'bytes':apk.stat().st_size, 'sha256':sha,
          'package':'cn.elder.assistant', 'version':'0.2.0', 'min_sdk':30, 'target_sdk':35,
          'signature':'verified_debug', 'zip_integrity':'passed'}
(root / 'docs/test-results/apk-0.2.0.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
(root / 'dist/SHA256SUMS-0.2.0.txt').write_text(sha + '  ' + apk.name + '\n')
print(json.dumps(report, ensure_ascii=False))
