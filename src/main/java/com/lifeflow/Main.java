
package com.lifeflow;

import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        try {
            DBConnection.initializeDatabase();
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize database automatically: " + e.getMessage());
        }

        if (args.length > 0 && "web".equalsIgnoreCase(args[0])) {
            int port = 8080;
            String envPort = System.getenv("PORT");
            if (envPort != null && !envPort.isEmpty()) {
                try {
                    port = Integer.parseInt(envPort);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid PORT environment variable, defaulting to 8080");
                }
            }
            WebServer.start(port);
            return;
        }

        Scanner sc = new Scanner(System.in);
      
        System.out.println("LifeFlow Blood Bank Management System ");
      
              boolean running = true;
        while (running) {
            System.out.println();
            System.out.println("┌─────────────────────────────┐");
            System.out.println("│         MAIN MENU           |");
            System.out.println("├─────────────────────────────┤");
            System.out.println("│  1. Register Donor          │");
            System.out.println("│  2. Register Patient        │");
            System.out.println("│  3. View All Donors         │");
            System.out.println("│  4. View All Patients       │");
            System.out.println("│  5. Create Blood Request    │");
            System.out.println("│  6. View Pending Requests   │");
            System.out.println("│  7. View All Requests       │");
            System.out.println("│  8. Accept Blood Request    │");
            System.out.println("│  9. Mark Donation Completed │");
            System.out.println("│ 10. Donor Donation History  │");
            System.out.println("│ 11. Predict Blood Demand    │");
            System.out.println("│  0. Exit                    │");
            System.out.println("└─────────────────────────────┘");
            System.out.print("Choose an option: ");

            String input = sc.nextLine();
            switch (input) {
                case "1" :
                    DonorService.registerDonor(sc);
                    break;
                case "2" :
                    PatientService.registerPatient(sc);
                    break;
                case "3" :
                    DonorService.listDonors();
                    break;
                case "4" :
                    PatientService.listPatients();
                    break;
                case "5" :
                    BloodRequestService.createRequest(sc);
                    break;
                case "6" :
                    BloodRequestService.listPendingRequests();
                    break;
                case "7" :
                    BloodRequestService.listAllRequests();
                    break;
                case "8" :
                    BloodRequestService.acceptRequest(sc);
                    break;
                case "9" :
                    BloodRequestService.markCompleted(sc);
                    break;
                case "10" :
                    DonorService.viewDonationHistory(sc);
                    break;
                case "11" :
                    BloodRequestService.predictFutureDemand();
                    break;
                case "0" :
                    System.out.println("Goodbye!");
                    running = false;
                    break;
                    default :
                    System.out.println(" Invalid option. Please try again.");
            }
        }
        sc.close();
    }
}