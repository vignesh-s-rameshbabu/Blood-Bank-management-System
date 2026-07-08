package com.lifeflow;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AIClient {

    private static final Logger logger = LoggerFactory.getLogger(AIClient.class);

    private static String BASE_URL = "http://localhost:8000/ai";
    static {
        String envUrl = System.getenv("AI_SERVICE_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            BASE_URL = envUrl;
        }
    }
    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static CompletableFuture<Map<String, Object>> predictDemandAsync(List<Map<String, Object>> historicalRequests) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("historical_requests", historicalRequests);
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/predict-demand"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenApply(body -> {
                        try {
                            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            throw new RuntimeException("Error parsing JSON", e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static CompletableFuture<List<Map<String, Object>>> optimizeMatchAsync(Map<String, Object> requestDetails, List<Map<String, Object>> donors) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("request", requestDetails);
            payload.put("donors", donors);
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/optimize-match"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenApply(body -> {
                        try {
                            return mapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
                        } catch (Exception e) {
                            throw new RuntimeException("Error parsing JSON", e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static CompletableFuture<Map<String, Object>> evaluateDonorEngagementAsync(int donorId, String lastDonationDate, int totalDonations) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("donor_id", donorId);
            if (lastDonationDate != null) {
                payload.put("last_donation_date", lastDonationDate);
            }
            payload.put("total_donations", totalDonations);
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/donor-engagement"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenApply(body -> {
                        try {
                            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            throw new RuntimeException("Error parsing JSON", e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
