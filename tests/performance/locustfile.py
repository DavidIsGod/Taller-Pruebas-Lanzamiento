"""
LOCUST PERFORMANCE & STRESS TESTS (Taller 2 - point 3d).

Models four real campus user journeys against the deployed CircleGuard stack:

  1. StudentLogin     -> login + QR generation (sustained traffic, 7am-10pm).
  2. CampusGateRush   -> QR validation peaks during class-change minutes.
  3. HealthSurveyDay  -> mass survey submission on a contagion-alert day.
  4. AdminLookup      -> very low-rate but high-cost identity vault lookups.

Run examples:

  # Smoke
  locust -f tests/performance/locustfile.py --host=http://localhost:8081 \
         --headless -u 20 -r 5 -t 1m --csv=build/locust

  # Load
  locust -f tests/performance/locustfile.py --headless -u 200 -r 50 -t 5m \
         --csv=build/locust --html=build/locust.html

  # Stress (find the breaking point)
  locust -f tests/performance/locustfile.py --headless -u 1000 -r 100 -t 10m \
         --csv=build/locust-stress
"""
import os
import random
import time
import uuid

import jwt as pyjwt
from locust import HttpUser, between, events, task

AUTH_URL     = os.environ.get("AUTH_URL",     "http://localhost:8081")
IDENTITY_URL = os.environ.get("IDENTITY_URL", "http://localhost:8083")
FORM_URL     = os.environ.get("FORM_URL",     "http://localhost:8085")
GATEWAY_URL  = os.environ.get("GATEWAY_URL",  "http://localhost:8086")

QR_SECRET = os.environ.get("QR_SECRET", "my-qr-secret-key-for-dev-1234567890")


def _mint_qr_token(anonymous_id: str, lifetime_s: int = 60) -> str:
    return pyjwt.encode(
        {"sub": anonymous_id, "iat": int(time.time()), "exp": int(time.time()) + lifetime_s},
        QR_SECRET,
        algorithm="HS256",
    )


class StudentLogin(HttpUser):
    """Realistic student traffic: log in, then idle. wait_time 5-15 s."""
    host = AUTH_URL
    wait_time = between(5, 15)
    weight = 4

    def on_start(self):
        self.username = f"student.{uuid.uuid4().hex[:8]}"

    @task(3)
    def login(self):
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": self.username, "password": "P@ssw0rd!"},
            name="POST /auth/login",
            catch_response=True,
        ) as r:
            # Both 200 (LDAP exists) and 401 (unknown user) are valid responses
            # for a Spring auth controller. Anything else is a real failure.
            if r.status_code in (200, 401):
                r.success()
            else:
                r.failure(f"unexpected status {r.status_code}")

    @task(1)
    def visitor_handoff(self):
        self.client.post(
            "/api/v1/auth/visitor/handoff",
            json={"anonymousId": str(uuid.uuid4())},
            name="POST /auth/visitor/handoff",
        )


class CampusGateRush(HttpUser):
    """Class-change rush: QR validations every 1-3 s, very high concurrency."""
    host = GATEWAY_URL
    wait_time = between(1, 3)
    weight = 6

    @task
    def validate_qr(self):
        token = _mint_qr_token(str(uuid.uuid4()))
        with self.client.post(
            "/api/v1/gate/validate",
            json={"token": token},
            name="POST /gate/validate",
            catch_response=True,
        ) as r:
            if r.status_code != 200:
                r.failure(f"status {r.status_code}")
            else:
                body = r.json()
                if "valid" not in body or "status" not in body:
                    r.failure("contract violation: missing valid/status")
                else:
                    r.success()


class HealthSurveyDay(HttpUser):
    """Spike scenario: every student submits a daily survey at 6:30am."""
    host = FORM_URL
    wait_time = between(0.2, 1.0)
    weight = 3

    @task
    def submit_survey(self):
        payload = {
            "anonymousId": str(uuid.uuid4()),
            "hasFever":    random.random() < 0.05,
            "hasCough":    random.random() < 0.10,
            "exposureDate": "2026-04-30",
        }
        self.client.post(
            "/api/v1/surveys",
            json=payload,
            name="POST /surveys",
        )


class AdminLookup(HttpUser):
    """Health-Center admin doing identity lookups (low rate, sensitive path)."""
    host = IDENTITY_URL
    wait_time = between(10, 30)
    weight = 1

    @task
    def map_identity(self):
        self.client.post(
            "/api/v1/identities/map",
            json={"realIdentity": f"admin.lookup.{uuid.uuid4().hex[:6]}@u.edu"},
            name="POST /identities/map",
        )


# ---------------------------------------------------------------------------
# Quality gates: fail the run if SLOs are not met.
# ---------------------------------------------------------------------------
@events.quitting.add_listener
def _enforce_slo(environment, **kwargs):
    stats = environment.stats.total
    p95 = stats.get_response_time_percentile(0.95) or 0
    err_ratio = stats.fail_ratio or 0.0

    print(f"\n=== Performance gate ===")
    print(f"requests:  {stats.num_requests}")
    print(f"failures:  {stats.num_failures} ({err_ratio:.2%})")
    print(f"p50 (ms):  {stats.median_response_time}")
    print(f"p95 (ms):  {p95}")
    print(f"p99 (ms):  {stats.get_response_time_percentile(0.99)}")
    print(f"rps avg :  {stats.total_rps:.1f}")
    print("=========================\n")

    if err_ratio > 0.05:
        environment.process_exit_code = 1
        print(f"FAIL: error rate {err_ratio:.2%} exceeds 5% SLO")
    if p95 > 800:
        environment.process_exit_code = 1
        print(f"FAIL: p95 {p95}ms exceeds 800ms SLO")
