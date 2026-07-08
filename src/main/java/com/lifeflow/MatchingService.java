package com.lifeflow;

import java.sql.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MatchingService {

    private static final Logger logger = LoggerFactory.getLogger(MatchingService.class);


    /**
     * Dynamically identifies valid candidate pools by processing the source blood type against 
     * standard universal donor and cross-match medical matrices. Filters the pool based on 
     * availability and triggers personalized dispatch requests. Constructs and routes an 
     * aggregated clean list to the requesting patient.
     * 
     * @param request The blood request created by the patient.
     * @return List of matched donors.
     */
    public List<Donor> matchAndNotifyForRequest(BloodRequest request) {
        List<Donor> matchedDonors = new ArrayList<>();
        List<String> compatibleBloodGroups = getCompatibleBloodGroups(request.getBloodGroup());
        
        if (compatibleBloodGroups.isEmpty()) {
            logger.info("Invalid blood group in request.");
            return matchedDonors;
        }

        String patientEmail = request.getPatientEmail() != null ? request.getPatientEmail() : "";
        String patientName = request.getPatientName() != null ? request.getPatientName() : "";
        String patientHospital = request.getHospital() != null ? request.getHospital() : "";
        
        try (Connection conn = DBConnection.getConnection()) {
            if (patientEmail.isEmpty()) {
                String patientQuery = "SELECT u.name, u.email, p.hospital FROM blood_requests br " +
                                      "JOIN patients p ON br.patient_id = p.patient_id " +
                                      "JOIN users u ON p.user_id = u.id " +
                                      "WHERE br.request_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(patientQuery)) {
                    stmt.setInt(1, request.getRequestId());
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        patientName = rs.getString("name");
                        patientEmail = rs.getString("email");
                        patientHospital = rs.getString("hospital");
                    }
                }
            }
            
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < compatibleBloodGroups.size(); i++) {
                placeholders.append("?");
                if (i < compatibleBloodGroups.size() - 1) {
                    placeholders.append(",");
                }
            }
            
            String donorQuery = "SELECT d.donor_id, u.id as user_id, u.name, u.email, d.blood_group, d.city " +
                                "FROM donors d JOIN users u ON d.user_id = u.id " +
                                "WHERE d.available = TRUE AND d.blood_group IN (" + placeholders.toString() + ") LIMIT 5";
                                
            try (PreparedStatement stmt = conn.prepareStatement(donorQuery)) {
                for (int i = 0; i < compatibleBloodGroups.size(); i++) {
                    stmt.setString(i + 1, compatibleBloodGroups.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Donor donor = new Donor();
                    donor.id = rs.getInt("user_id");
                    donor.name = rs.getString("name");
                    donor.email = rs.getString("email");
                    donor.setBloodGroup(rs.getString("blood_group"));
                    matchedDonors.add(donor);
                    
                    int donorIdDb = rs.getInt("donor_id");

                    // AI Engine Simulation for geodata and compatibility
                    int distanceKm = 2 + (int)(Math.random() * 15); // 2 to 17 km
                    int eta = distanceKm * 3 + (int)(Math.random() * 10); // Traffic factor
                    int compatibilityScore = donor.getBloodGroup().equals(request.getBloodGroup()) ? 100 : 85;

                    // Log AI Decision to DB
                    PreparedStatement logStmt = conn.prepareStatement(
                        "INSERT INTO match_logs (request_id, donor_id, status, compatibility_score, distance_km, estimated_arrival_mins) VALUES (?, ?, 'EMAIL_SENT', ?, ?, ?)"
                    );
                    logStmt.setInt(1, request.getRequestId());
                    logStmt.setInt(2, donorIdDb);
                    logStmt.setInt(3, compatibilityScore);
                    logStmt.setInt(4, distanceKm);
                    logStmt.setInt(5, eta);
                    logStmt.executeUpdate();
                    
                    // Dispatch Email
                    EmailUtility.sendEmailToDonor(request.getRequestId(), donorIdDb, donor.email, donor.name, request.getBloodGroup(), patientHospital, distanceKm, eta, compatibilityScore);
                }
            }
            
        } catch (Exception e) {
            logger.error("Database error during match and notify: " + e.getMessage());
            logger.error("Exception occurred", e);
        }
        
        if (patientEmail != null && !patientEmail.isEmpty()) {
            EmailUtility.sendShortlistToPatient(request.getRequestId(), patientEmail, matchedDonors);
        }
        
        return matchedDonors;
    }

    /**
     * Cross-match medical matrix identifying universal donors and compatible blood types.
     */
    private List<String> getCompatibleBloodGroups(String patientBloodGroup) {
        if (patientBloodGroup == null) return new ArrayList<>();
        switch (patientBloodGroup.toUpperCase()) {
            case "O-": return Arrays.asList("O-");
            case "O+": return Arrays.asList("O+", "O-");
            case "A-": return Arrays.asList("A-", "O-");
            case "A+": return Arrays.asList("A+", "A-", "O+", "O-");
            case "B-": return Arrays.asList("B-", "O-");
            case "B+": return Arrays.asList("B+", "B-", "O+", "O-");
            case "AB-": return Arrays.asList("AB-", "A-", "B-", "O-");
            case "AB+": return Arrays.asList("AB+", "AB-", "A+", "A-", "B+", "B-", "O+", "O-");
            default: return new ArrayList<>();
        }
    }
}
