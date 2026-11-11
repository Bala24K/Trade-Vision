package io.tradevision.gateway.api;

import io.tradevision.gateway.TvProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.OptionalLong;

/**
 * Stateless demo-account tokens: {@code <accountId>.<base64url(HMAC-SHA256(secret, accountId))>}.
 * No session table to lose on restart, verifiable in constant time on any thread. The secret is generated
 * once and kept next to the journal.
 */
@Component
public class AuthService {
    private final byte[] secret;

    public AuthService(TvProperties props) {
        Path file = Path.of(props.dataDir()).resolve("token.secret");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                byte[] s = new byte[32];
                new SecureRandom().nextBytes(s);
                Files.write(file, s);
            }
            secret = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String issue(long accountId) {
        return accountId + "." + sign(accountId);
    }

    public OptionalLong verify(String token) {
        if (token == null) return OptionalLong.empty();
        if (token.startsWith("Bearer ")) token = token.substring(7);
        int dot = token.indexOf('.');
        if (dot <= 0) return OptionalLong.empty();
        try {
            long id = Long.parseLong(token.substring(0, dot));
            byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
            byte[] expected = sign(id).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(given, expected) ? OptionalLong.of(id) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private String sign(long accountId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(Long.toString(accountId).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
