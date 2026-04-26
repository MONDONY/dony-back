package com.dony.api.notifications;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Where(clause = "deleted_at IS NULL")
public class NotificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private Map<String, String> data;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "is_critical", nullable = false)
    private boolean isCritical = false;

    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    @Column(name = "sms_sent_at")
    private LocalDateTime smsSentAt;

    protected NotificationEntity() {}

    public NotificationEntity(UUID userId, String type, String title, String body,
                              Map<String, String> data, boolean isCritical) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.data = data;
        this.isCritical = isCritical;
    }

    public UUID getUserId()              { return userId; }
    public String getType()              { return type; }
    public String getTitle()             { return title; }
    public String getBody()              { return body; }
    public Map<String, String> getData() { return data; }
    public LocalDateTime getReadAt()     { return readAt; }
    public boolean isRead()              { return readAt != null; }
    public boolean isCritical()          { return isCritical; }
    public LocalDateTime getAckedAt()    { return ackedAt; }
    public LocalDateTime getSmsSentAt()  { return smsSentAt; }

    public void markRead(LocalDateTime at)    { this.readAt = at; }
    public void markAcked(LocalDateTime at)   { this.ackedAt = at; }
    public void markSmsSent(LocalDateTime at) { this.smsSentAt = at; }
}