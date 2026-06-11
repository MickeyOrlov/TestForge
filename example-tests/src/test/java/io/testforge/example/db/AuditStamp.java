package io.testforge.example.db;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class AuditStamp {

    @Column(name = "created_by")
    private String createdBy;

    private String channel;

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
