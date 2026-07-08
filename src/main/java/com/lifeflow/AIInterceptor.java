package com.lifeflow;

import java.sql.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AIInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AIInterceptor.class);


    public static void processNewRequestAsync(int requestId, String bloodGroup, int units, String patientName) {
        CompletableFuture.runAsync(() -> {
            try {
                logger.info(" [AIInterceptor] Intercepted new request " + requestId + ". Fetching donors...");
                
                List<Map<String, Object>> donors = new ArrayList<>();
                Map<Integer, Map<String, Object>> donorDict = new HashMap<>();
                
                try (Connection conn = DBConnection.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT d.donor_id, d.blood_group, d.city, u.name, u.email FROM donors d JOIN users u ON d.user_id = u.id WHERE d.available = TRUE")) {
                    
                    while (rs.next()) {
                        Map<String, Object> d = new HashMap<>();
                        int dId = rs.getInt("donor_id");
                        d.put("donor_id", dId);
                        d.put("blood_group", rs.getString("blood_group"));
                        d.put("city", rs.getString("city"));
                        d.put("name", rs.getString("name"));
                        d.put("email", rs.getString("email"));
                        donors.add(d);
                        donorDict.put(dId, d);
                    }
                }
                
                Map<String, Object> reqInfo = new HashMap<>();
                reqInfo.put("request_id", requestId);
                reqInfo.put("blood_group", bloodGroup);
                reqInfo.put("units", units);
                reqInfo.put("urgency", "NORMAL");

                logger.info(" [AIInterceptor] Routing to AI for optimization...");
                List<Map<String, Object>> matchedDonors = AIClient.optimizeMatchAsync(reqInfo, donors).join();
                
                // Merge AI results with DB details
                for (Map<String, Object> md : matchedDonors) {
                    int dId = (Integer) md.get("donor_id");
                    Map<String, Object> dbDetails = donorDict.get(dId);
                    if (dbDetails != null) {
                        md.put("name", dbDetails.get("name"));
                        md.put("email", dbDetails.get("email"));
                        md.put("city", dbDetails.get("city"));
                        md.put("blood_group", dbDetails.get("blood_group"));
                    }
                }
                
                logger.info(" [AIInterceptor] AI Matching complete. Triggering Notifications...");
                
                // Trigger Donor Notifications for top 3 matches
                int count = 0;
                for (Map<String, Object> md : matchedDonors) {
                    if (count >= 3) break;
                    NotificationService.sendDonorNotification(md, reqInfo);
                    count++;
                }
                
                // Trigger Patient Shortlist
                NotificationService.sendPatientShortlist(reqInfo, matchedDonors, patientName);
                
            } catch (Exception e) {
                logger.error(" [AIInterceptor] Error during asynchronous routing: " + e.getMessage());
            }
        });
    }
}
