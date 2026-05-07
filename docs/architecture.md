# Arquitectura de comunicación entre los 6 microservicios

```mermaid
flowchart LR
    subgraph campus[Campus user]
        STU[Student / Visitor]
    end

    STU -->|POST /auth/login| AUTH(auth-service :8081)
    AUTH -->|REST POST /identities/map| IDENT(identity-service :8083)

    IDENT -->|Kafka audit.identity.accessed| NOTIF(notification-service)

    STU -->|POST /surveys| FORM(form-service :8085)
    FORM -->|Kafka survey.submitted| PROM(promotion-service :8082)

    PROM -->|Kafka circle.fenced| NOTIF
    PROM -->|SET user:status:*| REDIS[(Redis)]

    STU -->|GET /auth/qr/generate| AUTH
    STU -->|POST /gate/validate| GATE(gateway-service :8086)
    GATE -->|verify JWT secret| AUTH
    GATE -->|GET user:status:*| REDIS
```

| Canal                    | Producer            | Consumer            | Tipo  |
|--------------------------|---------------------|---------------------|-------|
| `audit.identity.accessed`| identity-service    | notification-service| Kafka |
| `survey.submitted`       | form-service        | promotion-service   | Kafka |
| `circle.fenced`          | promotion-service   | notification-service| Kafka |
| `/identities/map`        | auth-service        | identity-service    | REST  |
| `/gate/validate`         | client              | gateway-service     | REST  |
| `user:status:<id>`       | promotion-service   | gateway-service     | Redis |

Las pruebas de integración (punto 3.b) cubren cada uno de estos canales.
