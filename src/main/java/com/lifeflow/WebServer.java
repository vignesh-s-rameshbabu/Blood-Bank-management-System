package com.lifeflow;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public class WebServer {

    private static boolean validateCSRF(HttpExchange exchange, Map<String, String> params) {
        Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
        if (user == null) return false;
        String sessionToken = (String) user.get("csrf_token");
        String requestToken = params.get("csrf_token");
        return sessionToken != null && sessionToken.equals(requestToken);
    }


    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }


    public static void start(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new StaticFileHandler());
            
            // Unprotected endpoints
            server.createContext("/auth/google", new GoogleOAuthHandler.LoginRedirectHandler());
            server.createContext("/auth/google/callback", new GoogleOAuthHandler.CallbackHandler());
            server.createContext("/logout", new LogoutHandler());
            server.createContext("/api/me", new ApiMeHandler()).getFilters().add(new AuthFilter());
            server.createContext("/api/setup-role", new RoleSetupHandler()).getFilters().add(new AuthFilter());

            // Protect dashboards & actions
            server.createContext("/patient_dashboard.html", new ProtectedFileHandler("/patient_dashboard.html")).getFilters().add(new AuthFilter());
            server.createContext("/donor_dashboard.html", new ProtectedFileHandler("/donor_dashboard.html")).getFilters().add(new AuthFilter());
            server.createContext("/setup-role.html", new ProtectedFileHandler("/setup-role.html")).getFilters().add(new AuthFilter());
            server.createContext("/admin.html", new ProtectedFileHandler("/admin.html")).getFilters().add(new AuthFilter());
            server.createContext("/patient-register", new PatientRegisterHandler()).getFilters().add(new AuthFilter());

            server.createContext("/donor-login", new DonorLoginHandler());
            server.createContext("/donor-register", new DonorRegisterHandler());
            server.createContext("/accept-request", new AcceptRequestHandler()).getFilters().add(new RateLimitFilter());
            server.createContext("/complete-donation", new CompleteDonationHandler());
            server.createContext("/update-donor", new UpdateDonorHandler());
            server.createContext("/delete-user", new DeleteUserHandler());
            server.createContext("/delete-request", new DeleteRequestHandler());
            server.createContext("/api/emergency-match", new EmergencyMatchHandler()).getFilters().add(new AuthFilter());
            server.createContext("/api/donor-action", new DonorActionHandler());
            server.createContext("/api/live-status", new LiveStatusHandler());
            server.setExecutor(null); 
            server.start();
            logger.info("[LifeFlow] Web server successfully started at http://localhost:" + port);
        } catch (IOException e) {
            logger.error("[LifeFlow] Failed to start web server: " + e.getMessage());
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
                String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : "";
                params.put(key, value);
            } catch (Exception e) { logger.warn("Exception ignored", e); }
        }
        return params;
    }

    private static void redirect(HttpExchange exchange, String target) throws IOException {
        exchange.getResponseHeaders().set("Location", target);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            if (path.contains("..")) {
                sendResponse(exchange, 400, "text/plain", "Bad Request");
                return;
            }
            
            // Explicitly block direct access to protected static HTML files from the default handler
            if (path.equals("/patient_dashboard.html") || path.equals("/donor_dashboard.html") || path.equals("/setup-role.html") || path.equals("/admin.html")) {
                exchange.getResponseHeaders().set("Location", "/index.html");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            File file = new File("web", path);
            if (!file.exists() || file.isDirectory()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            String contentType = getContentType(path);
            byte[] bytes = Files.readAllBytes(file.toPath());

            if (path.equals("/donor_dashboard.html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = renderDonorDashboard(html, exchange.getRequestURI().getRawQuery());
                Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
                if (user != null && user.containsKey("csrf_token")) {
                    html = html.replace("CSRF_TOKEN_PLACEHOLDER", (String) user.get("csrf_token"));
                }
                bytes = html.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/donor_history.html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = renderDonorHistory(html, exchange.getRequestURI().getRawQuery());
                bytes = html.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/patient_search.html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = renderPatientSearch(html);
                bytes = html.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/admin.html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = renderAdmin(html);
                bytes = html.getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class DonorLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQueryParams(body);
            String email = params.get("email");
            String password = params.get("password");

            if (email == null || password == null) {
                redirect(exchange, "/donor_login.html?error=invalid");
                return;
            }

            try (Connection conn = DBConnection.getConnection()) {
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT u.name, u.password FROM users u JOIN donors d ON u.id = d.user_id WHERE u.email = ?");
                stmt.setString(1, email.trim());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String hashedPw = rs.getString("password");
                    if (hashedPw != null && org.mindrot.jbcrypt.BCrypt.checkpw(password.trim(), hashedPw)) {
                        redirect(exchange, "/donor_dashboard.html?email=" + email.trim());
                    } else {
                        redirect(exchange, "/donor_login.html?error=invalid");
                    }
                } else {
                    redirect(exchange, "/donor_login.html?error=invalid");
                }
            } catch (Exception e) {
                redirect(exchange, "/donor_login.html?error=invalid");
            }
        }
    }

    static class DonorRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQueryParams(body);
            String name = params.get("name");
            String email = params.get("email");
            String password = params.get("password");
            String phone = params.get("phone");
            String blood = params.get("blood");
            String city = params.get("city");
            String ageStr = params.get("age");

            if (name == null || email == null || password == null || blood == null) {
                redirect(exchange, "/donor_register.html?error=missing");
                return;
            }
            if (phone == null || !phone.trim().matches("^[0-9]{10}$")) {
                redirect(exchange, "/donor_register.html?error=invalid_phone");
                return;
            }

            try (Connection conn = DBConnection.getConnection()) {
                PreparedStatement check = conn.prepareStatement("SELECT id FROM users WHERE email = ?");
                check.setString(1, email.trim());
                if (check.executeQuery().next()) {
                    redirect(exchange, "/donor_register.html?error=exists");
                    return;
                }

                String hashedPw = org.mindrot.jbcrypt.BCrypt.hashpw(password.trim(), org.mindrot.jbcrypt.BCrypt.gensalt());
                PreparedStatement userStmt = conn.prepareStatement(
                        "INSERT INTO users (name, email, password, phone, role) VALUES (?, ?, ?, ?, 'DONOR')",
                        Statement.RETURN_GENERATED_KEYS);
                userStmt.setString(1, name.trim());
                userStmt.setString(2, email.trim());
                userStmt.setString(3, hashedPw);
                userStmt.setString(4, phone.trim());
                userStmt.executeUpdate();

                ResultSet keys = userStmt.getGeneratedKeys();
                if (keys.next()) {
                    int userId = keys.getInt(1);
                    int age = 0;
                    if (ageStr != null) {
                        try {
                            age = Integer.parseInt(ageStr.trim());
                        } catch (Exception e) { logger.warn("Exception ignored", e); }
                    }
                    PreparedStatement donorStmt = conn.prepareStatement(
                            "INSERT INTO donors (user_id, blood_group, city, age, available) VALUES (?, ?, ?, ?, TRUE)");
                    donorStmt.setInt(1, userId);
                    donorStmt.setString(2, blood.trim());
                    donorStmt.setString(3, city.trim());
                    donorStmt.setInt(4, age);
                    donorStmt.executeUpdate();

                    redirect(exchange, "/donor_register.html?success=1");
                } else {
                    redirect(exchange, "/donor_register.html?error=missing");
                }
            } catch (Exception e) {
                redirect(exchange, "/donor_register.html?error=missing");
            }
        }
    }

    static class PatientRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
            if (user == null) {
                redirect(exchange, "/index.html");
                return;
            }
            int userId = (Integer) user.get("id");
            String fullName = (String) user.get("name");

            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String blood = params.get("blood");
            String hospital = params.get("hospital");
            String phone = params.get("phone");

            if (blood == null || hospital == null) {
                redirect(exchange, "/patient_dashboard.html?error=missing");
                return;
            }
            if (phone != null && !phone.trim().isEmpty() && !phone.trim().matches("^[0-9]{10}$")) {
                redirect(exchange, "/patient_dashboard.html?error=invalid_phone");
                return;
            }

            int units = 1; // Defaulting units for standard request

            try (Connection conn = DBConnection.getConnection()) {
                int patientId = 0;
                
                // Ensure patient record exists
                PreparedStatement findPatient = conn.prepareStatement("SELECT patient_id FROM patients WHERE user_id = ?");
                findPatient.setInt(1, userId);
                ResultSet rsP = findPatient.executeQuery();
                if (rsP.next()) {
                    patientId = rsP.getInt("patient_id");
                } else {
                    PreparedStatement patientStmt = conn.prepareStatement(
                            "INSERT INTO patients (user_id, blood_group, hospital) VALUES (?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                    patientStmt.setInt(1, userId);
                    patientStmt.setString(2, blood.trim());
                    patientStmt.setString(3, hospital.trim());
                    patientStmt.executeUpdate();
                    ResultSet pKeys = patientStmt.getGeneratedKeys();
                    if (pKeys.next()) {
                        patientId = pKeys.getInt(1);
                    }
                    
                    // Also update the users table to set phone if provided and empty
                    if (phone != null && !phone.trim().isEmpty()) {
                        PreparedStatement updatePhone = conn.prepareStatement("UPDATE users SET phone = ? WHERE id = ? AND (phone IS NULL OR phone = '')");
                        updatePhone.setString(1, phone.trim());
                        updatePhone.setInt(2, userId);
                        updatePhone.executeUpdate();
                    }
                }

                if (patientId > 0) {
                    PreparedStatement reqStmt = conn.prepareStatement(
                            "INSERT INTO blood_requests (patient_id, blood_group, units, status) VALUES (?, ?, ?, 'PENDING')",
                            Statement.RETURN_GENERATED_KEYS);
                    reqStmt.setInt(1, patientId);
                    reqStmt.setString(2, blood.trim());
                    reqStmt.setInt(3, units);
                    reqStmt.executeUpdate();
                    
                    ResultSet reqKeys = reqStmt.getGeneratedKeys();
                    if (reqKeys.next()) {
                        int newRequestId = reqKeys.getInt(1);
                        AIInterceptor.processNewRequestAsync(newRequestId, blood.trim(), units, fullName);
                    }
                    redirect(exchange, "/patient_dashboard.html?success=1");
                } else {
                    redirect(exchange, "/patient_dashboard.html?error=missing");
                }
            } catch (Exception e) {
                redirect(exchange, "/patient_dashboard.html?error=exception");
            }
        }
    }

        static class AcceptRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQueryParams(body);
            if (!validateCSRF(exchange, params)) {
                sendResponse(exchange, 403, "text/plain", "CSRF Token Invalid");
                return;
            }
            String reqIdStr = params.get("requestId");
            String email = params.get("email");

            if (reqIdStr != null && email != null) {
                try (Connection conn = DBConnection.getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        int requestId = Integer.parseInt(reqIdStr);
                        PreparedStatement findDonor = conn.prepareStatement(
                                "SELECT donor_id FROM donors d JOIN users u ON d.user_id = u.id WHERE u.email = ?");
                        findDonor.setString(1, email.trim());
                        ResultSet rs = findDonor.executeQuery();
                        if (rs.next()) {
                            int donorId = rs.getInt("donor_id");

                            PreparedStatement ins = conn.prepareStatement(
                                    "INSERT INTO donations (donor_id, request_id) VALUES (?, ?)");
                            ins.setInt(1, donorId);
                            ins.setInt(2, requestId);
                            ins.executeUpdate();

                            PreparedStatement updReq = conn.prepareStatement(
                                    "UPDATE blood_requests SET status = 'ACCEPTED' WHERE request_id = ?");
                            updReq.setInt(1, requestId);
                            updReq.executeUpdate();

                            PreparedStatement updDon = conn.prepareStatement(
                                    "UPDATE donors SET available = FALSE WHERE donor_id = ?");
                            updDon.setInt(1, donorId);
                            updDon.executeUpdate();
                        }
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        logger.error("Transaction failed, rolled back.", e);
                    }
                } catch (Exception e) { logger.warn("Exception ignored", e); }
            }
            redirect(exchange, "/donor_dashboard.html?email=" + email);
        }
    }

    static class CompleteDonationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String reqIdStr = params.get("requestId");
            String email = params.get("email");

            if (reqIdStr != null && email != null) {
                try (Connection conn = DBConnection.getConnection()) {
                    int requestId = Integer.parseInt(reqIdStr);
                    PreparedStatement updReq = conn.prepareStatement(
                            "UPDATE blood_requests SET status = 'COMPLETED' WHERE request_id = ?");
                    updReq.setInt(1, requestId);
                    updReq.executeUpdate();

                    PreparedStatement findDonor = conn.prepareStatement(
                            "SELECT donor_id FROM donors d JOIN users u ON d.user_id = u.id WHERE u.email = ?");
                    findDonor.setString(1, email.trim());
                    ResultSet rs = findDonor.executeQuery();
                    if (rs.next()) {
                        int donorId = rs.getInt("donor_id");
                        PreparedStatement updDon = conn.prepareStatement(
                                "UPDATE donors SET available = TRUE WHERE donor_id = ?");
                        updDon.setInt(1, donorId);
                        updDon.executeUpdate();
                    }
                } catch (Exception e) { logger.warn("Exception ignored", e); }
            }
            redirect(exchange, "/donor_dashboard.html?email=" + email);
        }
    }

    static class UpdateDonorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String email = params.get("email");
            String name = params.get("name");
            String blood = params.get("blood");
            String ageStr = params.get("age");
            String phone = params.get("phone");
            String city = params.get("city");
            String status = params.get("status");

            if (email == null || name == null) {
                sendResponse(exchange, 400, "text/plain", "Bad Request");
                return;
            }
            if (phone == null || !phone.trim().matches("^[0-9]{10}$")) {
                sendResponse(exchange, 400, "text/plain", "Invalid phone number format");
                return;
            }

            int age = 0;
            if (ageStr != null) {
                try {
                    age = Integer.parseInt(ageStr.trim());
                } catch (Exception e) { logger.warn("Exception ignored", e); }
            }

            boolean available = "Active".equalsIgnoreCase(status);

            try (Connection conn = DBConnection.getConnection()) {
                PreparedStatement userStmt = conn.prepareStatement(
                        "UPDATE users u JOIN donors d ON u.id = d.user_id SET u.name = ?, u.phone = ? WHERE u.email = ?");
                userStmt.setString(1, name.trim());
                userStmt.setString(2, phone.trim());
                userStmt.setString(3, email.trim());
                userStmt.executeUpdate();

                PreparedStatement donorStmt = conn.prepareStatement(
                        "UPDATE donors d JOIN users u ON u.id = d.user_id SET d.blood_group = ?, d.city = ?, d.age = ?, d.available = ? WHERE u.email = ?");
                donorStmt.setString(1, blood.trim());
                donorStmt.setString(2, city.trim());
                donorStmt.setInt(3, age);
                donorStmt.setBoolean(4, available);
                donorStmt.setString(5, email.trim());
                donorStmt.executeUpdate();

                sendResponse(exchange, 200, "text/plain", "SUCCESS");
            } catch (Exception e) {
                sendResponse(exchange, 500, "text/plain", "Error: " + e.getMessage());
            }
        }
    }

    private static String renderDonorDashboard(String html, String query) {
        Map<String, String> params = parseQueryParams(query);
        String email = params.get("email");
        if (email == null || email.trim().isEmpty()) {
            return html;
        }

        String donorName = "Arjun Mehta";
        String bloodGroup = "O+";
        String city = "Chennai, India";
        String phone = "+91 98765 43210";
        int donorId = 0;
        boolean available = true;
        int age = 27;

        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.name, u.phone, d.donor_id, d.blood_group, d.city, d.available, d.age " +
                    "FROM users u " +
                    "JOIN donors d ON u.id = d.user_id " +
                    "WHERE u.email = ?");
            stmt.setString(1, email.trim());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                donorName = rs.getString("name");
                phone = rs.getString("phone");
                donorId = rs.getInt("donor_id");
                bloodGroup = rs.getString("blood_group");
                city = rs.getString("city");
                available = rs.getBoolean("available");
                age = rs.getInt("age");
            }
        } catch (Exception e) { logger.warn("Exception in DB query, using fallback", e); }

        html = html.replace("Arjun Mehta", escapeHtml(donorName));
        html = html.replace("+91 98765 43210", escapeHtml(phone));
        html = html.replace("Chennai, India", city);
        html = html.replace("value=\"Arjun Mehta\"", "value=\"" + escapeHtml(donorName) + "\"");
        html = html.replace("value=\"+91 98765 43210\"", "value=\"" + escapeHtml(phone) + "\"");
        html = html.replace("value=\"Chennai, India\"", "value=\"" + escapeHtml(city) + "\"");
        html = html.replace("value=\"27\"", "value=\"" + age + "\"");
        html = html.replace("placeholder=\"27\"", "placeholder=\"" + age + "\"");

        // Static view replacements
        html = html.replace("id=\"display-blood-val\" class=\"blood-badge\">O+", "id=\"display-blood-val\" class=\"blood-badge\">" + escapeHtml(bloodGroup));
        html = html.replace("id=\"display-age-val\">27", "id=\"display-age-val\">" + age);

        html = html.replace("<option value=\"O+\" selected>", "<option value=\"O+\">");
        html = html.replace("<option value=\"" + escapeHtml(bloodGroup) + "\">", "<option value=\"" + escapeHtml(bloodGroup) + "\" selected>");

        if (available) {
            html = html.replace("<option value=\"Active\" selected>", "<option value=\"Active\" selected>");
            html = html.replace("id=\"display-status-val\" class=\"status-active\">Active", "id=\"display-status-val\" class=\"status-active\">Active");
        } else {
            html = html.replace("<option value=\"Active\" selected>", "<option value=\"Active\">");
            html = html.replace("<option value=\"Inactive\">", "<option value=\"Inactive\" selected>");
            html = html.replace("id=\"display-status-val\" class=\"status-active\">Active", "id=\"display-status-val\" class=\"status-inactive\">Inactive");
        }

        // Replace View History Link
        html = html.replace("href=\"donor_history.html\"", "href=\"donor_history.html?email=" + email.trim() + "\"");

        // Calculate dynamic stats
        int totalDonations = 0;
        int livesImpacted = 0;
        if (donorId > 0) {
            try (Connection conn = DBConnection.getConnection()) {
                PreparedStatement countStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM donations dn JOIN blood_requests br ON dn.request_id = br.request_id WHERE dn.donor_id = ? AND br.status = 'COMPLETED'");
                countStmt.setInt(1, donorId);
                ResultSet rsCount = countStmt.executeQuery();
                if (rsCount.next()) {
                    totalDonations = rsCount.getInt(1);
                    livesImpacted = totalDonations * 3;
                }
            } catch (Exception e) { logger.warn("Exception ignored", e); }
        }

        // Replace stats
        int statDonIndex = html.indexOf("<h3>2</h3>");
        if (statDonIndex != -1) {
            html = html.substring(0, statDonIndex) + "<h3>" + totalDonations + "</h3>" + html.substring(statDonIndex + "<h3>2</h3>".length());
        }
        int statImpIndex = html.indexOf("<h3>6</h3>");
        if (statImpIndex != -1) {
            html = html.substring(0, statImpIndex) + "<h3>" + livesImpacted + "</h3>" + html.substring(statImpIndex + "<h3>6</h3>".length());
        }

        StringBuilder pendingRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement reqs = conn.prepareStatement(
                    "SELECT br.request_id, u.name AS patient_name, br.blood_group, br.units, br.status, br.created_at, p.hospital " +
                    "FROM blood_requests br " +
                    "JOIN patients p ON br.patient_id = p.patient_id " +
                    "JOIN users u ON p.user_id = u.id " +
                    "WHERE br.status = 'PENDING' " +
                    "ORDER BY br.created_at DESC");
            ResultSet rsReqs = reqs.executeQuery();
            while (rsReqs.next()) {
                int reqId = rsReqs.getInt("request_id");
                String patName = rsReqs.getString("patient_name");
                String bg = rsReqs.getString("blood_group");
                int uCount = rsReqs.getInt("units");
                String hosp = rsReqs.getString("hospital");

                pendingRows.append("<tr>")
                        .append("<td>").append(patName).append("</td>")
                        .append("<td><span class=\"blood-badge\">").append(bg).append("</span></td>")
                        .append("<td>").append(hosp).append("</td>")
                        .append("<td>")
                        .append("<form method=\"POST\" action=\"/accept-request\" style=\"display:inline;\">")
                        .append("<input type=\"hidden\" name=\"requestId\" value=\"").append(reqId).append("\">")
                        .append("<input type=\"hidden\" name=\"email\" value=\"").append(escapeHtml(email)).append("\">")
                        .append("<input type=\"hidden\" name=\"csrf_token\" value=\"CSRF_TOKEN_PLACEHOLDER\">")
                        .append("<button type=\"submit\" class=\"dash-donate-btn btn-donate-small\" style=\"border:none; cursor:pointer;\">Accept</button>")
                        .append("</form>")
                        .append("</td>")
                        .append("</tr>");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        if (pendingRows.length() == 0) {
            pendingRows.append("<tr><td colspan=\"4\" style=\"text-align:center; color:rgba(255,255,255,0.6);\">No pending requests found.</td></tr>");
        }

        int tbodyStart = html.indexOf("<tbody id=\"pending-requests-tbody\">");
        if (tbodyStart != -1) {
            int tbodyEnd = html.indexOf("</tbody>", tbodyStart);
            if (tbodyEnd != -1) {
                html = html.substring(0, tbodyStart + "<tbody id=\"pending-requests-tbody\">".length()) + "\n" + pendingRows.toString() + "\n" + html.substring(tbodyEnd);
            }
        }

        StringBuilder acceptedRows = new StringBuilder();
        if (donorId > 0) {
            try (Connection conn = DBConnection.getConnection()) {
                PreparedStatement acc = conn.prepareStatement(
                        "SELECT br.request_id, u.name AS patient_name, u.phone, u.email, p.blood_group, p.hospital, br.status " +
                        "FROM donations dn " +
                        "JOIN blood_requests br ON dn.request_id = br.request_id " +
                        "JOIN patients p ON br.patient_id = p.patient_id " +
                        "JOIN users u ON p.user_id = u.id " +
                        "WHERE dn.donor_id = ? AND br.status = 'ACCEPTED'");
                acc.setInt(1, donorId);
                ResultSet rsAcc = acc.executeQuery();
                while (rsAcc.next()) {
                    int reqId = rsAcc.getInt("request_id");
                    String patName = rsAcc.getString("patient_name");
                    String patPhone = rsAcc.getString("phone");
                    String pEmail = rsAcc.getString("email");
                    String bg = rsAcc.getString("blood_group");
                    String hosp = rsAcc.getString("hospital");
                    String status = rsAcc.getString("status");

                    acceptedRows.append("<tr>")
                            .append("<td>").append(patName).append("</td>")
                            .append("<td><span class=\"blood-badge\">").append(bg).append("</span></td>")
                            .append("<td>").append(hosp).append("</td>")
                            .append("<td>").append(patPhone).append("</td>")
                            .append("<td>").append(pEmail).append("</td>")
                            .append("<td><span class=\"badge-done badge-normal\" style=\"background: rgba(39, 174, 96, 0.15); color: #2ecc71; border: 1px solid rgba(39, 174, 96, 0.3); padding: 3px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 600;\">").append(status).append("</span></td>")
                            .append("<td>")
                            .append("<a href=\"/complete-donation?requestId=").append(reqId).append("&email=").append(email).append("\" class=\"dash-donate-btn btn-donate-small\" style=\"background:linear-gradient(135deg,#27ae60,#2ecc71);box-shadow:0 6px 20px rgba(39,174,96,0.4); text-decoration:none; display:inline-block;\">Complete</a>")
                            .append("</td>")
                            .append("</tr>");
                }
            } catch (Exception e) { logger.warn("Exception ignored", e); }
        }
        if (acceptedRows.length() == 0) {
            acceptedRows.append("<tr><td colspan=\"7\" style=\"text-align:center; color:rgba(255,255,255,0.6);\">No accepted requests in progress.</td></tr>");
        }

        int accTbodyStart = html.indexOf("<tbody id=\"accepted-requests-tbody\">");
        if (accTbodyStart != -1) {
            int accTbodyEnd = html.indexOf("</tbody>", accTbodyStart);
            if (accTbodyEnd != -1) {
                html = html.substring(0, accTbodyStart + "<tbody id=\"accepted-requests-tbody\">".length()) + "\n" + acceptedRows.toString() + "\n" + html.substring(accTbodyEnd);
            }
        }

        return html;
    }

    private static String renderDonorHistory(String html, String query) {
        Map<String, String> params = parseQueryParams(query);
        String email = params.get("email");
        if (email == null || email.trim().isEmpty()) {
            return html;
        }

        // Replace back button to maintain email context
        html = html.replace("href=\"donor_dashboard.html\"", "href=\"donor_dashboard.html?email=" + email.trim() + "\"");

        StringBuilder historyRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement findDonor = conn.prepareStatement(
                    "SELECT d.donor_id FROM donors d JOIN users u ON d.user_id = u.id WHERE u.email = ?");
            findDonor.setString(1, email.trim());
            ResultSet rs = findDonor.executeQuery();
            if (rs.next()) {
                int donorId = rs.getInt("donor_id");

                PreparedStatement hist = conn.prepareStatement(
                        "SELECT dn.donated_at, br.blood_group, br.units, br.status " +
                        "FROM donations dn " +
                        "JOIN blood_requests br ON dn.request_id = br.request_id " +
                        "WHERE dn.donor_id = ? AND br.status = 'COMPLETED' " +
                        "ORDER BY dn.donated_at DESC");
                hist.setInt(1, donorId);
                ResultSet rsHist = hist.executeQuery();
                while (rsHist.next()) {
                    String date = rsHist.getTimestamp("donated_at").toString().substring(0, 10);
                    String bg = rsHist.getString("blood_group");
                    int units = rsHist.getInt("units");
                    String status = rsHist.getString("status");

                    historyRows.append("<tr>")
                            .append("<td>").append(date).append("</td>")
                            .append("<td><span class=\"blood-badge\" style=\"background: linear-gradient(135deg, #e74c3c, #c0392b); color: white; padding: 4px 12px; border-radius: 20px; font-weight: 700; font-size: 0.85rem;\">").append(bg).append("</span></td>")
                            .append("<td>").append(units).append(" Unit").append(units > 1 ? "s" : "").append("</td>")
                            .append("<td><span class=\"badge-done\" style=\"background: rgba(39, 174, 96, 0.15); color: #2ecc71; border: 1px solid rgba(39, 174, 96, 0.3); padding: 3px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 600;\">").append(status).append("</span></td>")
                            .append("</tr>");
                }
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }

        if (historyRows.length() == 0) {
            historyRows.append("<tr><td colspan=\"4\" style=\"text-align:center; color:rgba(255,255,255,0.6);\">No past donations found.</td></tr>");
        }

        int tbodyStart = html.indexOf("<tbody id=\"history-data\">");
        if (tbodyStart != -1) {
            int tbodyEnd = html.indexOf("</tbody>", tbodyStart);
            if (tbodyEnd != -1) {
                html = html.substring(0, tbodyStart + "<tbody id=\"history-data\">".length()) + "\n" + historyRows.toString() + "\n" + html.substring(tbodyEnd);
            }
        }

        return html;
    }

    private static String renderPatientSearch(String html) {
        StringBuilder donorCards = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.name, u.email, u.phone, d.blood_group, d.city " +
                    "FROM users u " +
                    "JOIN donors d ON u.id = d.user_id " +
                    "WHERE d.available = TRUE");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                String email = rs.getString("email");
                String phone = rs.getString("phone");
                String bg = rs.getString("blood_group");
                String city = rs.getString("city");

                donorCards.append("                <div class=\"donor-search-card\" data-blood=\"").append(bg).append("\" data-location=\"").append(city).append("\" data-age-group=\"18-30\">\n")
                        .append("                    <div class=\"card-header-row\">\n")
                        .append("                        <span class=\"card-donor-name\">").append(name).append("</span>\n")
                        .append("                        <span class=\"blood-badge\">").append(bg).append("</span>\n")
                        .append("                    </div>\n")
                        .append("                    <div class=\"card-details-list\">\n")
                        .append("                        <div class=\"card-detail-item\">\n")
                        .append("                            <span>Location</span>\n")
                        .append("                            <span>").append(city).append(", India</span>\n")
                        .append("                        </div>\n")
                        .append("                        <div class=\"card-detail-item\">\n")
                        .append("                            <span>Phone</span>\n")
                        .append("                            <span>").append(phone).append("</span>\n")
                        .append("                        </div>\n")
                        .append("                        <div class=\"card-detail-item\">\n")
                        .append("                            <span>Email</span>\n")
                        .append("                            <span>").append(email).append("</span>\n")
                        .append("                        </div>\n")
                        .append("                        <div class=\"card-detail-item\">\n")
                        .append("                            <span>Status</span>\n")
                        .append("                            <span class=\"badge-done\">Active</span>\n")
                        .append("                        </div>\n")
                        .append("                    </div>\n")
                        .append("                </div>\n");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        if (donorCards.length() == 0) {
            donorCards.append("<p style=\"text-align:center; color:rgba(255,255,255,0.6); grid-column:1/-1; padding: 40px;\">No available donors found.</p>");
        }

        int gridStart = html.indexOf("<div class=\"donor-cards-grid\" id=\"donor-grid\">");
        if (gridStart != -1) {
            int gridEnd = html.indexOf("</div>\n            \n        </div>", gridStart);
            if (gridEnd != -1) {
                html = html.substring(0, gridStart + "<div class=\"donor-cards-grid\" id=\"donor-grid\">".length()) + "\n" + donorCards.toString() + "\n" + html.substring(gridEnd);
            }
        }
        return html;
    }

    private static String renderPatientDashboard(String html) {
        StringBuilder donorRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.name, u.email, u.phone, d.blood_group, d.city " +
                    "FROM users u " +
                    "JOIN donors d ON u.id = d.user_id " +
                    "WHERE d.available = TRUE LIMIT 10");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                String bg = rs.getString("blood_group");
                String city = rs.getString("city");
                
                donorRows.append("                                <tr>\n")
                         .append("                                    <td>").append(name).append("</td>\n")
                         .append("                                    <td><span class=\"badge-blood\">").append(bg).append("</span></td>\n")
                         .append("                                    <td>").append(city).append("</td>\n")
                         .append("                                    <td><span class=\"badge-status\">Available</span></td>\n")
                         .append("                                </tr>\n");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        
        if (donorRows.length() == 0) {
            donorRows.append("                                <tr><td colspan=\"4\" style=\"text-align:center; color:rgba(255,255,255,0.6);\">No available donors found.</td></tr>\n");
        }

        int tbodyStart = html.indexOf("<tbody>");
        if (tbodyStart != -1) {
            int tbodyEnd = html.indexOf("</tbody>", tbodyStart);
            if (tbodyEnd != -1) {
                html = html.substring(0, tbodyStart + "<tbody>".length()) + "\n" + donorRows.toString() + html.substring(tbodyEnd);
            }
        }
        return html;
    }

    private static String renderAdmin(String html) {
        StringBuilder donorsRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.id, u.name, u.email, u.phone, d.blood_group, d.city, d.available, d.age " +
                    "FROM users u JOIN donors d ON u.id = d.user_id ORDER BY u.id");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                donorsRows.append("<tr>")
                        .append("<td>").append(rs.getInt("id")).append("</td>")
                        .append("<td>").append(rs.getString("name")).append("</td>")
                        .append("<td>").append(rs.getString("email")).append("</td>")
                        .append("<td><span class=\"blood-badge\">").append(rs.getString("blood_group")).append("</span></td>")
                        .append("<td>").append(rs.getInt("age")).append("</td>")
                        .append("<td>").append(rs.getString("city")).append(", India</td>")
                        .append("<td>").append(rs.getBoolean("available") ? "Available" : "Unavailable").append("</td>")
                        .append("<td><a href=\"/delete-user?userId=").append(rs.getInt("id")).append("\" class=\"btn-del\" style=\"text-decoration:none; display:inline-block;\">&#128465; Delete</a></td>")
                        .append("</tr>");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        if (donorsRows.length() == 0) {
            donorsRows.append("<tr><td colspan=\"8\" style=\"text-align:center;\">No donors registered.</td></tr>");
        }

        StringBuilder patientsRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.id, u.name, u.email, u.phone, p.blood_group, p.hospital " +
                    "FROM users u JOIN patients p ON u.id = p.user_id ORDER BY u.id");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                patientsRows.append("<tr>")
                        .append("<td>").append(rs.getInt("id")).append("</td>")
                        .append("<td>").append(rs.getString("name")).append("</td>")
                        .append("<td>").append(rs.getString("email")).append("</td>")
                        .append("<td><span class=\"blood-badge\">").append(rs.getString("blood_group")).append("</span></td>")
                        .append("<td>").append(rs.getString("hospital")).append("</td>")
                        .append("<td>").append(rs.getString("phone")).append("</td>")
                        .append("<td><a href=\"/delete-user?userId=").append(rs.getInt("id")).append("\" class=\"btn-del\" style=\"text-decoration:none; display:inline-block;\">&#128465; Delete</a></td>")
                        .append("</tr>");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        if (patientsRows.length() == 0) {
            patientsRows.append("<tr><td colspan=\"7\" style=\"text-align:center;\">No patients registered.</td></tr>");
        }

        StringBuilder requestsRows = new StringBuilder();
        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT br.request_id, u.name AS patient_name, br.blood_group, br.units, br.status, br.created_at, p.hospital " +
                    "FROM blood_requests br " +
                    "JOIN patients p ON br.patient_id = p.patient_id " +
                    "JOIN users u ON p.user_id = u.id " +
                    "ORDER BY br.created_at DESC");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                requestsRows.append("<tr>")
                        .append("<td>").append(rs.getInt("request_id")).append("</td>")
                        .append("<td>").append(rs.getString("patient_name")).append("</td>")
                        .append("<td><span class=\"blood-badge\">").append(rs.getString("blood_group")).append("</span></td>")
                        .append("<td>").append(rs.getString("hospital")).append("</td>")
                        .append("<td>").append(rs.getInt("units")).append(" Unit").append(rs.getInt("units") > 1 ? "s" : "").append("</td>")
                        .append("<td><span class=\"badge-pending\">").append(rs.getString("status")).append("</span></td>")
                        .append("<td>").append(rs.getTimestamp("created_at")).append("</td>")
                        .append("<td><a href=\"/delete-request?requestId=").append(rs.getInt("request_id")).append("\" class=\"btn-del\" style=\"text-decoration:none; display:inline-block;\">&#128465; Delete</a></td>")
                        .append("</tr>");
            }
        } catch (Exception e) { logger.warn("Exception ignored", e); }
        if (requestsRows.length() == 0) {
            requestsRows.append("<tr><td colspan=\"8\" style=\"text-align:center;\">No blood requests found.</td></tr>");
        }

        int donorsTbody = html.indexOf("<tbody id=\"donors-tbody\">");
        if (donorsTbody != -1) {
            int donorsTbodyEnd = html.indexOf("</tbody>", donorsTbody);
            if (donorsTbodyEnd != -1) {
                html = html.substring(0, donorsTbody + "<tbody id=\"donors-tbody\">".length()) + "\n" + donorsRows.toString() + "\n" + html.substring(donorsTbodyEnd);
            }
        }

        int patientsTbody = html.indexOf("<tbody id=\"patients-tbody\">");
        if (patientsTbody != -1) {
            int patientsTbodyEnd = html.indexOf("</tbody>", patientsTbody);
            if (patientsTbodyEnd != -1) {
                html = html.substring(0, patientsTbody + "<tbody id=\"patients-tbody\">".length()) + "\n" + patientsRows.toString() + "\n" + html.substring(patientsTbodyEnd);
            }
        }

        int requestsTbody = html.indexOf("<tbody id=\"requests-tbody\">");
        if (requestsTbody != -1) {
            int requestsTbodyEnd = html.indexOf("</tbody>", requestsTbody);
            if (requestsTbodyEnd != -1) {
                html = html.substring(0, requestsTbody + "<tbody id=\"requests-tbody\">".length()) + "\n" + requestsRows.toString() + "\n" + html.substring(requestsTbodyEnd);
            }
        }

        return html;
    }

    static class DeleteUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String userIdStr = params.get("userId");
            if (userIdStr != null) {
                try (Connection conn = DBConnection.getConnection()) {
                    int userId = Integer.parseInt(userIdStr);
                    PreparedStatement getRole = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
                    getRole.setInt(1, userId);
                    ResultSet rs = getRole.executeQuery();
                    if (rs.next()) {
                        String role = rs.getString("role");
                        if ("DONOR".equalsIgnoreCase(role)) {
                            PreparedStatement getDonor = conn.prepareStatement("SELECT donor_id FROM donors WHERE user_id = ?");
                            getDonor.setInt(1, userId);
                            ResultSet rsDonor = getDonor.executeQuery();
                            if (rsDonor.next()) {
                                int donorId = rsDonor.getInt("donor_id");
                                PreparedStatement delDonations = conn.prepareStatement("DELETE FROM donations WHERE donor_id = ?");
                                delDonations.setInt(1, donorId);
                                delDonations.executeUpdate();
                            }
                            PreparedStatement delDonor = conn.prepareStatement("DELETE FROM donors WHERE user_id = ?");
                            delDonor.setInt(1, userId);
                            delDonor.executeUpdate();
                        } else if ("PATIENT".equalsIgnoreCase(role)) {
                            PreparedStatement getPatient = conn.prepareStatement("SELECT patient_id FROM patients WHERE user_id = ?");
                            getPatient.setInt(1, userId);
                            ResultSet rsPat = getPatient.executeQuery();
                            if (rsPat.next()) {
                                int patientId = rsPat.getInt("patient_id");
                                PreparedStatement delDonations = conn.prepareStatement(
                                        "DELETE FROM donations WHERE request_id IN (SELECT request_id FROM blood_requests WHERE patient_id = ?)");
                                delDonations.setInt(1, patientId);
                                delDonations.executeUpdate();
                                PreparedStatement delRequests = conn.prepareStatement("DELETE FROM blood_requests WHERE patient_id = ?");
                                delRequests.setInt(1, patientId);
                                delRequests.executeUpdate();
                            }
                            PreparedStatement delPatient = conn.prepareStatement("DELETE FROM patients WHERE user_id = ?");
                            delPatient.setInt(1, userId);
                            delPatient.executeUpdate();
                        }
                        PreparedStatement delUser = conn.prepareStatement("DELETE FROM users WHERE id = ?");
                        delUser.setInt(1, userId);
                        delUser.executeUpdate();
                    }
                } catch (Exception e) {
                    logger.error("Exception occurred", e);
                }
            }
            redirect(exchange, "/admin.html");
        }
    }

    static class DeleteRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String reqIdStr = params.get("requestId");
            if (reqIdStr != null) {
                try (Connection conn = DBConnection.getConnection()) {
                    int reqId = Integer.parseInt(reqIdStr);
                    PreparedStatement delDonations = conn.prepareStatement("DELETE FROM donations WHERE request_id = ?");
                    delDonations.setInt(1, reqId);
                    delDonations.executeUpdate();
                    PreparedStatement delRequest = conn.prepareStatement("DELETE FROM blood_requests WHERE request_id = ?");
                    delRequest.setInt(1, reqId);
                    delRequest.executeUpdate();
                } catch (Exception e) {
                    logger.error("Exception occurred", e);
                }
            }
            redirect(exchange, "/admin.html");
        }
    }

    static class EmergencyMatchHandler implements HttpHandler {
        private final ObjectMapper mapper = new ObjectMapper();
        private final MatchingService matchingService = new MatchingService();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    BloodRequest request = mapper.readValue(is, BloodRequest.class);
                    Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
                    int userId = (Integer) user.get("id");
                    
                    int realRequestId = -1;
                    try (Connection conn = DBConnection.getConnection()) {
                        // Ensure patient profile exists
                        PreparedStatement checkPatient = conn.prepareStatement("SELECT patient_id FROM patients WHERE user_id = ?");
                        checkPatient.setInt(1, userId);
                        ResultSet rsPatient = checkPatient.executeQuery();
                        int patientId = -1;
                        if (rsPatient.next()) {
                            patientId = rsPatient.getInt("patient_id");
                        } else {
                            PreparedStatement insertPatient = conn.prepareStatement("INSERT INTO patients (user_id, blood_group, hospital) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                            insertPatient.setInt(1, userId);
                            insertPatient.setString(2, request.getBloodGroup());
                            insertPatient.setString(3, request.getHospital());
                            insertPatient.executeUpdate();
                            ResultSet keys = insertPatient.getGeneratedKeys();
                            if (keys.next()) patientId = keys.getInt(1);
                        }

                        // Insert real blood request
                        PreparedStatement insertReq = conn.prepareStatement("INSERT INTO blood_requests (patient_id, blood_group, units, status) VALUES (?, ?, ?, 'PENDING')", Statement.RETURN_GENERATED_KEYS);
                        insertReq.setInt(1, patientId);
                        insertReq.setString(2, request.getBloodGroup());
                        insertReq.setInt(3, request.getUnits());
                        insertReq.executeUpdate();
                        ResultSet reqKeys = insertReq.getGeneratedKeys();
                        if (reqKeys.next()) realRequestId = reqKeys.getInt(1);
                        
                        request.setRequestId(realRequestId);
                    }
                    
                    final int finalRequestId = realRequestId;
                    // Trigger lifecycle method asynchronously to prevent latency
                    CompletableFuture.runAsync(() -> {
                        try {
                            matchingService.matchAndNotifyForRequest(request);
                        } catch (Exception e) {
                            logger.error("[EmergencyMatch] Error triggering match: " + e.getMessage());
                            logger.error("Exception occurred", e);
                        }
                    });

                    sendResponse(exchange, 202, "application/json", "{\"status\":\"Accepted\",\"requestId\":" + finalRequestId + ",\"message\":\"Emergency match triggered asynchronously.\"}");
                } catch (Exception e) {
                    logger.error("[EmergencyMatch] Invalid request format: " + e.getMessage());
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid request format.\"}");
                }
            } else {
                sendResponse(exchange, 405, "application/json", "{\"error\":\"Method Not Allowed\"}");
            }
        }
    }

    static class ProtectedFileHandler implements HttpHandler {
        private final String path;
        public ProtectedFileHandler(String path) {
            this.path = path;
        }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File file = new File("web", path);
            if (!file.exists()) {
                sendResponse(exchange, 404, "text/plain", "File not found");
                return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            
            if (path.equals("/patient_dashboard.html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = renderPatientDashboard(html);
                bytes = html.getBytes(StandardCharsets.UTF_8);
            }
            
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String sessionId = SessionManager.getSessionIdFromCookie(exchange);
            if (sessionId != null) {
                SessionManager.destroySession(sessionId);
                SessionManager.clearSessionCookie(exchange);
            }
            redirect(exchange, "/index.html");
        }
    }

    static class ApiMeHandler implements HttpHandler {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
            String response = mapper.writeValueAsString(user);
            sendResponse(exchange, 200, "application/json", response);
        }
    }

    static class RoleSetupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method not allowed");
                return;
            }
            
            Map<String, Object> user = (Map<String, Object>) exchange.getAttribute("user");
            int userId = (Integer) user.get("id");
            
            String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQueryParams(query);
            String role = params.get("role");
            String phone = params.get("phone");
            
            if (role != null && (role.equals("PATIENT") || role.equals("DONOR"))) {
                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("UPDATE users SET role = ?, phone = ? WHERE id = ?")) {
                    stmt.setString(1, role);
                    stmt.setString(2, phone);
                    stmt.setInt(3, userId);
                    stmt.executeUpdate();
                    
                    if (role.equals("PATIENT")) {
                        redirect(exchange, "/patient_dashboard.html");
                    } else {
                        redirect(exchange, "/donor_dashboard.html");
                    }
                } catch (Exception e) {
                    logger.error("Exception occurred", e);
                    sendResponse(exchange, 500, "text/plain", "Database error");
                }
            } else {
                sendResponse(exchange, 400, "text/plain", "Invalid role");
            }
        }
    }
    static class LiveStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("requestId=")) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing requestId\"}");
                return;
            }
            Map<String, String> params = parseQueryParams(query);
            int requestId = Integer.parseInt(params.get("requestId"));

            StringBuilder jsonBuilder = new StringBuilder("[");
            try (Connection conn = DBConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT ml.status, u.name as donor_name, ml.notified_at " +
                     "FROM match_logs ml " +
                     "JOIN donors d ON ml.donor_id = d.donor_id " +
                     "JOIN users u ON d.user_id = u.id " +
                     "WHERE ml.request_id = ? ORDER BY ml.notified_at DESC"
                 )) {
                
                stmt.setInt(1, requestId);
                ResultSet rs = stmt.executeQuery();
                boolean first = true;
                while (rs.next()) {
                    if (!first) jsonBuilder.append(",");
                    String status = rs.getString("status");
                    String donorName = rs.getString("donor_name");
                    String obfuscatedName = (donorName != null && donorName.length() > 2) ? donorName.substring(0, 2) + "***" : "***";
                    
                    jsonBuilder.append("{")
                               .append("\"status\":\"").append(status).append("\",")
                               .append("\"donor\":\"").append(obfuscatedName).append("\"")
                               .append("}");
                    first = false;
                }
            } catch (Exception e) {
                logger.error("Exception occurred", e);
            }
            jsonBuilder.append("]");

            sendResponse(exchange, 200, "application/json", jsonBuilder.toString());
        }
    }

    static class DonorActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            int requestId = Integer.parseInt(params.get("requestId"));
            int donorId = Integer.parseInt(params.get("donorId"));
            String action = params.get("action");

            try (Connection conn = DBConnection.getConnection()) {
                if ("ACCEPT".equalsIgnoreCase(action)) {
                    // Lock Check
                    PreparedStatement checkStmt = conn.prepareStatement("SELECT status FROM blood_requests WHERE request_id = ?");
                    checkStmt.setInt(1, requestId);
                    ResultSet rsCheck = checkStmt.executeQuery();
                    if (rsCheck.next()) {
                        String currentStatus = rsCheck.getString("status");
                        if ("ACCEPTED".equals(currentStatus) || "COMPLETED".equals(currentStatus)) {
                            sendHtmlResponse(exchange, "Sorry, this request has already been claimed by another donor.");
                            return;
                        }
                    }

                    // Assign Request
                    PreparedStatement updateReq = conn.prepareStatement("UPDATE blood_requests SET status = 'ACCEPTED' WHERE request_id = ?");
                    updateReq.setInt(1, requestId);
                    updateReq.executeUpdate();

                    PreparedStatement insertDonation = conn.prepareStatement("INSERT INTO donations (donor_id, request_id) VALUES (?, ?)");
                    insertDonation.setInt(1, donorId);
                    insertDonation.setInt(2, requestId);
                    insertDonation.executeUpdate();

                    PreparedStatement updateLog = conn.prepareStatement("UPDATE match_logs SET status = 'ACCEPTED', responded_at = CURRENT_TIMESTAMP WHERE request_id = ? AND donor_id = ?");
                    updateLog.setInt(1, requestId);
                    updateLog.setInt(2, donorId);
                    updateLog.executeUpdate();
                    
                    // Fetch details to send confirmations
                    PreparedStatement detailsStmt = conn.prepareStatement(
                        "SELECT p_u.email as p_email, p_u.name as p_name, p_u.phone as p_phone, p.hospital, " +
                        "d_u.email as d_email, d_u.name as d_name, d_u.phone as d_phone, d.blood_group, ml.estimated_arrival_mins " +
                        "FROM blood_requests br " +
                        "JOIN patients p ON br.patient_id = p.patient_id " +
                        "JOIN users p_u ON p.user_id = p_u.id " +
                        "JOIN donors d ON d.donor_id = ? " +
                        "JOIN users d_u ON d.user_id = d_u.id " +
                        "JOIN match_logs ml ON ml.request_id = br.request_id AND ml.donor_id = d.donor_id " +
                        "WHERE br.request_id = ?"
                    );
                    detailsStmt.setInt(1, donorId);
                    detailsStmt.setInt(2, requestId);
                    ResultSet rsDetails = detailsStmt.executeQuery();

                    if (rsDetails.next()) {
                        String pEmail = rsDetails.getString("p_email");
                        String pName = rsDetails.getString("p_name");
                        String pPhone = rsDetails.getString("p_phone");
                        String hospital = rsDetails.getString("hospital");
                        
                        String dEmail = rsDetails.getString("d_email");
                        String dName = rsDetails.getString("d_name");
                        String dPhone = rsDetails.getString("d_phone");
                        String dBlood = rsDetails.getString("blood_group");
                        int eta = rsDetails.getInt("estimated_arrival_mins");
                        String trackingId = "TRK-" + requestId + "-" + donorId;

                        EmailUtility.sendConfirmationToPatient(pEmail, pName, dName, dBlood, dPhone, dEmail, eta, trackingId);
                        EmailUtility.sendConfirmationToDonor(dEmail, dName, pName, hospital, pPhone, eta);
                    }

                    sendHtmlResponse(exchange, "Success! You have officially accepted the request. Check your email for patient details and navigation instructions.");

                } else if ("REJECT".equalsIgnoreCase(action)) {
                    PreparedStatement updateLog = conn.prepareStatement("UPDATE match_logs SET status = 'REJECTED', responded_at = CURRENT_TIMESTAMP WHERE request_id = ? AND donor_id = ?");
                    updateLog.setInt(1, requestId);
                    updateLog.setInt(2, donorId);
                    updateLog.executeUpdate();
                    sendHtmlResponse(exchange, "You have rejected the request. Thank you for your time; the AI will find another match.");
                } else {
                    sendResponse(exchange, 400, "text/plain", "Invalid Action");
                }
            } catch (Exception e) {
                logger.error("Exception occurred", e);
                sendResponse(exchange, 500, "text/plain", "Internal Server Error");
            }
        }

        private void sendHtmlResponse(HttpExchange exchange, String message) throws IOException {
            String html = "<html><body style='font-family: Arial, sans-serif; text-align: center; padding: 50px;'>" +
                          "<h2>LifeFlow AI System</h2><p>" + message + "</p></body></html>";
            sendResponse(exchange, 200, "text/html", html);
        }
    }
}
