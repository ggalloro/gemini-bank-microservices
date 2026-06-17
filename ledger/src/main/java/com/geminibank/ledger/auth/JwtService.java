package com.geminibank.ledger.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates the bearer JWT issued by the users service.
 *
 * The ledger does not mint tokens; it only verifies the HS256 signature with the
 * shared {@code TOKEN_SECRET} and extracts the logical claims (user_id, full_name).
 * Replaces the original itsdangerous-based validation.
 *
 * Signature verification is implemented directly (raw HMAC-SHA256) rather than via
 * a library that enforces RFC 7518 minimum key sizes, so it stays compatible with
 * the PyJWT-minted tokens of the users service, which use the short shared
 * secret. PyJWT does not impose a minimum key length, and neither does this.
 */
@Service
public class JwtService {

    private final byte[] secretBytes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtService(@Value("${TOKEN_SECRET:dev-secret-change-me}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return the verified identity, or {@code null} if the token is invalid.
     */
    public Identity verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String signingInput = parts[0] + "." + parts[1];

            // Header must declare HS256.
            Map<String, Object> header = parseJson(parts[0]);
            if (!"HS256".equals(String.valueOf(header.get("alg")))) {
                return null;
            }

            // Recompute and constant-time compare the signature.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, provided)) {
                return null;
            }

            Map<String, Object> claims = parseJson(parts[1]);
            Object userId = claims.get("user_id");
            if (!(userId instanceof Number)) {
                return null;
            }
            Object fullName = claims.get("full_name");
            return new Identity(((Number) userId).longValue(),
                    fullName == null ? null : fullName.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String b64Url) throws Exception {
        byte[] json = Base64.getUrlDecoder().decode(b64Url);
        return objectMapper.readValue(json, Map.class);
    }
}
