package com.vrn.core;

import java.io.IOException;

/** A bounded, user-facing object-integrity failure; no automatic repair is performed. */
public final class ObjectIntegrityException extends IOException {
    public enum Kind {
        MALFORMED_IDENTIFIER, MISSING, UNREADABLE, UNEXPECTED_TYPE, UNSAFE_PATH,
        DIGEST_MISMATCH, PUBLICATION_FAILED
    }

    private final Kind kind;
    private final String objectId;

    ObjectIntegrityException(Kind kind, String objectId, String reason, Throwable cause) {
        super("Object integrity [" + kind + "]\nObject: " + printable(objectId) + "\nReason: " + reason, cause);
        this.kind = kind;
        this.objectId = objectId;
    }

    public Kind kind() { return kind; }
    public String objectId() { return objectId; }

    private static String printable(String value) {
        if (value == null) return "<null>";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(value.length(), 128); i++) {
            char ch = value.charAt(i);
            result.append(Character.isISOControl(ch) ? String.format("\\u%04x", (int) ch) : ch);
        }
        if (value.length() > 128) result.append("...");
        return result.toString();
    }
}
