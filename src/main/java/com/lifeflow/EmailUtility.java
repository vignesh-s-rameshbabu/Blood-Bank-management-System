package com.lifeflow;

import javax.mail.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.mail.internet.*;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

public class EmailUtility {

    private static final Logger logger = LoggerFactory.getLogger(EmailUtility.class);


    private static Properties mailProps = new Properties();

    static {
        try (InputStream input = EmailUtility.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                logger.error("Sorry, unable to find application.properties");
            } else {
                mailProps.load(input);
            }
        } catch (Exception ex) {
            logger.error("Error loading mail configuration: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static Properties getMailProperties() {
        Properties props = new Properties();
        String host = System.getenv("SMTP_HOST");
        props.put("mail.smtp.host", host != null ? host : mailProps.getProperty("mail.smtp.host", "smtp.gmail.com"));
        
        String port = System.getenv("SMTP_PORT");
        props.put("mail.smtp.port", port != null ? port : mailProps.getProperty("mail.smtp.port", "587"));
        
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        return props;
    }

    private static String getSmtpUser() {
        String user = System.getenv("SMTP_USER");
        return user != null ? user : mailProps.getProperty("mail.user", "");
    }

    private static String getSmtpPassword() {
        String pwd = System.getenv("SMTP_PASSWORD");
        return pwd != null ? pwd : mailProps.getProperty("mail.password", "");
    }
    
    public static String getSmtpFrom() {
        String from = System.getenv("SMTP_FROM");
        return from != null ? from : mailProps.getProperty("mail.from", "system@lifeflow.com");
    }

    private static Session getSession() {
        return Session.getInstance(getMailProperties(), new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(getSmtpUser(), getSmtpPassword());
            }
        });
    }

    public static String getAppUrl() {
        String appUrl = System.getenv("APP_URL");
        return appUrl != null && !appUrl.isEmpty() ? appUrl : "http://localhost:8080";
    }

    public static void sendEmailToDonor(int requestId, int donorId, String donorEmail, String donorName, String requestedGroup, String patientLocation, int distance, int eta, int score) {
        try {
            Session session = getSession();
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getSmtpFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(donorEmail));
            message.setSubject("URGENT: Emergency Blood Request - " + requestedGroup + " Match Found");

            String acceptLink = getAppUrl() + "/api/donor-action?requestId=" + requestId + "&donorId=" + donorId + "&action=ACCEPT";
            String rejectLink = getAppUrl() + "/api/donor-action?requestId=" + requestId + "&donorId=" + donorId + "&action=REJECT";

            String htmlContent = "<html><body style='font-family:\"Inter\", Arial, sans-serif; background-color:#f8fafc; padding:40px; color:#1e293b; margin:0;'>" +
                    "<div style='background:white; border-radius:12px; padding:32px; max-width:600px; margin:auto; box-shadow:0 10px 25px rgba(0,0,0,0.05);'>" +
                    "<div style='text-align:center; padding-bottom:24px; border-bottom:1px solid #e2e8f0; margin-bottom:24px;'>" +
                    "<h2 style='color:#ef4444; margin:0; font-size:24px; font-weight:700;'>LifeFlow AI Dispatch</h2>" +
                    "<p style='color:#64748b; margin-top:8px;'>Emergency Match Detected</p>" +
                    "</div>" +
                    "<p style='font-size:16px;'>Dear <strong>" + donorName + "</strong>,</p>" +
                    "<p style='font-size:16px; line-height:1.6;'>Our AI matching engine has identified you as a <strong style='color:#ef4444;'>" + score + "% compatible</strong> donor for a critical emergency in your area.</p>" +
                    
                    "<div style='background:#f1f5f9; border-radius:8px; padding:20px; margin:24px 0;'>" +
                    "<h3 style='margin-top:0; color:#334155; font-size:14px; text-transform:uppercase; letter-spacing:1px;'>Emergency Details</h3>" +
                    "<table style='width:100%; border-collapse:collapse;'>" +
                    "<tr><td style='padding:8px 0; color:#64748b; width:40%;'>Blood Group Needed</td><td style='padding:8px 0; font-weight:600; color:#ef4444;'>" + requestedGroup + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Location</td><td style='padding:8px 0; font-weight:600;'>" + patientLocation + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Distance</td><td style='padding:8px 0; font-weight:600;'>" + distance + " km</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Est. Arrival Time</td><td style='padding:8px 0; font-weight:600;'>" + eta + " mins</td></tr>" +
                    "</table>" +
                    "</div>" +

                    "<p style='font-size:16px; text-align:center; margin-bottom:24px; font-weight:500;'>Please respond immediately to help save a life.</p>" +
                    
                    "<div style='text-align:center; margin-bottom:32px;'>" +
                    "<a href='" + acceptLink + "' style='display:inline-block; background-color:#10b981; color:white; padding:14px 32px; text-decoration:none; border-radius:6px; font-weight:600; font-size:16px; margin-right:12px;'>Accept Request</a>" +
                    "<a href='" + rejectLink + "' style='display:inline-block; background-color:#fff; color:#64748b; padding:14px 32px; text-decoration:none; border-radius:6px; font-weight:600; font-size:16px; border:1px solid #cbd5e1;'>Reject</a>" +
                    "</div>" +

                    "<div style='text-align:center; padding-top:24px; border-top:1px solid #e2e8f0; font-size:13px; color:#94a3b8;'>" +
                    "<p>Thank you for being a hero.<br/><strong>LifeFlow Automated AI System</strong></p>" +
                    "</div>" +
                    "</div></body></html>";

            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            logEmail(donorEmail, message.getSubject(), "DONOR_DISPATCH");
            logger.info(" [EmailUtility] Successfully sent donor dispatch email to: " + donorEmail);

        } catch (MessagingException e) {
            logger.error(" [EmailUtility] Failed to send email to donor " + donorEmail + ". Reason: " + e.getMessage());
            logger.error("Exception occurred", e);
        }
    }

    public static void sendShortlistToPatient(int requestId, String patientEmail, List<Donor> matchedDonors) {
        try {
            Session session = getSession();
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getSmtpFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(patientEmail));
            message.setSubject("Blood Request Update - AI Matching Digest");

            StringBuilder htmlBuilder = new StringBuilder();
            htmlBuilder.append("<html><body style='font-family:\"Inter\", Arial, sans-serif; background-color:#f8fafc; padding:40px; color:#1e293b; margin:0;'>")
                       .append("<div style='background:white; border-radius:12px; padding:32px; max-width:600px; margin:auto; box-shadow:0 10px 25px rgba(0,0,0,0.05);'>")
                       .append("<div style='text-align:center; padding-bottom:24px; border-bottom:1px solid #e2e8f0; margin-bottom:24px;'>")
                       .append("<h2 style='color:#3b82f6; margin:0; font-size:24px; font-weight:700;'>LifeFlow AI Match</h2>")
                       .append("<p style='color:#64748b; margin-top:8px;'>Request #").append(requestId).append(" Update</p>")
                       .append("</div>")
                       .append("<p style='font-size:16px;'>Dear Patient,</p>")
                       .append("<p style='font-size:16px; line-height:1.6;'>Our AI dynamic routing engine has found <strong style='color:#3b82f6;'>").append(matchedDonors.size()).append(" compatible donors</strong> for your recent blood request.</p>")
                       .append("<div style='background:#f1f5f9; border-radius:8px; padding:20px; margin:24px 0;'>")
                       .append("<h3 style='margin-top:0; color:#334155; font-size:14px; text-transform:uppercase; letter-spacing:1px;'>Anonymized Match Shortlist</h3>")
                       .append("<table style='width:100%; border-collapse: collapse; margin-top:15px; font-size:14px;'>")
                       .append("<tr style='background-color:#e2e8f0; text-align:left;'>")
                       .append("<th style='padding:12px; border-bottom:1px solid #cbd5e1;'>Donor Ref</th>")
                       .append("<th style='padding:12px; border-bottom:1px solid #cbd5e1;'>Name (Masked)</th>")
                       .append("<th style='padding:12px; border-bottom:1px solid #cbd5e1;'>Blood Group</th>")
                       .append("<th style='padding:12px; border-bottom:1px solid #cbd5e1;'>Status</th>")
                       .append("</tr>");

            if (matchedDonors == null || matchedDonors.isEmpty()) {
                htmlBuilder.append("<tr><td colspan='4' style='padding:15px; text-align:center; color:#64748b;'>No compatible donors are available at this moment.</td></tr>");
            } else {
                for (Donor d : matchedDonors) {
                    String idStr = String.valueOf(d.id); 
                    String obfuscatedId = "***" + idStr.substring(Math.max(0, idStr.length() - 2));
                    String obfuscatedName = (d.name != null && d.name.length() > 2) ? d.name.substring(0, 2) + "***" : "***";
                    
                    htmlBuilder.append("<tr>")
                               .append("<td style='padding:12px; border-bottom:1px solid #f1f5f9; font-family:monospace;'>").append(obfuscatedId).append("</td>")
                               .append("<td style='padding:12px; border-bottom:1px solid #f1f5f9;'>").append(obfuscatedName).append("</td>")
                               .append("<td style='padding:12px; border-bottom:1px solid #f1f5f9; font-weight:bold; color:#ef4444;'>").append(d.getBloodGroup()).append("</td>")
                               .append("<td style='padding:12px; border-bottom:1px solid #f1f5f9; color:#f59e0b; font-weight:600;'>Notified</td>")
                               .append("</tr>");
                }
            }
            
            htmlBuilder.append("</table></div>")
                       .append("<p style='margin-top:20px; font-size:15px;'>We have automatically dispatched emergency alerts to these donors. You will receive a final confirmation email with their contact details as soon as one accepts the request.</p>")
                       .append("<p style='font-size:15px;'>You can monitor live updates on your <a href='").append(getAppUrl()).append("/patient_dashboard.html' style='color:#3b82f6; font-weight:600;'>Patient Dashboard</a>.</p>")
                       .append("<div style='text-align:center; padding-top:24px; border-top:1px solid #e2e8f0; font-size:13px; color:#94a3b8; margin-top:32px;'>")
                       .append("<p>LifeFlow Automated AI System</p>")
                       .append("</div></div></body></html>");

            message.setContent(htmlBuilder.toString(), "text/html; charset=utf-8");
            Transport.send(message);
            logEmail(patientEmail, message.getSubject(), "PATIENT_DIGEST");
            logger.info(" [EmailUtility] Successfully sent patient digest email to: " + patientEmail);

        } catch (MessagingException e) {
            logger.error(" [EmailUtility] Failed to send digest email to patient " + patientEmail + ". Reason: " + e.getMessage());
            logger.error("Exception occurred", e);
        }
    }

    public static void sendConfirmationToPatient(String patientEmail, String patientName, String donorName, String donorBlood, String donorPhone, String donorEmailStr, int eta, String trackingId) {
        try {
            Session session = getSession();
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getSmtpFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(patientEmail));
            message.setSubject("CONFIRMED: Donor Secured for Blood Request - Tracking ID: " + trackingId);

            String htmlContent = "<html><body style='font-family:\"Inter\", Arial, sans-serif; background-color:#f8fafc; padding:40px; color:#1e293b; margin:0;'>" +
                    "<div style='background:white; border-radius:12px; padding:32px; max-width:600px; margin:auto; box-shadow:0 10px 25px rgba(0,0,0,0.05);'>" +
                    "<div style='text-align:center; padding-bottom:24px; border-bottom:1px solid #e2e8f0; margin-bottom:24px;'>" +
                    "<div style='background:#10b981; width:60px; height:60px; border-radius:50%; margin:0 auto 16px; display:flex; align-items:center; justify-content:center; color:white; font-size:28px;'>&#10003;</div>" +
                    "<h2 style='color:#10b981; margin:0; font-size:24px; font-weight:700;'>Donor Confirmed</h2>" +
                    "<p style='color:#64748b; margin-top:8px;'>Tracking ID: " + trackingId + "</p>" +
                    "</div>" +
                    "<p style='font-size:16px;'>Dear <strong>" + patientName + "</strong>,</p>" +
                    "<p style='font-size:16px; line-height:1.6;'>A donor has officially accepted your blood request. Please find their contact information below.</p>" +
                    
                    "<div style='background:#f1f5f9; border-radius:8px; padding:20px; margin:24px 0;'>" +
                    "<h3 style='margin-top:0; color:#334155; font-size:14px; text-transform:uppercase; letter-spacing:1px;'>Donor Information</h3>" +
                    "<table style='width:100%; border-collapse:collapse;'>" +
                    "<tr><td style='padding:8px 0; color:#64748b; width:40%;'>Donor Name</td><td style='padding:8px 0; font-weight:600;'>" + donorName + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Blood Group</td><td style='padding:8px 0; font-weight:600; color:#ef4444;'>" + donorBlood + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Contact Number</td><td style='padding:8px 0; font-weight:600;'>" + donorPhone + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Email Address</td><td style='padding:8px 0; font-weight:600;'>" + donorEmailStr + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Est. Arrival Time</td><td style='padding:8px 0; font-weight:600; color:#3b82f6;'>" + eta + " mins</td></tr>" +
                    "</table>" +
                    "</div>" +

                    "<p style='font-size:16px; margin-bottom:24px;'>The donor is currently preparing and will arrive at your hospital shortly. You may contact them directly if necessary.</p>" +

                    "<div style='text-align:center; padding-top:24px; border-top:1px solid #e2e8f0; font-size:13px; color:#94a3b8;'>" +
                    "<p>Thank you,<br/><strong>LifeFlow Automated AI System</strong></p>" +
                    "</div>" +
                    "</div></body></html>";

            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            logEmail(patientEmail, message.getSubject(), "PATIENT_CONFIRM");
        } catch (MessagingException e) {
            logger.error("Exception occurred", e);
        }
    }

    public static void sendConfirmationToDonor(String donorEmail, String donorName, String patientName, String hospital, String patientPhone, int eta) {
        try {
            Session session = getSession();
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getSmtpFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(donorEmail));
            message.setSubject("DISPATCH CONFIRMED: Proceed to " + hospital);

            String htmlContent = "<html><body style='font-family:\"Inter\", Arial, sans-serif; background-color:#f8fafc; padding:40px; color:#1e293b; margin:0;'>" +
                    "<div style='background:white; border-radius:12px; padding:32px; max-width:600px; margin:auto; box-shadow:0 10px 25px rgba(0,0,0,0.05);'>" +
                    "<div style='text-align:center; padding-bottom:24px; border-bottom:1px solid #e2e8f0; margin-bottom:24px;'>" +
                    "<h2 style='color:#10b981; margin:0; font-size:24px; font-weight:700;'>Action Confirmed</h2>" +
                    "<p style='color:#64748b; margin-top:8px;'>You have been successfully assigned to this emergency.</p>" +
                    "</div>" +
                    "<p style='font-size:16px;'>Dear <strong>" + donorName + "</strong>,</p>" +
                    "<p style='font-size:16px; line-height:1.6;'>Thank you for accepting the request. The patient has been notified of your estimated arrival (" + eta + " mins).</p>" +
                    
                    "<div style='background:#f1f5f9; border-radius:8px; padding:20px; margin:24px 0;'>" +
                    "<h3 style='margin-top:0; color:#334155; font-size:14px; text-transform:uppercase; letter-spacing:1px;'>Patient Information</h3>" +
                    "<table style='width:100%; border-collapse:collapse;'>" +
                    "<tr><td style='padding:8px 0; color:#64748b; width:40%;'>Patient Name</td><td style='padding:8px 0; font-weight:600;'>" + patientName + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Hospital</td><td style='padding:8px 0; font-weight:600; color:#ef4444;'>" + hospital + "</td></tr>" +
                    "<tr><td style='padding:8px 0; color:#64748b;'>Patient Contact</td><td style='padding:8px 0; font-weight:600;'>" + patientPhone + "</td></tr>" +
                    "</table>" +
                    "</div>" +
                    
                    "<div style='text-align:center; margin-bottom:32px;'>" +
                    "<a href='https://www.google.com/maps/search/?api=1&query=" + hospital.replace(" ", "+") + "' style='display:inline-block; background-color:#3b82f6; color:white; padding:14px 32px; text-decoration:none; border-radius:6px; font-weight:600; font-size:16px;'>Open in Google Maps</a>" +
                    "</div>" +

                    "<p style='font-size:14px; color:#64748b; margin-bottom:24px;'><strong>Instructions:</strong> Please proceed safely to the hospital and present yourself to the blood bank reception.</p>" +

                    "<div style='text-align:center; padding-top:24px; border-top:1px solid #e2e8f0; font-size:13px; color:#94a3b8;'>" +
                    "<p>You are saving a life today.<br/><strong>LifeFlow Automated AI System</strong></p>" +
                    "</div>" +
                    "</div></body></html>";

            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            logEmail(donorEmail, message.getSubject(), "DONOR_CONFIRM");
        } catch (MessagingException e) {
            logger.error("Exception occurred", e);
        }
    }

    private static void logEmail(String recipient, String subject, String type) {
        try (java.sql.Connection conn = DBConnection.getConnection()) {
            java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO email_logs (recipient_email, subject, template_type) VALUES (?, ?, ?)");
            stmt.setString(1, recipient);
            stmt.setString(2, subject);
            stmt.setString(3, type);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to log email: " + e.getMessage());
        }
    }
}
