package com.lifeflow;

import java.security.SecureRandom;
import java.util.Base64;

public class CSRFManager {
    private static final SecureRandom secureRandom = new SecureRandom();

    public static String generateCSRFToken() {
        byte[] token = new byte[32];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
