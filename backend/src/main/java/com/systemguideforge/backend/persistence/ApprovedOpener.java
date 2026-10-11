package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "approved_openers", uniqueConstraints = @UniqueConstraint(columnNames = {"application_id", "route_pattern", "accessible_name"}))
public class ApprovedOpener {
    @Id
    private String id;
    @Column(name = "application_id", nullable = false)
    private String applicationId;
    @Column(name = "route_pattern", nullable = false, length = 200)
    private String routePattern;
    @Column(name = "accessible_name", nullable = false, length = 120)
    private String accessibleName;

    protected ApprovedOpener() {}

    public ApprovedOpener(String applicationId, ApprovedOpenerRule rule) {
        this.id = UUID.randomUUID().toString();
        this.applicationId = applicationId;
        this.routePattern = rule.routePattern();
        this.accessibleName = rule.accessibleName();
    }

    public String getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getRoutePattern() { return routePattern; }
    public String getAccessibleName() { return accessibleName; }
    public ApprovedOpenerRule toRule() { return new ApprovedOpenerRule(routePattern, accessibleName); }
}
