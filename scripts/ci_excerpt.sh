#!/bin/bash
# TEMPORARY CI-debug helper. Emits the first compile error (or the first
# failing testcase) as a workflow ::error:: annotation, readable via the
# public check-runs API without auth. Deleted once the click-fix CI is green.
# Usage: ci_excerpt.sh <gradle-output-file>
OUT_FILE="$1"
MSG=$(grep -m1 -o -E '^e: .*$' "$OUT_FILE" | head -c 500)
if [ -z "$MSG" ]; then
  MSG=$(python3 scripts/ci_parse_tests.py | head -c 500)
fi
if [ -z "$MSG" ]; then
  MSG="unknown failure"
fi
echo "::error ::CI-FAILURE-EXCERPT: $MSG"
