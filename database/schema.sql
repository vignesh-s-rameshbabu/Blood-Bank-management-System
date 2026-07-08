
CREATE DATABASE IF NOT EXISTS lifeflow;
USE lifeflow;

CREATE TABLE IF NOT EXISTS users(
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    email VARCHAR(100) UNIQUE,
    password VARCHAR(255),
    phone VARCHAR(20),
    role VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS donors(
    donor_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    blood_group VARCHAR(5),
    city VARCHAR(100),
    available BOOLEAN DEFAULT TRUE,
    FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS patients(
    patient_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    blood_group VARCHAR(5),
    hospital VARCHAR(150),
    FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS blood_requests(
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    patient_id INT,
    blood_group VARCHAR(5),
    units INT,
    status VARCHAR(30) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(patient_id) REFERENCES patients(patient_id)
);

CREATE TABLE IF NOT EXISTS donations(
    donation_id INT AUTO_INCREMENT PRIMARY KEY,
    donor_id INT,
    request_id INT,
    donated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(donor_id) REFERENCES donors(donor_id),
    FOREIGN KEY(request_id) REFERENCES blood_requests(request_id)
);

