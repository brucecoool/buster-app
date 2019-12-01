package com.dave.busterapp.model;

public class BusterWebhook {
    private String type;
    private BusterTransaction data;

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public BusterTransaction getData() {
        return data;
    }

    public void setData(final BusterTransaction data) {
        this.data = data;
    }
}
