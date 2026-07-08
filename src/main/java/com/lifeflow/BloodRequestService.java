
package com.lifeflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class BloodRequestService {

    public static void createRequest(Scanner sc) {
        System.out.println("\n--- Create Blood Request ---");
        System.out.print("Patient User ID: ");
        int userId;
        try {
            userId = Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println(" Invalid ID.");
            return;
        }
        System.out.print("Blood Group Needed: ");
        String bloodGroup = sc.nextLine();
        System.out.print("Units Required: ");
        int units;
        try {
            units = Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println(" Invalid units.");
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            // Get patient_id from user_id
            PreparedStatement findPatient = conn.prepareStatement(
                    "SELECT patient_id FROM patients WHERE user_id = ?");
            findPatient.setInt(1, userId);
            ResultSet rs = findPatient.executeQuery();
            if (!rs.next()) {
                System.out.println(" No patient found for User ID " + userId);
                return;
            }
            int patientId = rs.getInt("patient_id");

            String sql = "INSERT INTO blood_requests (patient_id, blood_group, units, status) VALUES (?, ?, ?, 'PENDING')";
            PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1, patientId);
            stmt.setString(2, bloodGroup);
            stmt.setInt(3, units);
            stmt.executeUpdate();
            ResultSet keys = stmt.getGeneratedKeys();
            if (keys.next()) {
                int newRequestId = keys.getInt(1);
                System.out.println(" Blood request created! Request ID: " + newRequestId);
                // Fetch patient name for the interceptor
                String patientName = "Unknown";
                PreparedStatement findPatName = conn.prepareStatement("SELECT u.name FROM users u JOIN patients p ON u.id = p.user_id WHERE p.patient_id = ?");
                findPatName.setInt(1, patientId);
                ResultSet rsPat = findPatName.executeQuery();
                if (rsPat.next()) {
                    patientName = rsPat.getString("name");
                }
                
                // Fire and forget AI Interceptor
                AIInterceptor.processNewRequestAsync(newRequestId, bloodGroup, units, patientName);
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void listPendingRequests() {
        System.out.println("\n--- Pending Blood Requests ---");
        String sql = "SELECT br.request_id, u.name AS patient_name, br.blood_group, br.units, br.status, br.created_at " +
                     "FROM blood_requests br " +
                     "JOIN patients p ON br.patient_id = p.patient_id " +
                     "JOIN users u ON p.user_id = u.id " +
                     "WHERE br.status = 'PENDING' ORDER BY br.created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            System.out.printf("%-5s %-20s %-8s %-8s %-12s %-25s%n",
                    "ID", "Patient", "Blood", "Units", "Status", "Created At");
            System.out.println("-".repeat(80));
            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("%-5d %-20s %-8s %-8d %-12s %-25s%n",
                        rs.getInt("request_id"),
                        rs.getString("patient_name"),
                        rs.getString("blood_group"),
                        rs.getInt("units"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at"));
            }
            if (!found) System.out.println("No pending requests.");
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void acceptRequest(Scanner sc) {
        System.out.println("\n--- Accept Blood Request ---");
        int donorUserId;
        int requestId;

        try {
            System.out.print("Donor User ID: ");
            donorUserId = Integer.parseInt(sc.nextLine().trim());
            System.out.print("Request ID to accept: ");
            requestId = Integer.parseInt(sc.nextLine().trim());
        }
        catch (NumberFormatException e) 
        {
            System.out.println(" Invalid input.");
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            // Check if the blood request exists and is PENDING
            PreparedStatement findRequest = conn.prepareStatement(
                    "SELECT status FROM blood_requests WHERE request_id = ?");
            findRequest.setInt(1, requestId);
            ResultSet rsReq = findRequest.executeQuery();
            if (!rsReq.next()) {
                System.out.println(" Error: No blood request found with ID " + requestId);
                return;
            }
            String status = rsReq.getString("status");
            if (!"PENDING".equalsIgnoreCase(status)) {
                System.out.println(" Error: This blood request is already " + status + ".");
                return;
            }

            // Find donor
            PreparedStatement findDonor = conn.prepareStatement(
                    "SELECT donor_id, available FROM donors WHERE user_id = ?");
            findDonor.setInt(1, donorUserId);
            ResultSet rs = findDonor.executeQuery();

            if (!rs.next()) {
                System.out.println(" No donor found for User ID " + donorUserId);
                return;
            }
            int donorId = rs.getInt("donor_id");
            boolean available = rs.getBoolean("available");
            if (!available) {
                System.out.println(" Error: Donor is currently not available for donation.");
                return;
            }

            PreparedStatement insertDonation = conn.prepareStatement(
                    "INSERT INTO donations (donor_id, request_id) VALUES (?, ?)");
            insertDonation.setInt(1, donorId);
            insertDonation.setInt(2, requestId);
            insertDonation.executeUpdate();

            PreparedStatement updateStatus = conn.prepareStatement(
                    "UPDATE blood_requests SET status = 'ACCEPTED' WHERE request_id = ?");
            updateStatus.setInt(1, requestId);
            updateStatus.executeUpdate();

            // Mark donor as unavailable
            PreparedStatement markDonor = conn.prepareStatement(
                    "UPDATE donors SET available = FALSE WHERE donor_id = ?");
            markDonor.setInt(1, donorId);
            markDonor.executeUpdate();

            System.out.println(" Request #" + requestId + " accepted by Donor #" + donorId);
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void markCompleted(Scanner sc) {
        System.out.println("\n--- Mark Donation Completed ---");
        System.out.print("Request ID: ");
        int requestId;
        try {
            requestId = Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println(" Invalid ID.");
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            PreparedStatement updateReq = conn.prepareStatement(
                    "UPDATE blood_requests SET status = 'COMPLETED' WHERE request_id = ?");
            updateReq.setInt(1, requestId);
            int rows = updateReq.executeUpdate();
            if (rows > 0) {
                String sql = "UPDATE donors SET available = TRUE WHERE donor_id = " +
                             "(SELECT donor_id FROM donations WHERE request_id = ?)";
                PreparedStatement updateDonor = conn.prepareStatement(sql);
                updateDonor.setInt(1, requestId);
                updateDonor.executeUpdate();
                System.out.println(" Request #" + requestId + " marked as COMPLETED. Donor is available again.");
            } else {
                System.out.println(" Request not found.");
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void listAllRequests() {
        System.out.println("\n--- All Blood Requests ---");
        String sql = "SELECT br.request_id, u.name AS patient_name, br.blood_group, br.units, br.status, br.created_at " +
                     "FROM blood_requests br " +
                     "JOIN patients p ON br.patient_id = p.patient_id " +
                     "JOIN users u ON p.user_id = u.id " +
                     "ORDER BY br.created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            System.out.printf("%-5s %-20s %-8s %-8s %-12s %-25s%n",
                    "ID", "Patient", "Blood", "Units", "Status", "Created At");
            System.out.println("-".repeat(80));
            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("%-5d %-20s %-8s %-8d %-12s %-25s%n",
                        rs.getInt("request_id"),
                        rs.getString("patient_name"),
                        rs.getString("blood_group"),
                        rs.getInt("units"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at"));
            }
            if (!found) System.out.println("No blood requests found.");
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void predictFutureDemand() {
        System.out.println("\n--- Predicting Future Blood Demand ---");
        String sql = "SELECT blood_group, units, created_at FROM blood_requests";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<Map<String, Object>> histRequests = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> h = new HashMap<>();
                h.put("blood_group", rs.getString("blood_group"));
                h.put("units", rs.getInt("units"));
                h.put("created_at", rs.getTimestamp("created_at").toString());
                histRequests.add(h);
            }

            System.out.println(" Requesting AI demand prediction based on " + histRequests.size() + " historical requests...");
            try {
                Map<String, Object> response = AIClient.predictDemandAsync(histRequests).join();
                Map<String, Integer> predictions = (Map<String, Integer>) response.get("predicted_demand_next_14_days");
                
                System.out.println(" --- AI Demand Forecast (Next 14 Days) --- ");
                System.out.printf("%-15s %-15s%n", "Blood Group", "Predicted Units");
                System.out.println("-".repeat(35));
                if (predictions != null) {
                    for (Map.Entry<String, Integer> entry : predictions.entrySet()) {
                        System.out.printf("%-15s %-15d%n", entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception ex) {
                System.out.println(" Warning: AI Microservice unreachable for demand prediction.");
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }
}
