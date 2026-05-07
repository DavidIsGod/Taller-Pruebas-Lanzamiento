#!/usr/bin/env bash
# ============================================================================
# Taller 2 - point 1: install + configure Docker, Kubernetes (kind) and
# Jenkins. Tested on Ubuntu 22.04 / 24.04 (also works on the lab images).
# Re-runnable: every step is idempotent.
# ============================================================================
set -euo pipefail

log() { printf '\n\033[1;34m[setup]\033[0m %s\n' "$*"; }

# ----------------------------------------------------------------------------
# 1. Docker Engine (CE) + buildx + compose v2
# ----------------------------------------------------------------------------
install_docker() {
  if command -v docker >/dev/null 2>&1; then log "Docker already installed: $(docker -v)"; return; fi
  log "Installing Docker Engine + Buildx + Compose"
  sudo apt-get update -y
  sudo apt-get install -y ca-certificates curl gnupg
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo usermod -aG docker "$USER" || true
  sudo systemctl enable --now docker
  log "Docker installed: $(docker -v)"
}

# ----------------------------------------------------------------------------
# 2. kubectl + kind (lightweight local Kubernetes)
# ----------------------------------------------------------------------------
install_kubectl() {
  if command -v kubectl >/dev/null 2>&1; then log "kubectl already present: $(kubectl version --client --output=json | head -1)"; return; fi
  log "Installing kubectl"
  curl -fsSL "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
    -o /tmp/kubectl
  sudo install -m 0755 /tmp/kubectl /usr/local/bin/kubectl
}

install_kind() {
  if command -v kind >/dev/null 2>&1; then log "kind already present: $(kind version)"; return; fi
  log "Installing kind"
  curl -fsSL https://kind.sigs.k8s.io/dl/latest/kind-linux-amd64 -o /tmp/kind
  sudo install -m 0755 /tmp/kind /usr/local/bin/kind
}

create_cluster() {
  if kind get clusters 2>/dev/null | grep -q '^circleguard$'; then
    log "kind cluster 'circleguard' already exists"; return
  fi
  log "Creating kind cluster 'circleguard' (1 control + 2 workers)"
  cat > /tmp/kind-circleguard.yaml <<'KIND'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: circleguard
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - { containerPort: 80,  hostPort: 80,  protocol: TCP }
      - { containerPort: 443, hostPort: 443, protocol: TCP }
  - role: worker
  - role: worker
containerdConfigPatches:
  - |
    [plugins."io.containerd.grpc.v1.cri".registry.mirrors."registry.local:5000"]
      endpoint = ["http://kind-registry:5000"]
KIND
  # Local image registry for the pipelines.
  if ! docker ps --format '{{.Names}}' | grep -q '^kind-registry$'; then
    docker run -d --restart=always --name kind-registry -p 5000:5000 registry:2
  fi
  kind create cluster --config /tmp/kind-circleguard.yaml
  docker network connect kind kind-registry || true

  kubectl create namespace circleguard-dev   --dry-run=client -o yaml | kubectl apply -f -
  kubectl create namespace circleguard-stage --dry-run=client -o yaml | kubectl apply -f -
  kubectl create namespace circleguard-prod  --dry-run=client -o yaml | kubectl apply -f -
}

# ----------------------------------------------------------------------------
# 3. Jenkins LTS in Docker, wired to the docker socket and to kubectl
# ----------------------------------------------------------------------------
run_jenkins() {
  # Re-use an existing Jenkins container if the user already has one (very
  # common on lab machines). We only inspect the published port; we do not
  # change its configuration.
  if docker ps --format '{{.Names}}' | grep -q '^jenkins$'; then
    JENKINS_PORT=$(docker port jenkins 8080/tcp | awk -F: '{print $2}' | head -1)
    log "Jenkins already running on host port :${JENKINS_PORT:-8080}"
    return
  fi
  log "Starting Jenkins LTS on host port 8080"
  docker volume create jenkins-home >/dev/null
  docker run -d --name jenkins --restart=always \
    -p 8080:8080 -p 50000:50000 \
    -u root \
    -v jenkins-home:/var/jenkins_home \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$HOME/.kube:/root/.kube" \
    jenkins/jenkins:lts-jdk21

  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:8080/login >/dev/null 2>&1; then break; fi
    sleep 2
  done
  log "Initial admin password:"
  docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword || true

  log "Installing Jenkins plugins (workflow, docker, kubernetes, junit, html-publisher)"
  docker exec jenkins jenkins-plugin-cli --plugins \
    workflow-aggregator git docker-workflow kubernetes pipeline-stage-view \
    blueocean junit htmlpublisher configuration-as-code credentials-binding \
    pipeline-utility-steps || true
  docker restart jenkins
}

# ----------------------------------------------------------------------------
# 4. Top-up missing plugins on an existing Jenkins (idempotent)
# ----------------------------------------------------------------------------
ensure_jenkins_plugins() {
  if ! docker ps --format '{{.Names}}' | grep -q '^jenkins$'; then return; fi
  local needed=(workflow-aggregator git docker-workflow junit credentials \
                credentials-binding pipeline-stage-view pipeline-utility-steps \
                kubernetes htmlpublisher)
  log "Verifying Jenkins plugins"
  docker exec jenkins ls /var/jenkins_home/plugins 2>/dev/null \
    | sed 's/.jpi$//; s/.hpi$//' | sort -u > /tmp/_jp.txt
  local missing=()
  for p in "${needed[@]}"; do grep -qx "$p" /tmp/_jp.txt || missing+=("$p"); done
  if [ ${#missing[@]} -eq 0 ]; then
    log "All required Jenkins plugins present"
  else
    log "Installing missing plugins: ${missing[*]}"
    docker exec jenkins jenkins-plugin-cli --plugins "${missing[@]}" || true
    docker restart jenkins
  fi
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
install_docker
install_kubectl
install_kind
create_cluster
run_jenkins
ensure_jenkins_plugins

JENKINS_PORT=$(docker port jenkins 8080/tcp 2>/dev/null | awk -F: '{print $2}' | head -1)
log "DONE. Open Jenkins at http://localhost:${JENKINS_PORT:-8080}"
log "kubectl context : $(kubectl config current-context)"
log "Local registry  : registry.local:5000  (host: localhost:5000)"
