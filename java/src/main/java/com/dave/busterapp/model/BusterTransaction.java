package com.dave.busterapp.model;

import java.time.OffsetDateTime;

public class BusterTransaction {
    private String id;
    private OffsetDateTime created;
    private String status;
    private String referenceId;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public OffsetDateTime getCreated() {
        return created;
    }

    public void setCreated(final OffsetDateTime created) {
        this.created = created;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(final String referenceId) {
        this.referenceId = referenceId;
    }
}
