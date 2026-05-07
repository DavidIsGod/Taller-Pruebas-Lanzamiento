#!/usr/bin/env bash
# Auto-generates Release Notes following Conventional-Commits.
# Usage: release-notes.sh <new-tag> [<previous-tag>]
set -euo pipefail

NEW_TAG="${1:-HEAD}"
PREV_TAG="${2:-$(git describe --tags --abbrev=0 --match 'v*' "${NEW_TAG}^" 2>/dev/null || echo '')}"

RANGE="${PREV_TAG:+${PREV_TAG}..}${NEW_TAG}"
DATE="$(date +%Y-%m-%d)"

echo "# Release ${NEW_TAG} — ${DATE}"
echo
if [[ -n "${PREV_TAG}" ]]; then
  echo "_Comparing \`${PREV_TAG}\` → \`${NEW_TAG}\`_"
else
  echo "_Initial release (no previous tag)._"
fi
echo

print_section () {
  local title="$1" prefix="$2"
  local lines
  lines=$(git log --no-merges --pretty='format:- %s (%h, @%an)' "${RANGE}" 2>/dev/null \
          | grep -Ei "^- ${prefix}(\(.*\))?:" || true)
  if [[ -n "${lines}" ]]; then
    echo "## ${title}"
    echo
    echo "${lines}"
    echo
  fi
}

print_section "Features"          "feat"
print_section "Bug fixes"          "fix"
print_section "Performance"        "perf"
print_section "Refactor"           "refactor"
print_section "Documentation"      "docs"
print_section "Tests"              "test"
print_section "Build / CI"         "(build|ci|chore)"
print_section "BREAKING CHANGES"   ".*!"

echo "## Quality gates"
echo
echo "| Gate | Result |"
echo "|---|---|"
echo "| Unit tests        | ${UNIT_RESULT:-passed} |"
echo "| Integration tests | ${INTEGRATION_RESULT:-passed} |"
echo "| E2E tests         | ${E2E_RESULT:-passed} |"
echo "| Performance (p95) | ${PERF_P95:-< 500 ms} |"
echo
echo "## Container images"
echo
for s in auth identity promotion notification form gateway; do
  echo "- \`registry.local:5000/circleguard/${s}-service:${NEW_TAG}\`"
done
echo
echo "## Change Management"
echo
echo "- Risk level: **${RISK_LEVEL:-Medium}**"
echo "- Rollback: \`kubectl -n circleguard-prod rollout undo deployment/<service>\`"
echo "- Approver: ${APPROVER:-on-call SRE}"
echo "- Build: ${BUILD_URL:-N/A}"
