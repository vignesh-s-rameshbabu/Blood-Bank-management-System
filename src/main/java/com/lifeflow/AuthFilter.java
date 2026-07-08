package com.lifeflow;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

public class AuthFilter extends Filter {

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String sessionId = SessionManager.getSessionIdFromCookie(exchange);
        Map<String, Object> user = SessionManager.getUserFromSession(sessionId);

        if (user == null) {
            // Unauthorized, redirect to login
            exchange.getResponseHeaders().set("Location", "/index.html");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        } else {
            String role = (String) user.get("role");
            String path = exchange.getRequestURI().getPath();

            boolean authorized = true;
            if (path.startsWith("/donor_") || path.startsWith("/accept-request") || path.startsWith("/complete-donation")) {
                if (!"DONOR".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
                    authorized = false;
                }
            } else if (path.startsWith("/patient_") || path.startsWith("/request-blood")) {
                if (!"PATIENT".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
                    authorized = false;
                }
            } else if (path.startsWith("/admin.html")) {
                if (!"ADMIN".equalsIgnoreCase(role)) {
                    authorized = false;
                }
            }

            if (!authorized) {
                String response = "403 Forbidden - Insufficient role privileges";
                exchange.sendResponseHeaders(403, response.length());
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                exchange.close();
                return;
            }

            // User is authenticated and authorized
            exchange.setAttribute("user", user);
            chain.doFilter(exchange);
        }
    }

    @Override
    public String description() {
        return "Ensures that a user is authenticated via Google OAuth before accessing protected endpoints.";
    }
}
