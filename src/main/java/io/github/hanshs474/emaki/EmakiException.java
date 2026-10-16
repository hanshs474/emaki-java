package io.github.hanshs474.emaki;

/** The only exception this library throws on purpose. Check {@link #kind()} to decide what to do next. */
public final class EmakiException extends RuntimeException {
    /** Why a call failed, split by what to do next. */
    public enum Kind {
        /** No API key, or the key is invalid or deleted (HTTP 401). */
        AUTH,
        /** The account does not have enough credits for this run (HTTP 402). */
        QUOTA,
        /** The model is locked until the account has bought credits (HTTP 403). */
        PLAN,
        /** Another generation on this account is still in progress (HTTP 409). */
        BUSY,
        /** The content filter refused the prompt, or the account is cooling down after a refusal. */
        REJECTED,
        /** A parameter was missing or out of range (HTTP 400). */
        VALIDATION,
        /** The run was accepted and then failed; {@link #failureCode()} says why when known. */
        FAILED,
        /** The deadline passed; the run may still finish in your emaki library. */
        TIMEOUT,
        /** Anything else the service answered, or a transport failure. */
        OTHER
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final int httpStatus;
    private final String code;
    private final String failureCode;

    EmakiException(Kind kind, String message) {
        this(kind, message, 0, null, null);
    }

    EmakiException(Kind kind, String message, int httpStatus, String code, String failureCode) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
        this.failureCode = failureCode;
    }

    /** What went wrong. */
    public Kind kind() {
        return kind;
    }

    /** The HTTP status the service answered with, or 0 when there was no response. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The machine-readable {@code code} field of the error body, e.g. {@code content_blocked}, or null. */
    public String code() {
        return code;
    }

    /** For {@link Kind#FAILED}: the service's failure code, or null. */
    public String failureCode() {
        return failureCode;
    }

    /** Map an HTTP error answer to the error that says what to do next. */
    static EmakiException fromHttp(int status, String message, String code) {
        String msg = message == null || message.isEmpty() ? "HTTP " + status : message;
        Kind kind;
        if (status == 401) kind = Kind.AUTH;
        else if (status == 402) kind = Kind.QUOTA;
        else if (status == 403) kind = Kind.PLAN;
        else if (status == 409) kind = Kind.BUSY;
        else if ("content_cooldown".equals(code) || "content_blocked".equals(code)) kind = Kind.REJECTED;
        else if (status == 400) kind = Kind.VALIDATION;
        else kind = Kind.OTHER;
        return new EmakiException(kind, msg, status, code, null);
    }
}
