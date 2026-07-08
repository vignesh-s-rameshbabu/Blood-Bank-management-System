
package com.lifeflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;
import java.util.Map;

public class DonorService {

    public static void registerDonor(Scanner sc) {
        System.out.println("\n--- Donor Registration ---");
        System.out.print("Name: ");
        String name = sc.nextLine();
        System.out.print("Email: ");
        String email = sc.nextLine();
        System.out.print("Password: ");
        String password = sc.nextLine();
        System.out.print("Phone: ");
        String phone = sc.nextLine();
        System.out.print("Blood Group (e.g. A+, O-): ");
        String bloodGroup = sc.nextLine();
        System.out.print("City: ");
        String city = sc.nextLine();

        try (Connection conn = DBConnection.getConnection()) {

            String userSql = "INSERT INTO users (name, email, password, phone, role) VALUES (?, ?, ?, ?, 'DONOR')";
            PreparedStatement userStmt = conn.prepareStatement(userSql, Statement.RETURN_GENERATED_KEYS);
            userStmt.setString(1, name);
            userStmt.setString(2, email);
            userStmt.setString(3, password);
            userStmt.setString(4, phone);
            userStmt.executeUpdate();

            ResultSet keys = userStmt.getGeneratedKeys();
            if (keys.next()) {
                int userId = keys.getInt(1);
                String donorSql = "INSERT INTO donors (user_id, blood_group, city, available) VALUES (?, ?, ?, TRUE)";
                PreparedStatement donorStmt = conn.prepareStatement(donorSql);
                donorStmt.setInt(1, userId);
                donorStmt.setString(2, bloodGroup);
                donorStmt.setString(3, city);
                donorStmt.executeUpdate();
                System.out.println(" Donor registered successfully! User ID: " + userId);
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void listDonors() {
        System.out.println("\n--- All Donors ---");
        String sql = "SELECT u.id, u.name, u.email, u.phone, d.blood_group, d.city, d.available " +
                     "FROM users u JOIN donors d ON u.id = d.user_id ORDER BY u.id";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            System.out.printf("%-5s %-20s %-25s %-15s %-8s %-15s %-10s%n",
                    "ID", "Name", "Email", "Phone", "Blood", "City", "Available");
            System.out.println("-".repeat(100));
            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("%-5d %-20s %-25s %-15s %-8s %-15s %-10s%n",
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("blood_group"),
                        rs.getString("city"),
                        rs.getBoolean("available") ? "YES" : "NO");
            }
            if (!found) {
                System.out.println("No donors registered yet.");
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void viewDonationHistory(Scanner sc) {
        System.out.println("\n--- Donation History ---");
        System.out.print("Enter Donor User ID: ");
        int userId;
        try {
            userId = Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println("Invalid ID.");
            return;
        }

        String sql = "SELECT dn.donation_id, br.blood_group, br.units, br.status, dn.donated_at " +
                     "FROM donations dn " +
                     "JOIN donors d ON dn.donor_id = d.donor_id " +
                     "JOIN blood_requests br ON dn.request_id = br.request_id " +
                     "WHERE d.user_id = ? ORDER BY dn.donated_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            System.out.printf("%-5s %-10s %-8s %-15s %-25s%n",
                    "ID", "BloodGrp", "Units", "Status", "Donated At");
            System.out.println("-".repeat(70));
            boolean found = false;
            
            int totalDonations = 0;
            String lastDonationDate = null;
            
            while (rs.next()) {
                found = true;
                totalDonations++;
                if (lastDonationDate == null) {
                    lastDonationDate = rs.getTimestamp("donated_at").toString();
                }
                
                System.out.printf("%-5d %-10s %-8d %-15s %-25s%n",
                        rs.getInt("donation_id"),
                        rs.getString("blood_group"),
                        rs.getInt("units"),
                        rs.getString("status"),
                        rs.getTimestamp("donated_at"));
            }
            if (!found) {
                System.out.println("No donation history found for this donor.");
            } else {
                System.out.println("\n Requesting AI Donor Engagement Metrics...");
                try {
                    Map<String, Object> metrics = AIClient.evaluateDonorEngagementAsync(userId, lastDonationDate, totalDonations).join();
                    System.out.println(" --- AI Donor Engagement Metrics --- ");
                    System.out.println(" Eligibility Countdown (Days): " + metrics.get("eligibility_countdown_days"));
                    System.out.println(" Churn Risk (Percent): " + metrics.get("churn_risk_percent") + "%");
                } catch (Exception ex) {
                    System.out.println(" Warning: AI Microservice unreachable for engagement metrics.");
                }
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }
}
