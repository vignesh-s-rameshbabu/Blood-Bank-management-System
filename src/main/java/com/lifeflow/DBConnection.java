
package com.lifeflow;

import com.zaxxer.hikari.HikariConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DBConnection {

    private static final Logger logger = LoggerFactory.getLogger(DBConnection.class);

    private static final String SERVER_URL = "jdbc:mysql://127.0.0.1:3306/";
    private static HikariDataSource dataSource;
    private static java.util.Properties dbProps = new java.util.Properties();
    
    static {
        try (java.io.InputStream input = DBConnection.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                dbProps.load(input);
            } else {
                logger.warn("application.properties not found for DB properties");
            }
        } catch (Exception ex) {
            logger.error("Error loading DB properties", ex);
        }

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(dbProps.getProperty("db.url", "jdbc:mysql://127.0.0.1:3306/lifeflow"));
            config.setUsername(dbProps.getProperty("db.user", "root"));
            config.setPassword(dbProps.getProperty("db.password", "root"));
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            logger.error("Failed to initialize HikariCP connection pool: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }

    public static void initializeDatabase() throws Exception {
        try (Connection conn = getServerConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS lifeflow");
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users(" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "name VARCHAR(100)," +
                    "email VARCHAR(100) UNIQUE," +
                    "password VARCHAR(255)," +
                    "phone VARCHAR(20)," +
                    "role VARCHAR(20)," +
                    "google_id VARCHAR(255) UNIQUE," +
                    "profile_image VARCHAR(500)," +
                    "login_provider VARCHAR(50) DEFAULT 'GOOGLE'," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "last_login TIMESTAMP," +
                    "email_verified BOOLEAN DEFAULT TRUE)");
            try {
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN google_id VARCHAR(255) UNIQUE");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN profile_image VARCHAR(500)");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN login_provider VARCHAR(50) DEFAULT 'GOOGLE'");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN last_login TIMESTAMP");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN email_verified BOOLEAN DEFAULT TRUE");
            } catch (Exception e) {
                // Ignore if columns already exist
            }
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS sessions(" +
                    "session_id VARCHAR(100) PRIMARY KEY," +
                    "user_id INT," +
                    "csrf_token VARCHAR(100)," +
                    "expires_at TIMESTAMP," +
                    "FOREIGN KEY(user_id) REFERENCES users(id))");
            try {
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN csrf_token VARCHAR(100)");
            } catch (Exception e) {
                // ignore
            }
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS donors(" +
                    "donor_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "user_id INT," +
                    "blood_group VARCHAR(5)," +
                    "city VARCHAR(100)," +
                    "age INT," +
                    "available BOOLEAN DEFAULT TRUE," +
                    "FOREIGN KEY(user_id) REFERENCES users(id))");
            try {
                stmt.executeUpdate("ALTER TABLE donors ADD COLUMN age INT");
            } catch (Exception e) {
                // ignore if column already exists
            }
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS patients(" +
                    "patient_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "user_id INT," +
                    "blood_group VARCHAR(5)," +
                    "hospital VARCHAR(150)," +
                    "FOREIGN KEY(user_id) REFERENCES users(id))");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS blood_requests(" +
                    "request_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "patient_id INT," +
                    "blood_group VARCHAR(5)," +
                    "units INT," +
                    "status VARCHAR(30) DEFAULT 'PENDING'," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(patient_id) REFERENCES patients(patient_id))");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS donations(" +
                    "donation_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "donor_id INT," +
                    "request_id INT," +
                    "donated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(donor_id) REFERENCES donors(donor_id)," +
                    "FOREIGN KEY(request_id) REFERENCES blood_requests(request_id))");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS match_logs(" +
                    "log_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "request_id INT," +
                    "donor_id INT," +
                    "status VARCHAR(50)," +
                    "compatibility_score INT," +
                    "distance_km INT," +
                    "estimated_arrival_mins INT," +
                    "notified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "responded_at TIMESTAMP," +
                    "FOREIGN KEY(request_id) REFERENCES blood_requests(request_id)," +
                    "FOREIGN KEY(donor_id) REFERENCES donors(donor_id))");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS email_logs(" +
                    "email_id INT AUTO_INCREMENT PRIMARY KEY," +
                    "recipient_email VARCHAR(100)," +
                    "subject VARCHAR(200)," +
                    "template_type VARCHAR(50)," +
                    "sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private static Connection getServerConnection() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try {
            return DriverManager.getConnection(SERVER_URL, "root", "");
        } catch (Exception e) {
            return DriverManager.getConnection(SERVER_URL, "root", "root");
        }
    }
}
