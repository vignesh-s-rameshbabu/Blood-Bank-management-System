package com.lifeflow;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitFilter extends Filter {
    
    // IP address to count mapping
    private static final ConcurrentHashMap<String, RequestData> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 50;
    
    private static class RequestData {
        AtomicInteger count = new AtomicInteger(1);
        long startTime = System.currentTimeMillis();
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        long currentTime = System.currentTimeMillis();
        
        requestCounts.compute(clientIp, (ip, data) -> {
            if (data == null || (currentTime - data.startTime) > 60000) {
                return new RequestData();
            }
            data.count.incrementAndGet();
            return data;
        });
        
        RequestData data = requestCounts.get(clientIp);
        if (data.count.get() > MAX_REQUESTS_PER_MINUTE) {
            String response = "429 Too Many Requests";
            exchange.sendResponseHeaders(429, response.length());
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return; // Reject request
        }
        
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Limits requests to " + MAX_REQUESTS_PER_MINUTE + " per minute per IP";
    }
}
