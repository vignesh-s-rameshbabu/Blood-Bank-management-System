
package com.lifeflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;

public class PatientService {

    public static void registerPatient(Scanner sc) {
        System.out.println("\n--- Patient Registration ---");
        System.out.print("Name: ");
        String name = sc.nextLine();
        System.out.print("Email: ");
        String email = sc.nextLine();
        System.out.print("Password: ");
        String password = sc.nextLine();
        System.out.print("Phone: ");
        String phone = sc.nextLine();
        System.out.print("Blood Group Needed (e.g. A+, O-): ");
        String bloodGroup = sc.nextLine();
        System.out.print("Hospital Name: ");
        String hospital = sc.nextLine();

        try (Connection conn = DBConnection.getConnection()) {
            String userSql = "INSERT INTO users (name, email, password, phone, role) VALUES (?, ?, ?, ?, 'PATIENT')";
            PreparedStatement userStmt = conn.prepareStatement(userSql, Statement.RETURN_GENERATED_KEYS);
            userStmt.setString(1, name);
            userStmt.setString(2, email);
            userStmt.setString(3, password);
            userStmt.setString(4, phone);
            userStmt.executeUpdate();

            ResultSet keys = userStmt.getGeneratedKeys();
            if (keys.next()) {
                int userId = keys.getInt(1);
                 String patientSql = "INSERT INTO patients (user_id, blood_group, hospital) VALUES (?, ?, ?)";
                PreparedStatement patientStmt = conn.prepareStatement(patientSql);
                patientStmt.setInt(1, userId);
                patientStmt.setString(2, bloodGroup);
                patientStmt.setString(3, hospital);
                patientStmt.executeUpdate();
                System.out.println(" Patient registered successfully! User ID: " + userId);
            }
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }

    public static void listPatients() {
        System.out.println("\n--- All Patients ---");
        String sql = "SELECT u.id, u.name, u.email, u.phone, p.blood_group, p.hospital " +
                     "FROM users u JOIN patients p ON u.id = p.user_id ORDER BY u.id";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            System.out.printf("%-5s %-20s %-25s %-15s %-8s %-20s%n",
                    "ID", "Name", "Email", "Phone", "Blood", "Hospital");
            System.out.println("-".repeat(95));
            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("%-5d %-20s %-25s %-15s %-8s %-20s%n",
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("blood_group"),
                        rs.getString("hospital"));
            }
            if (!found) System.out.println("No patients registered yet.");
        } catch (Exception e) {
            System.out.println(" Error: " + e.getMessage());
        }
    }
}
