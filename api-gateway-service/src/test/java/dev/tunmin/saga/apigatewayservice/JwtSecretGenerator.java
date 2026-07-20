package dev.tunmin.saga.apigatewayservice;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

public class JwtSecretGenerator {
	
	@Test
    public void generateSecret() {
        // 1. Initialize a cryptographically secure random number generator
        SecureRandom secureRandom = new SecureRandom();
        
        // 2. Allocate an array of 32 bytes (256 bits) to meet HMAC256 requirements
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        
        // 3. Convert to a 64-character literal Hex string 
        String hexSecret = HexFormat.of().formatHex(secretBytes);
        
        // 4. Base64 encode the LITERAL TEXT of the hex string for the K8s data block
        String k8sDataFieldSecret = Base64.getEncoder().encodeToString(hexSecret.getBytes(StandardCharsets.UTF_8));
        
        System.out.println("--- Generated 256-bit Cryptographic Tokens ---");
        System.out.println("Plain Hex (Copy this for local application.yml):");
        System.out.println(hexSecret);
        System.out.println("\nBase64 Value (Copy this for K8s Secret 'data' block):");
        System.out.println(k8sDataFieldSecret);
        System.out.println("----------------------------------------------");
    }	
}
