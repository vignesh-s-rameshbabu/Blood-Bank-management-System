package com.lifeflow;

import java.sql.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.security.SecureRandom;
import com.sun.net.httpserver.HttpExchange;

public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);


    private static final SecureRandom secureRandom = new SecureRandom();

    public static String createSession(int userId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String csrfToken = CSRFManager.generateCSRFToken();
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO sessions (session_id, user_id, csrf_token, expires_at) VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL 7 DAY))")) {
            stmt.setString(1, sessionId);
            stmt.setInt(2, userId);
            stmt.setString(3, csrfToken);
            stmt.executeUpdate();
            return sessionId;
        } catch (Exception e) {
            logger.error("Exception occurred", e);
            return null;
        }
    }

    public static void setSessionCookie(HttpExchange exchange, String sessionId) {
        String cookie = "SESSION_ID=" + sessionId + "; Path=/; HttpOnly; Secure; Max-Age=" + (7 * 24 * 60 * 60) + "; SameSite=Lax";
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public static void clearSessionCookie(HttpExchange exchange) {
        String cookie = "SESSION_ID=; Path=/; HttpOnly; Secure; Max-Age=0; SameSite=Lax";
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public static String getSessionIdFromCookie(HttpExchange exchange) {
        if (!exchange.getRequestHeaders().containsKey("Cookie")) {
            return null;
        }
        for (String cookie : exchange.getRequestHeaders().get("Cookie")) {
            String[] parts = cookie.split(";");
            for (String part : parts) {
                part = part.trim();
                if (part.startsWith("SESSION_ID=")) {
                    return part.substring(11);
                }
            }
        }
        return null;
    }

    public static Map<String, Object> getUserFromSession(String sessionId) {
        if (sessionId == null) return null;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT u.id, u.name, u.email, u.role, u.profile_image, s.csrf_token FROM sessions s " +
                     "JOIN users u ON s.user_id = u.id " +
                     "WHERE s.session_id = ? AND s.expires_at > NOW()")) {
            
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    user.put("id", rs.getInt("id"));
                    user.put("name", rs.getString("name"));
                    user.put("email", rs.getString("email"));
                    user.put("role", rs.getString("role"));
                    user.put("profile_image", rs.getString("profile_image"));
                    user.put("csrf_token", rs.getString("csrf_token"));
                    return user;
                }
            }
        } catch (Exception e) {
            logger.error("Exception occurred", e);
        }
        return null;
    }

    public static void destroySession(String sessionId) {
        if (sessionId == null) return;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Exception occurred", e);
        }
    }
}
