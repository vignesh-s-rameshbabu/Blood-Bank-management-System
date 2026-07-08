package com.lifeflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class GoogleOAuthHandler {

    private static final Logger logger = LoggerFactory.getLogger(GoogleOAuthHandler.class);


    // Load from application.properties
    private static final java.util.Properties oauthProps = new java.util.Properties();
    private static String CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID"; 
    private static String CLIENT_SECRET = "YOUR_GOOGLE_CLIENT_SECRET";
    private static String REDIRECT_URI = "http://localhost:8080/auth/google/callback";
    
    static {
        try (java.io.InputStream input = GoogleOAuthHandler.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                oauthProps.load(input);
            } else {
                logger.warn("application.properties not found for Google OAuth");
            }
        } catch (Exception ex) {
            logger.error("Error loading Google OAuth properties", ex);
        }
        
        String envClientId = System.getenv("GOOGLE_CLIENT_ID");
        CLIENT_ID = envClientId != null ? envClientId : oauthProps.getProperty("google.client.id", CLIENT_ID);
        
        String envClientSecret = System.getenv("GOOGLE_CLIENT_SECRET");
        CLIENT_SECRET = envClientSecret != null ? envClientSecret : oauthProps.getProperty("google.client.secret", CLIENT_SECRET);
        
        String envRedirectUri = System.getenv("GOOGLE_REDIRECT_URI");
        REDIRECT_URI = envRedirectUri != null ? envRedirectUri : oauthProps.getProperty("google.redirect.uri", REDIRECT_URI);
    }

    public static class LoginRedirectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            String oauthUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                    "?client_id=" + CLIENT_ID +
                    "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
                    "&response_type=code" +
                    "&scope=" + URLEncoder.encode("openid email profile", "UTF-8") +
                    "&access_type=online" +
                    "&prompt=select_account";

            exchange.getResponseHeaders().set("Location", oauthUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
    }

    public static class CallbackHandler implements HttpHandler {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String code = params.get("code");

            if (code == null) {
                // If there's an error (e.g. user denied), redirect to index
                exchange.getResponseHeaders().set("Location", "/index.html");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            try {
                // 1. Exchange code for access token
                String tokenEndpoint = "https://oauth2.googleapis.com/token";
                String postData = "client_id=" + CLIENT_ID +
                        "&client_secret=" + CLIENT_SECRET +
                        "&code=" + code +
                        "&grant_type=authorization_code" +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8");

                byte[] postDataBytes = postData.getBytes(StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(tokenEndpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postDataBytes);
                }

                if (conn.getResponseCode() != 200) {
                    throw new RuntimeException("Failed to fetch token");
                }

                JsonNode tokenResponse;
                try (InputStream is = conn.getInputStream()) {
                    tokenResponse = mapper.readTree(is);
                }
                String accessToken = tokenResponse.get("access_token").asText();

                // 2. Fetch User Profile
                HttpURLConnection profileConn = (HttpURLConnection) new URL("https://www.googleapis.com/oauth2/v2/userinfo").openConnection();
                profileConn.setRequestMethod("GET");
                profileConn.setRequestProperty("Authorization", "Bearer " + accessToken);

                if (profileConn.getResponseCode() != 200) {
                    throw new RuntimeException("Failed to fetch profile");
                }

                JsonNode profileResponse;
                try (InputStream is = profileConn.getInputStream()) {
                    profileResponse = mapper.readTree(is);
                }

                String googleId = profileResponse.get("id").asText();
                String email = profileResponse.get("email").asText();
                String name = profileResponse.get("name").asText();
                String picture = profileResponse.has("picture") ? profileResponse.get("picture").asText() : "";
                
                // 3. Upsert user in database
                int userId = -1;
                String role = null;
                boolean isNew = false;
                
                try (Connection dbConn = DBConnection.getConnection()) {
                    // Check if exists
                    try (PreparedStatement stmt = dbConn.prepareStatement("SELECT id, role FROM users WHERE email = ?")) {
                        stmt.setString(1, email);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                userId = rs.getInt("id");
                                role = rs.getString("role");
                                // Update last_login
                                try (PreparedStatement update = dbConn.prepareStatement("UPDATE users SET google_id = ?, profile_image = ?, last_login = NOW() WHERE id = ?")) {
                                    update.setString(1, googleId);
                                    update.setString(2, picture);
                                    update.setInt(3, userId);
                                    update.executeUpdate();
                                }
                            }
                        }
                    }

                    // Create new user if not exists
                    if (userId == -1) {
                        isNew = true;
                        try (PreparedStatement insert = dbConn.prepareStatement("INSERT INTO users (name, email, google_id, profile_image, login_provider) VALUES (?, ?, ?, ?, 'GOOGLE')", Statement.RETURN_GENERATED_KEYS)) {
                            insert.setString(1, name);
                            insert.setString(2, email);
                            insert.setString(3, googleId);
                            insert.setString(4, picture);
                            insert.executeUpdate();
                            try (ResultSet generatedKeys = insert.getGeneratedKeys()) {
                                if (generatedKeys.next()) {
                                    userId = generatedKeys.getInt(1);
                                }
                            }
                        }
                    }
                }

                // 4. Create session
                String sessionId = SessionManager.createSession(userId);
                SessionManager.setSessionCookie(exchange, sessionId);

                // 5. Redirect based on role status
                String redirectUrl = "/patient_dashboard.html";
                if (role == null || role.trim().isEmpty()) {
                    redirectUrl = "/setup-role.html";
                } else if ("ADMIN".equalsIgnoreCase(role)) {
                    redirectUrl = "/admin.html";
                } else if ("DONOR".equalsIgnoreCase(role)) {
                    redirectUrl = "/donor_dashboard.html"; // assuming they have this
                }

                exchange.getResponseHeaders().set("Location", redirectUrl);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();

            } catch (Exception e) {
                logger.error("Exception occurred", e);
                exchange.getResponseHeaders().set("Location", "/index.html?error=auth_failed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> params = new HashMap<>();
            if (query == null || query.isEmpty()) return params;
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                try {
                    String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
                    String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : "";
                    params.put(key, value);
                } catch (UnsupportedEncodingException e) {}
            }
            return params;
        }
    }
}
