package com.vrn.core;

import java.io.IOException;

public final class RestoreFailure extends IOException {
    public enum Status { REFUSED, PREPARATION_FAILED, ROLLED_BACK, RECOVERY_REQUIRED, COMPLETED_CLEANUP_PENDING }
    private final Status status;
    RestoreFailure(Status status, String message, Throwable cause) { super(message, cause); this.status = status; }
    public Status status() { return status; }
}
