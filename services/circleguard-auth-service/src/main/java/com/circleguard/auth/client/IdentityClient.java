package com.circleguard.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class IdentityClient {
    private final RestTemplate restTemplate = new RestTemplate();

    // Resolved from Spring property `identity.service.url` (binds to the
    // IDENTITY_SERVICE_URL env var injected by the k8s Deployment), so the
    // auth-service can talk to identity-service via the cluster DNS name.
    @Value("${identity.service.url:http://localhost:8083}")
    private String identityBaseUrl;

    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                identityBaseUrl + "/api/v1/identities/map", request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}
