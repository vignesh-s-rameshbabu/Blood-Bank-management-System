
package com.lifeflow;

public class Donor extends User {
    private String bloodGroup;

    @Override
    public String getRole() {
        return "DONOR";
    }

    public String getBloodGroup() {
        return bloodGroup;
    }

    public void setBloodGroup(String bloodGroup) {
        this.bloodGroup = bloodGroup;
    }
}
