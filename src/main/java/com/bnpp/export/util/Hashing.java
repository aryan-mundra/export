package com.bnpp.export.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprints: record hashes, version fingerprints and review tokens. */
public final class Hashing {

    private Hashing() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Lower-case hex SHA-256 of a text (UTF-8). */
    public static String sha256(String text) {
        return hex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
