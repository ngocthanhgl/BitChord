#!/bin/bash
# TEMPORARY CI-debug helper. Posts the first compile error (or the first
# failing testcase) as a commit-status description, which is readable via the
# public API without auth. Deleted once the click-fix CI is green.
# Usage: ci_excerpt.sh <gradle-output-file>
OUT_FILE="$1"
MSG=$(grep -m1 -o -E '^e: .*$' "$OUT_FILE" | head -c 130)
if [ -z "$MSG" ]; then
  MSG=$(python3 scripts/ci_parse_tests.py | head -c 130)
fi
if [ -z "$MSG" ]; then
  MSG="unknown failure"
fi
JSON=$(python3 -c "import json,sys; print(json.dumps(sys.argv[1]))" "$MSG")
curl -s -X POST \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${GITHUB_REPOSITORY}/statuses/${GITHUB_SHA}" \
  -d "{\"state\":\"failure\",\"context\":\"ci-failure-excerpt\",\"description\":$JSON}" > /dev/null
