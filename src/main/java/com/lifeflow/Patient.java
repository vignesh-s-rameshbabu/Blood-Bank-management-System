
package com.lifeflow;

public class Patient extends User {
    private String hospital;

    @Override
    public String getRole() {
        return "PATIENT";
    }
}
