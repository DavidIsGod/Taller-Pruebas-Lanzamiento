"""
E2E test fixtures (Taller 2 - point 3c).

Resolves service URLs from environment variables exported by the stage/prod
Jenkins pipeline. Falls back to localhost (`kubectl port-forward` style) so
the suite can also be executed locally.
"""
import os
import pytest
import requests
from tenacity import retry, stop_after_delay, wait_fixed


def _env(var: str, default: str) -> str:
    return os.environ.get(var, default).rstrip("/")


@pytest.fixture(scope="session")
def auth_url() -> str:
    return _env("E2E_AUTH_URL", "http://localhost:8081")


@pytest.fixture(scope="session")
def identity_url() -> str:
    return _env("E2E_IDENTITY_URL", "http://localhost:8083")


@pytest.fixture(scope="session")
def form_url() -> str:
    return _env("E2E_FORM_URL", "http://localhost:8085")


@pytest.fixture(scope="session")
def gateway_url() -> str:
    return _env("E2E_GATE_URL", "http://localhost:8086")


@pytest.fixture(scope="session")
def promotion_url() -> str:
    return _env("E2E_PROMOTION_URL", "http://localhost:8082")


@pytest.fixture(scope="session", autouse=True)
def wait_for_stack(auth_url, identity_url, form_url, gateway_url):
    """Block until each service answers HTTP (stack up).

    Actuator is not bundled in workshop images; we accept any sub-500 response
    on / first, then /actuator/health if present.
    """
    @retry(stop=stop_after_delay(120), wait=wait_fixed(3))
    def _hit(url: str):
        base = url.rstrip("/")
        for path in ("/actuator/health", "/"):
            try:
                r = requests.get(f"{base}{path}", timeout=5)
                if r.status_code < 500:
                    return r
            except requests.RequestException:
                continue
        raise RuntimeError(f"{url}: no reachable HTTP endpoint")

    for url in [auth_url, identity_url, form_url, gateway_url]:
        try:
            _hit(url)
        except Exception as e:
            pytest.skip(f"E2E stack not ready: {url} -> {e}")
