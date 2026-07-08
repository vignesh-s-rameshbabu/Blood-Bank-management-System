package com.lifeflow;

import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileWriter;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private static final String NOTIF_DIR = "notifications/";

    static {
        try {
            Files.createDirectories(Paths.get(NOTIF_DIR));
        } catch (Exception e) {
            logger.error("Failed to create notifications directory");
        }
    }

        public static void sendDonorNotification(Map<String, Object> donor, Map<String, Object> patientRequest) {
        try {
            int requestId = Integer.parseInt(patientRequest.get("request_id").toString());
            String donorIdStr = donor.get("donor_id").toString();
            int donorId = Integer.parseInt(donorIdStr);
            String donorEmail = donor.get("email") != null ? donor.get("email").toString() : "donor_" + donorIdStr + "@lifeflow.com";
            String donorName = donor.get("name") != null ? donor.get("name").toString() : "Donor " + donorIdStr;
            String requestedGroup = patientRequest.get("blood_group").toString();
            
            String patientLocation = "Unknown";
            if (patientRequest.containsKey("hospital")) {
                patientLocation = patientRequest.get("hospital").toString();
            }
            
            int distance = donor.containsKey("distance") ? Integer.parseInt(donor.get("distance").toString()) : 5;
            int eta = donor.containsKey("eta") ? Integer.parseInt(donor.get("eta").toString()) : 15;
            int score = donor.containsKey("compatibility_score") ? Integer.parseInt(donor.get("compatibility_score").toString()) : 90;
            
            EmailUtility.sendEmailToDonor(requestId, donorId, donorEmail, donorName, requestedGroup, patientLocation, distance, eta, score);
            logger.info(" [NotificationService] Sent urgent email to Donor " + donorIdStr);
        } catch (Exception e) {
            logger.error("Error writing donor email: " + e.getMessage(), e);
        }
    }
    
        public static void sendPatientShortlist(Map<String, Object> patientRequest, List<Map<String, Object>> matchedDonors, String patientName) {
        try {
            int reqId = Integer.parseInt(patientRequest.get("request_id").toString());
            // Construct a fake patient email for now if not provided
            String patientEmail = patientRequest.containsKey("email") ? patientRequest.get("email").toString() : "patient_" + reqId + "@lifeflow.com";
            
            List<Donor> donorsList = new java.util.ArrayList<>();
            for (Map<String, Object> md : matchedDonors) {
                Donor d = new Donor();
                d.id = Integer.parseInt(md.get("donor_id").toString());
                d.name = md.get("name") != null ? md.get("name").toString() : "Unknown";
                d.setBloodGroup(md.get("blood_group").toString());
                donorsList.add(d);
            }
            
            EmailUtility.sendShortlistToPatient(reqId, patientEmail, donorsList);
            logger.info(" [NotificationService] Generated Patient Shortlist for Req " + reqId);
        } catch (Exception e) {
            logger.error("Error writing patient shortlist: " + e.getMessage(), e);
        }
    }
}
