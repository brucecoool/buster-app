package com.dave.busterapp.model;

public enum TransactionStatus {
    CREATED(false), PENDING(false), COMPLETED(true), CANCELED(true), UNKNOWN(false);

    boolean isFinal;

    TransactionStatus(final boolean isFinal) {
        this.isFinal = isFinal;
    }

    public boolean isFinal() {
        return isFinal;
    }
}
