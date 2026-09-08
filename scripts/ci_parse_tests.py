"""TEMPORARY CI-debug helper. Parses unit-test XMLs and prints a one-line
failure excerpt. Deleted once the click-fix CI is green."""
import glob
import xml.etree.ElementTree as ET

out = []
for f in sorted(glob.glob("app/build/test-results/testProdDebugUnitTest/*.xml")):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        for res in list(tc):
            if res.tag in ("failure", "error"):
                msg = (res.get("message") or "").split("\n")[0].strip()
                out.append(
                    (tc.get("classname") or "").split(".")[-1]
                    + "."
                    + (tc.get("name") or "")
                    + ": "
                    + msg
                )
print(" | ".join(out[:2]) or "no failing testcase in xml")
