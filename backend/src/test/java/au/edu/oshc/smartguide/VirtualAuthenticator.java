package au.edu.oshc.smartguide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upokecenter.cbor.CBORObject;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

/** A test-only authenticator that produces genuine ES256 WebAuthn signatures. */
class VirtualAuthenticator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KeyPair keyPair;
    final byte[] id = new byte[32];
    String handle;

    VirtualAuthenticator() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        new SecureRandom().nextBytes(id);
    }

    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private static byte[] coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray(), result = new byte[32];
        System.arraycopy(bytes, Math.max(0, bytes.length - 32), result, Math.max(0, 32 - bytes.length), Math.min(32, bytes.length));
        return result;
    }

    String register(JsonNode options) throws Exception { return register(options, "http://localhost:5173", "localhost", 0x45); }

    String register(JsonNode options, String origin, String rpId, int flags) throws Exception {
        handle = options.at("/publicKey/user/id").asText();
        ECPublicKey key = (ECPublicKey) keyPair.getPublic();
        byte[] cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
                .Add(-2, coordinate(key.getW().getAffineX())).Add(-3, coordinate(key.getW().getAffineY())).EncodeToBytes();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(authData(rpId, flags, 0));
        data.write(new byte[16]); // AAGUID for an unattested authenticator.
        data.write(ByteBuffer.allocate(2).putShort((short) id.length).array());
        data.write(id); data.write(cose);
        byte[] attestation = CBORObject.NewMap().Add("fmt", "none").Add("attStmt", CBORObject.NewMap())
                .Add("authData", data.toByteArray()).EncodeToBytes();
        return JSON.writeValueAsString(Map.of("id", b64(id), "rawId", b64(id), "type", "public-key",
                "clientExtensionResults", Map.of("credProps", Map.of("rk", true)),
                "response", Map.of("clientDataJSON", b64(client("webauthn.create", options, origin)),
                        "attestationObject", b64(attestation), "transports", List.of("internal"))));
    }

    String authenticate(JsonNode options, int counter) throws Exception {
        return authenticate(options, counter, "http://localhost:5173", "localhost", 5, handle, false);
    }

    String authenticate(JsonNode options, int counter, String origin, String rpId, int flags,
                        String userHandle, boolean corruptSignature) throws Exception {
        byte[] client = client("webauthn.get", options, origin);
        byte[] data = authData(rpId, flags, counter);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate()); signer.update(data); signer.update(hash(client));
        byte[] signature = signer.sign();
        if (corruptSignature) signature[signature.length - 1] ^= 1;
        return JSON.writeValueAsString(Map.of("id", b64(id), "rawId", b64(id), "type", "public-key",
                "clientExtensionResults", Map.of(),
                "response", Map.of("clientDataJSON", b64(client), "authenticatorData", b64(data),
                        "signature", b64(signature), "userHandle", userHandle)));
    }

    private byte[] client(String type, JsonNode options, String origin) throws Exception {
        return JSON.writeValueAsBytes(Map.of("type", type, "challenge", options.at("/publicKey/challenge").asText(),
                "origin", origin, "crossOrigin", false));
    }

    private byte[] authData(String rpId, int flags, int counter) throws Exception {
        return ByteBuffer.allocate(37).put(hash(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) flags).putInt(counter).array();
    }
}
