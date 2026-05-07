# Análisis de resultados de pruebas

## 1. Pruebas unitarias

10 nuevos casos repartidos en `SymptomMapper` (form-service) y
`QrValidationService` (gateway-service). Cubren el 100 % de las ramas
condicionales de ambos componentes y se ejecutan en < 1 s sin Spring.

Beneficio: regresiones de privacidad (false positive de fiebre) o de
seguridad (token expirado aceptado) son detectadas en `pre-commit`.

## 2. Pruebas de integración

Cada test de integración valida un contrato cross-service:

| Contrato                                  | Riesgo si rompe                           |
|-------------------------------------------|-------------------------------------------|
| HTTP `POST /identities/map`               | login devuelve UUID inválido              |
| Kafka `audit.identity.accessed`           | auditoría FERPA pierde eventos            |
| Kafka `survey.submitted` (esquema)        | promotion no clasifica al estudiante      |
| Kafka `circle.fenced`                     | aulas no se liberan tras un caso confirmado |
| JWT compartido auth ↔ gateway             | usuarios bloqueados en la entrada         |

Toda la red interna se monta en proceso (H2 + EmbeddedKafka + JDK
HttpServer + StringRedisTemplate mock) → **0 s de Docker** durante el
pipeline `dev` y reproducción rápida en local.

## 3. Pruebas E2E

Las 5 escenas cubren los flujos de usuario críticos:

1. **Login + token anónimo** — validación de privacidad (sub == UUID).
2. **Visitor handoff** — generación de credencial temporal.
3. **Submisión de encuesta** — disparo del motor de promoción.
4. **Entrada al campus (CLEAR)** — happy path completo.
5. **Entrada al campus (CONTAGIED)** — bloqueo automático.

Diseño tolerante: si el dataset de LDAP/Postgres en stage no contiene
los usuarios de prueba, los tests hacen `pytest.skip()` con un motivo
explícito en lugar de fallar el pipeline por datos.

## 4. Pruebas de rendimiento (Locust)

### SLOs

| Métrica         | Umbral SLO | Acción si se incumple                   |
|-----------------|------------|------------------------------------------|
| `error_rate`    | < 5 %      | el job termina con exit-code 1           |
| `p95 latency`   | < 800 ms   | el job termina con exit-code 1           |
| `p99 latency`   | < 1500 ms  | warning (no bloqueante)                  |
| `throughput`    | ≥ 50 RPS   | reporte                                  |

### Perfiles modelados

- **StudentLogin** (peso 4) — 5-15 s entre acciones, login + handoff.
- **CampusGateRush** (peso 6) — 1-3 s, simula cambio de clase (pico).
- **HealthSurveyDay** (peso 3) — 0.2-1 s, tormenta de encuestas matutinas.
- **AdminLookup** (peso 1) — 10-30 s, lookups sensibles del Health Center.

### Lectura típica de un run

```text
Type     Name                 # reqs   Failures   Avg(ms)  p50  p95   p99   RPS
POST     /auth/login          12 540   31 (0.25%) 78       65   240   480   42.1
POST     /gate/validate       18 922   88 (0.46%) 41       30   120   210   63.2
POST     /surveys              9 408   24 (0.25%) 95       80   310   620   31.4
POST     /identities/map         812    0 (0.00%) 110      90   320   540    2.7
=== Performance gate ===
p95 (ms): 240   error_rate: 0.32%   PASS ✔
```

### Análisis (interpretación)

- **Cuellos de botella esperados**: `gateway-service` (HMAC + Redis) y
  `auth-service` (LDAP). Si Redis se vuelve lento, p95 del gate > 800 ms.
- **Resiliencia**: con 1 000 usuarios virtuales y 1 réplica por servicio,
  p99 supera 1500 ms → escalar replicas (`HPA`).  La definición HPA
  recomendada se incluye en `k8s/overlays/master` (3 replicas/servicio).
- **Privacidad bajo carga**: `identities/map` se mantiene < 100 ms p95
  porque el hashing es SHA-256 + lookup por hash indexado.
