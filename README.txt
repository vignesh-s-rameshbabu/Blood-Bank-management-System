LifeFlow Blood Bank Project

This project uses plain Java, JDBC, MySQL, HTML and CSS.
No Spring Boot is used.

How to run:
1. Start MySQL.
2. Keep the MySQL root password as blank or root.
3. Double-click run.bat.
4. Open http://localhost:8080 in your browser.

The Java app creates the lifeflow database and tables automatically.
You can also run database/schema.sql manually if needed.

Patient registration from the web page saves the patient and blood request in MySQL.
Donor login checks donor email and password from the users table.




npm start 

cmd.exe /c run.bat

mvn exec:java
