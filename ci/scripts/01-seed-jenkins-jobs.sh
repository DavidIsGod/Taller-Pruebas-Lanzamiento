#!/usr/bin/env bash
# ============================================================================
# Creates the three pipeline jobs (dev / stage / master) in the running
# Jenkins container. Idempotent: re-running updates the job configs.
#
# Usage:
#   JENKINS_USER=DavidIsGod JENKINS_TOKEN=<111bb8721381b001a5aa2e480c6cfe072e> ./ci/scripts/01-seed-jenkins-jobs.sh
#
# How to obtain JENKINS_TOKEN:
#   - log in to http://localhost:8080
#   - top-right -> "<your user>" -> Security -> "Add new token"
# ============================================================================
set -euo pipefail

JENKINS_URL="${JENKINS_URL:-http://localhost:8080}"
JENKINS_USER="${JENKINS_USER:?set JENKINS_USER}"
JENKINS_TOKEN="${JENKINS_TOKEN:?set JENKINS_TOKEN (Jenkins API token)}"
REPO_URL="${REPO_URL:-$(git -C "$(dirname "$0")/../.." remote get-url origin 2>/dev/null || echo 'https://github.com/jcmunozf/circle-guard-public.git')}"

CRUMB=$(curl -sS -u "$JENKINS_USER:$JENKINS_TOKEN" \
  "$JENKINS_URL/crumbIssuer/api/json" | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"{d['crumbRequestField']}:{d['crumb']}\")")

job_xml() {
  # $1 = jenkinsfile path inside the repo
  cat <<EOF
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>CircleGuard Taller2 pipeline - $1</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${REPO_URL}</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/master</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>$1</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers/>
</flow-definition>
EOF
}

create_or_update () {
  local name="$1" jenkinsfile="$2"
  if curl -sf -u "$JENKINS_USER:$JENKINS_TOKEN" -H "$CRUMB" \
       "$JENKINS_URL/job/$name/api/json" >/dev/null 2>&1; then
    echo "[update] $name"
    job_xml "$jenkinsfile" | curl -sS -u "$JENKINS_USER:$JENKINS_TOKEN" -H "$CRUMB" \
      -H 'Content-Type: application/xml' -X POST --data-binary @- \
      "$JENKINS_URL/job/$name/config.xml"
  else
    echo "[create] $name"
    job_xml "$jenkinsfile" | curl -sS -u "$JENKINS_USER:$JENKINS_TOKEN" -H "$CRUMB" \
      -H 'Content-Type: application/xml' -X POST --data-binary @- \
      "$JENKINS_URL/createItem?name=$name"
  fi
}

create_or_update circleguard-dev    ci/dev/Jenkinsfile
create_or_update circleguard-stage  ci/stage/Jenkinsfile
create_or_update circleguard-master ci/master/Jenkinsfile

echo
echo "Seed complete. Jobs:"
echo "  $JENKINS_URL/job/circleguard-dev/"
echo "  $JENKINS_URL/job/circleguard-stage/"
echo "  $JENKINS_URL/job/circleguard-master/"
