# Taller 2 — Pruebas y Lanzamiento

> Repositorio base: <https://github.com/jcmunozf/circle-guard-public>
> Plataforma: Spring Boot 3 / Java 21 / Gradle / Kotlin DSL.
> Entorno: Docker + kind (Kubernetes local) + Jenkins LTS.

Este documento explica **punto por punto** lo entregado y reúne **todos los
comandos de CLI necesarios** para reproducirlo end-to-end.

---

## Microservicios seleccionados (los 6)

Se eligieron seis microservicios que **se comunican entre sí**:

| # | Microservicio                     | Comunica con                          | Mecanismo            |
|---|-----------------------------------|---------------------------------------|----------------------|
| 1 | `circleguard-auth-service`        | `identity-service`                    | REST (`IdentityClient`) |
| 2 | `circleguard-identity-service`    | `notification-service`, `promotion`   | Kafka `audit.identity.accessed` |
| 3 | `circleguard-form-service`        | `promotion-service`                   | Kafka `survey.submitted` |
| 4 | `circleguard-promotion-service`   | `notification-service`, `gateway`     | Kafka `circle.fenced` + Redis `user:status:*` |
| 5 | `circleguard-notification-service`| consumidor de `circle.fenced` y otros | Kafka                |
| 6 | `circleguard-gateway-service`     | `auth-service`, `promotion-service`   | JWT compartido + Redis |

Diagrama de flujos en `docs/architecture.md` (incluido en el zip).

---

## Punto 1 (10 %) — Configuración de Jenkins, Docker y Kubernetes

Todo automatizado en **`ci/scripts/00-setup-environment.sh`** (ver script).
Resumen de comandos (Ubuntu 22.04 / 24.04):

```bash
# 1. Clonar y posicionarse
git clone https://github.com/jcmunozf/circle-guard-public.git
cd circle-guard-public
git checkout master

# 2. Ejecutar el setup automático (Docker + kind + kubectl + Jenkins)
chmod +x ci/scripts/00-setup-environment.sh
./ci/scripts/00-setup-environment.sh

# 3. Verificación
docker ps                    # debe mostrar 'jenkins' y 'kind-registry'
kubectl get nodes            # debe mostrar 1 control + 2 workers
kubectl get ns | grep circle # debe mostrar circleguard-dev/stage/prod
curl -I http://localhost:8080 # Jenkins UI (8080 por defecto)
```

> **Nota:** si ya tenías Jenkins en `:8080`, el script lo detecta y solo
> instala los plugins faltantes (`pipeline-utility-steps`, `htmlpublisher`,
> `kubernetes`, `pipeline-stage-view`).

### Crear los 3 jobs en Jenkins (un solo comando)

```bash
# 1. Crea un API token en Jenkins:  Manage Jenkins -> Users -> <tu-user>
#    -> Security -> Add new token
# 2. Ejecutá el script de seed:
JENKINS_URL=http://localhost:8080 \
JENKINS_USER=admin \
JENKINS_TOKEN=<paste-here> \
./ci/scripts/01-seed-jenkins-jobs.sh
```
Crea/actualiza `circleguard-dev`, `circleguard-stage` y `circleguard-master`
apuntando a los `Jenkinsfile` correspondientes en este repo.

### Configuración manual equivalente

```bash
# --- Docker -------------------------------------------------------------
sudo apt-get update && sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER && newgrp docker

# --- kubectl ------------------------------------------------------------
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# --- kind (cluster local) ----------------------------------------------
curl -Lo kind https://kind.sigs.k8s.io/dl/latest/kind-linux-amd64
sudo install -m 0755 kind /usr/local/bin/kind
docker run -d --restart=always --name kind-registry -p 5000:5000 registry:2
kind create cluster --config /tmp/kind-circleguard.yaml          # ver script
docker network connect kind kind-registry
kubectl create ns circleguard-dev circleguard-stage circleguard-prod

# --- Jenkins LTS en Docker ---------------------------------------------
docker volume create jenkins-home
docker run -d --name jenkins --restart=always \
  -p 8080:8080 -p 50000:50000 -u root \
  -v jenkins-home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $HOME/.kube:/root/.kube \
  jenkins/jenkins:lts-jdk21

docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
docker exec jenkins jenkins-plugin-cli --plugins \
  workflow-aggregator git docker-workflow kubernetes pipeline-stage-view \
  blueocean junit htmlpublisher configuration-as-code credentials-binding \
  pipeline-utility-steps
docker restart jenkins
```

### Credenciales Jenkins necesarias

En **Manage Jenkins → Credentials → System → Global**:

| ID                | Tipo                | Uso                                     |
|-------------------|---------------------|-----------------------------------------|
| `kubeconfig-dev`  | Secret file         | `KUBECONFIG` para overlay `dev`         |
| `kubeconfig-stage`| Secret file         | `KUBECONFIG` para overlay `stage`       |
| `kubeconfig-prod` | Secret file         | `KUBECONFIG` para overlay `master/prod` |
| `dockerhub-creds` | Username/password   | `docker login registry.local:5000`      |
| `github-token`    | Secret text         | tagging y push de release notes         |

---

## Punto 2 (15 %) — Pipelines de DEV (build + test + deploy)

Pipeline: **`ci/dev/Jenkinsfile`** (multibranch, dispara en cada push).

Etapas:

1. `Checkout`
2. `Static analysis` (ktlint + detekt, no bloqueante en dev)
3. `Unit tests` — `./gradlew test`
4. `Integration tests` — `./gradlew integrationTest -Pgroups=integration`
5. `Build images` — `docker build` por servicio + push a `registry.local:5000`
6. `Deploy to dev (k8s)` — `kubectl apply -k k8s/overlays/dev`
7. `Smoke test` — `curl /actuator/health`

### Comandos manuales equivalentes (sin Jenkins)

```bash
# Compilar todo el monorepo y correr unit + integration
./gradlew --no-daemon clean build

# Construir todas las imágenes Docker (las 6 elegidas)
for s in auth identity promotion notification form gateway; do
  docker build \
    -f services/circleguard-${s}-service/Dockerfile \
    -t registry.local:5000/circleguard/${s}-service:dev .
  docker push registry.local:5000/circleguard/${s}-service:dev
done

# Desplegar la pila completa en el namespace circleguard-dev
kubectl apply -k k8s/overlays/dev
kubectl -n circleguard-dev get pods -w

# Smoke
kubectl -n circleguard-dev port-forward svc/auth-service 8081:8081 &
curl -fsS http://localhost:8081/actuator/health
```

Crear el job en Jenkins:

```bash
# Job multibranch apuntando al repo
# Manage Jenkins -> New Item -> Multibranch Pipeline -> 'circleguard-dev'
# Branch source: GitHub -> repo URL
# Build Configuration: by Jenkinsfile -> ci/dev/Jenkinsfile
```

---

## Punto 3 (30 %) — Pruebas

### 3.a Pruebas Unitarias (≥ 5)

Archivos nuevos:

| # | Test                                    | Servicio               | Archivo |
|---|-----------------------------------------|------------------------|---------|
| 1 | Detección de fiebre (YES) → sintomático | form-service           | `services/circleguard-form-service/src/test/java/com/circleguard/form/service/SymptomMapperUnitTest.java` |
| 2 | Todas las respuestas NO → sano          | form-service           | idem |
| 3 | Mapa de respuestas vacío → sano         | form-service           | idem |
| 4 | Respuestas null → no NPE                | form-service           | idem |
| 5 | Multi-choice "symptoms" → sintomático   | form-service           | idem |
| 6 | QR válido + status CLEAR → GREEN        | gateway-service        | `services/circleguard-gateway-service/src/test/java/com/circleguard/gateway/service/QrValidationServiceUnitTest.java` |
| 7 | Status CONTAGIED → RED                  | gateway-service        | idem |
| 8 | Status POTENTIAL → RED                  | gateway-service        | idem |
| 9 | Token expirado → rechazo                | gateway-service        | idem |
|10 | Token corrupto → rechazo sin excepción  | gateway-service        | idem |

Ejecutar sólo unitarias:

```bash
./gradlew :services:circleguard-form-service:test --tests "*UnitTest*"
./gradlew :services:circleguard-gateway-service:test --tests "*UnitTest*"
```

### 3.b Pruebas de Integración (≥ 5)

Validan **comunicación entre microservicios**:

| # | Test                                                   | Servicios involucrados             | Archivo |
|---|--------------------------------------------------------|------------------------------------|---------|
| 1 | POST `/map` idempotente y persiste                     | identity (full stack)              | `services/circleguard-identity-service/src/test/java/com/circleguard/identity/IdentityVaultEndToEndIT.java` |
| 2 | `lookup` emite evento Kafka `audit.identity.accessed`  | identity → notification (consumer) | idem |
| 3 | Contrato HTTP `IdentityClient` → identity              | auth → identity                    | `services/circleguard-auth-service/src/test/java/com/circleguard/auth/client/IdentityClientHttpIT.java` |
| 4 | Error 5xx upstream se propaga                          | auth → identity                    | idem |
| 5 | Submit survey emite `survey.submitted` (esquema)       | form → promotion                   | `services/circleguard-form-service/src/test/java/com/circleguard/form/integration/SurveySubmissionPublishesPromotionEventIT.java` |
| 6 | `circle.fenced` cancela reserva de aula                | promotion → notification           | `services/circleguard-notification-service/src/test/java/com/circleguard/notification/integration/PromotionToNotificationKafkaIT.java` |
| 7 | Gateway acepta JWT firmado por auth (CLEAR)            | auth ↔ gateway                     | `services/circleguard-gateway-service/src/test/java/com/circleguard/gateway/integration/GatewayValidatesAuthIssuedQrIT.java` |
| 8 | Gateway rechaza usuario CONTAGIED (semilla Redis)      | promotion ↔ gateway                | idem |

Ejecutar sólo integración:

```bash
./gradlew --no-daemon test --tests "*IT" --tests "*Integration*"
```

### 3.c Pruebas E2E (≥ 5)

En `tests/e2e/test_user_flows.py` (Python + pytest + requests):

| # | Flujo de usuario                                | Stack invocado                          |
|---|-------------------------------------------------|-----------------------------------------|
| 1 | Login de estudiante devuelve token anónimo     | auth → identity                         |
| 2 | Visitor handoff genera payload                  | auth                                    |
| 3 | Submisión de encuesta + propagación             | form → Kafka → promotion                |
| 4 | Entrada al campus de usuario CLEAR              | auth → gateway                          |
| 5 | Denegación de entrada para usuario CONTAGIED    | promotion (status) → gateway            |

Ejecutarlas localmente contra el cluster:

```bash
# 1. Port-forwards
kubectl -n circleguard-dev port-forward svc/auth-service     8081:8081 &
kubectl -n circleguard-dev port-forward svc/identity-service 8083:8083 &
kubectl -n circleguard-dev port-forward svc/form-service     8085:8085 &
kubectl -n circleguard-dev port-forward svc/gateway-service  8086:8086 &

# 2. Dependencias y ejecución
python3 -m venv .venv && source .venv/bin/activate
pip install -r tests/e2e/requirements.txt
pytest tests/e2e -v --junitxml=build/e2e-results.xml
```

### 3.d Pruebas de Rendimiento (Locust)

Archivo: `tests/performance/locustfile.py`. Cuatro perfiles de usuario:
`StudentLogin`, `CampusGateRush`, `HealthSurveyDay`, `AdminLookup`.

```bash
pip install -r tests/performance/requirements.txt

# Smoke (1 min)
locust -f tests/performance/locustfile.py --host=http://localhost:8081 \
       --headless -u 20 -r 5 -t 1m --csv=build/locust

# Load (5 min, 200 usuarios)
locust -f tests/performance/locustfile.py --headless -u 200 -r 50 -t 5m \
       --csv=build/locust --html=build/locust.html

# Stress (10 min, 1000 usuarios) — encontrar punto de quiebre
locust -f tests/performance/locustfile.py --headless -u 1000 -r 100 -t 10m \
       --csv=build/locust-stress --html=build/locust-stress.html
```

Métricas reportadas (ver `build/locust*.csv` y HTML):

| Métrica            | SLO          | Significado                          |
|--------------------|--------------|--------------------------------------|
| `p95 latency`      | < 800 ms     | 95 % de las requests                 |
| `error rate`       | < 5 %        | Fallos sobre total de requests       |
| `throughput (RPS)` | reportado    | Requests/seg sostenidas              |
| `failures`         | clasificadas | Por endpoint                         |

El `quitting` listener del `locustfile.py` hace **fail** del job si p95 > 800 ms
o error_rate > 5 %, lo que convierte el test de rendimiento en un quality gate.

---

## Punto 4 (15 %) — Pipelines de STAGE en Kubernetes

Pipeline: **`ci/stage/Jenkinsfile`** (dispara en `release/*`).

Etapas:

1. Checkout
2. Build + unit + integration tests (`./gradlew clean build`)
3. Build & push imágenes con tag `:stage` y `:<git-sha>`
4. `kubectl apply -k k8s/overlays/stage`
5. **E2E contra el cluster stage** (pytest)
6. **Locust** contra el cluster stage (carga real)

Comandos manuales equivalentes:

```bash
# Construir + push tag stage
GIT_SHA=$(git rev-parse --short HEAD)
for s in auth identity promotion notification form gateway; do
  docker build -f services/circleguard-${s}-service/Dockerfile \
    -t registry.local:5000/circleguard/${s}-service:stage \
    -t registry.local:5000/circleguard/${s}-service:${GIT_SHA} .
  docker push registry.local:5000/circleguard/${s}-service:stage
  docker push registry.local:5000/circleguard/${s}-service:${GIT_SHA}
done

# Deploy a stage
kubectl apply -k k8s/overlays/stage
for d in auth-service identity-service promotion-service notification-service form-service gateway-service; do
  kubectl -n circleguard-stage rollout status deployment/$d --timeout=240s
done

# E2E contra stage
export E2E_AUTH_URL=http://$(kubectl -n circleguard-stage get svc auth-service     -o jsonpath='{.spec.clusterIP}'):8081
export E2E_GATE_URL=http://$(kubectl -n circleguard-stage get svc gateway-service  -o jsonpath='{.spec.clusterIP}'):8086
export E2E_FORM_URL=http://$(kubectl -n circleguard-stage get svc form-service     -o jsonpath='{.spec.clusterIP}'):8085
pytest tests/e2e -v

# Locust contra stage
locust -f tests/performance/locustfile.py --headless -u 100 -r 20 -t 2m \
       --host=$E2E_AUTH_URL --csv=build/locust --html=build/locust.html
```

---

## Punto 5 (15 %) — Pipeline de PROD (master) + Release Notes

Pipeline: **`ci/master/Jenkinsfile`** (dispara en tags `v*.*.*`).

Fases (Change Management):

1. Checkout
2. Build + unit tests
3. Integration tests
4. **Validación de pruebas de sistema** (re-ejecuta E2E contra stage)
5. Build & push tags `:vX.Y.Z`, `:<sha>`, `:prod`
6. **Generación de Release Notes** (`ci/scripts/release-notes.sh`)
7. **Aprobación manual** (input gate)
8. Deploy a producción (`kubectl apply -k k8s/overlays/master`)
9. Smoke post-deploy
10. Tag y push del release
11. **Rollback automático** en caso de fallo (`kubectl rollout undo`)

### Generación de Release Notes (Conventional Commits)

```bash
# Manual
git tag v1.2.0
ci/scripts/release-notes.sh v1.2.0 > RELEASE_NOTES.md
cat RELEASE_NOTES.md
```

El script categoriza commits en **Features, Bug fixes, Performance,
Refactor, Docs, Tests, Build/CI, BREAKING CHANGES**, añade tabla de
quality gates, lista de imágenes inmutables y la sección de Change
Management (riesgo, rollback, aprobador).

### Promoción manual end-to-end (sin Jenkins)

```bash
# 1. Crear tag desde main
git tag -a v1.2.0 -m "Release v1.2.0"
git push origin v1.2.0
RELEASE_TAG=v1.2.0

# 2. Build & push imágenes prod
for s in auth identity promotion notification form gateway; do
  docker build -f services/circleguard-${s}-service/Dockerfile \
    -t registry.local:5000/circleguard/${s}-service:${RELEASE_TAG} \
    -t registry.local:5000/circleguard/${s}-service:prod .
  docker push registry.local:5000/circleguard/${s}-service:${RELEASE_TAG}
  docker push registry.local:5000/circleguard/${s}-service:prod
done

# 3. Generar release notes
ci/scripts/release-notes.sh ${RELEASE_TAG} > RELEASE_NOTES.md

# 4. Patchear overlay master con el tag y aplicar
sed -i "s/TAG_PLACEHOLDER/${RELEASE_TAG}/g" k8s/overlays/master/kustomization.yaml
kubectl apply -k k8s/overlays/master

# 5. Esperar el rollout
for d in auth-service identity-service promotion-service notification-service form-service gateway-service; do
  kubectl -n circleguard-prod rollout status deployment/$d --timeout=300s
done

# 6. Rollback (si algo sale mal)
for d in auth-service identity-service promotion-service notification-service form-service gateway-service; do
  kubectl -n circleguard-prod rollout undo deployment/$d
done
```

---

## Punto 6 (15 %) — Documentación y video

- Este archivo (`docs/TALLER2_PROCESO.md`) cubre **toda** la información solicitada.
- `docs/architecture.md` — diagrama de comunicación entre los 6 servicios.
- `docs/test-analysis.md` — análisis de resultados (rendimiento incluido).
- Video (≤ 8 min) — fuera de este zip; debe mostrar:
  1. Ejecución del pipeline `dev` (Jenkins → BlueOcean).
  2. Resultado de los tests unitarios + integración (JUnit report).
  3. Ejecución del pipeline `stage` con E2E + Locust.
  4. Promoción a `master` con Release Notes generadas.
  5. Análisis de resultados de Locust (HTML report).

---

## Apéndice A — Comandos rápidos de operación

```bash
# Estado del cluster
kubectl get pods -A
kubectl get svc  -A
kubectl logs -n circleguard-dev -l app=auth-service --tail=200

# Rebuild local de un solo servicio
SERVICE=promotion
docker build -f services/circleguard-${SERVICE}-service/Dockerfile \
  -t registry.local:5000/circleguard/${SERVICE}-service:dev .
docker push registry.local:5000/circleguard/${SERVICE}-service:dev
kubectl -n circleguard-dev rollout restart deployment/${SERVICE}-service

# Limpieza total
kind delete cluster --name circleguard
docker rm -f jenkins kind-registry
docker volume rm jenkins-home
```

## Apéndice B — Estructura del entregable

```
circle-guard-public/
├── ci/
│   ├── dev/Jenkinsfile          # punto 2
│   ├── stage/Jenkinsfile        # punto 4
│   ├── master/Jenkinsfile       # punto 5
│   └── scripts/
│       ├── 00-setup-environment.sh   # punto 1
│       ├── gen-dockerfiles.sh
│       └── release-notes.sh          # punto 5
├── k8s/
│   ├── base/                    # manifests base
│   └── overlays/{dev,stage,master}/   # kustomize por entorno
├── services/
│   └── circleguard-*-service/
│       ├── Dockerfile           # uno por servicio
│       └── src/test/...         # unit + IT tests añadidos (puntos 3a/3b)
├── tests/
│   ├── e2e/                     # punto 3c
│   └── performance/             # punto 3d (Locust)
└── docs/
    └── TALLER2_PROCESO.md       # este archivo (punto 6)
```
