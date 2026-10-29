package com.shtong.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors graduated and deleted accounts into the medical-insurance adapter. Pending work is read under the
 * state lock, delivered over HTTP without holding it, and the outcome is recorded in a separate short call.
 */
@Component
final class MedicalLifecycleDispatcher {
    private static final int BATCH_SIZE = 10;
    private final AccountAccessService service;
    private final ObjectMapper json;
    private final String endpoint;
    private final String adapterId;
    private final String adapterSecret;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();

    MedicalLifecycleDispatcher(AccountAccessService service, ObjectMapper json,
            @Value("${shtong.medical-insurance.lifecycle-projection-url:}") String lifecycleUrl,
            @Value("${shtong.medical-insurance.graduation-projection-url:}") String graduationUrl,
            @Value("${shtong.sso.adapter-id:medical-insurance}") String adapterId,
            @Value("${shtong.sso.adapter-secret:}") String adapterSecret) {
        this.service = service;
        this.json = json;
        String primary = Objects.requireNonNullElse(lifecycleUrl, "").trim();
        this.endpoint = primary.isBlank() ? Objects.requireNonNullElse(graduationUrl, "").trim() : primary;
        this.adapterId = adapterId;
        this.adapterSecret = Objects.requireNonNullElse(adapterSecret, "");
    }

    @Scheduled(fixedDelayString = "${shtong.medical-insurance.sync-delay-ms:2000}", initialDelay = 5000)
    public void dispatch() {
        if (endpoint.isBlank()) return;
        for (AccountAccessService.LifecycleProjection projection : service.pendingMedicalLifecycleProjections(BATCH_SIZE)) {
            String requestId = "medical-lifecycle-" + UUID.randomUUID();
            boolean delivered = deliver(projection, requestId);
            service.recordMedicalLifecycleProjection(projection.accountId(), projection.authorizationVersion(), delivered, requestId);
        }
    }

    private boolean deliver(AccountAccessService.LifecycleProjection projection, String requestId) {
        if (adapterSecret.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json").header("X-Shtong-Adapter-Id", adapterId)
                    .header("X-Shtong-Adapter-Secret", adapterSecret).header("X-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(projection.payload()))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && json.readTree(response.body()).path("ok").asBoolean();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        }
    }
}
