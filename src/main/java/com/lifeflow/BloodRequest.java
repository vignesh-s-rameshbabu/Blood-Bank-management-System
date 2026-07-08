
package com.lifeflow;

public class BloodRequest {
    private int requestId;
    private String bloodGroup;
    private int units;
    private String status;
    private String patientName;
    private String patientEmail;
    private String hospital;

    public BloodRequest() {}

    public BloodRequest(int requestId, String bloodGroup, int units, String status) {
        this.requestId = requestId;
        this.bloodGroup = bloodGroup;
        this.units = units;
        this.status = status;
    }

    public int getRequestId() { return requestId; }
    public void setRequestId(int requestId) { this.requestId = requestId; }

    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }

    public int getUnits() { return units; }
    public void setUnits(int units) { this.units = units; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public String getPatientEmail() { return patientEmail; }
    public void setPatientEmail(String patientEmail) { this.patientEmail = patientEmail; }

    public String getHospital() { return hospital; }
    public void setHospital(String hospital) { this.hospital = hospital; }

    @Override
    public String toString() {
        return "BloodRequest{requestId=" + requestId + ", bloodGroup='" + bloodGroup +
               "', units=" + units + ", status='" + status + "'}";
    }
}

