# Taller 2 — Entregable

Documento principal: **[`docs/TALLER2_PROCESO.md`](docs/TALLER2_PROCESO.md)**.

## Quickstart (todo en uno)

```bash
# 1. Setup (Docker + kind + kubectl + Jenkins)
./ci/scripts/00-setup-environment.sh

# 2. Pipeline DEV manual
./gradlew --no-daemon clean test
for s in auth identity promotion notification form gateway; do
  docker build -f services/circleguard-${s}-service/Dockerfile \
    -t registry.local:5000/circleguard/${s}-service:dev .
  docker push registry.local:5000/circleguard/${s}-service:dev
done
kubectl apply -k k8s/overlays/dev

# 3. Pipeline STAGE manual (E2E + Locust)
kubectl apply -k k8s/overlays/stage
pytest tests/e2e -v
locust -f tests/performance/locustfile.py --headless -u 100 -r 20 -t 2m

# 4. Pipeline MASTER manual con release notes
git tag v1.0.0 && ci/scripts/release-notes.sh v1.0.0 > RELEASE_NOTES.md
sed -i "s/TAG_PLACEHOLDER/v1.0.0/g" k8s/overlays/master/kustomization.yaml
kubectl apply -k k8s/overlays/master
```

## Mapa de entregables por punto

| Punto | Entregable                                                                 |
|-------|----------------------------------------------------------------------------|
| 1     | `ci/scripts/00-setup-environment.sh`                                       |
| 2     | `ci/dev/Jenkinsfile` + `services/*/Dockerfile` + `k8s/overlays/dev`        |
| 3a    | `services/circleguard-{form,gateway}-service/src/test/.../*UnitTest*.java` |
| 3b    | `*IT.java` en form, identity, auth, notification, gateway                  |
| 3c    | `tests/e2e/test_user_flows.py`                                             |
| 3d    | `tests/performance/locustfile.py`                                          |
| 4     | `ci/stage/Jenkinsfile` + `k8s/overlays/stage`                              |
| 5     | `ci/master/Jenkinsfile` + `ci/scripts/release-notes.sh`                    |
| 6     | `docs/TALLER2_PROCESO.md`, `docs/architecture.md`, `docs/test-analysis.md` |
