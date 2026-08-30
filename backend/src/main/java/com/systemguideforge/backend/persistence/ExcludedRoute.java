package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "excluded_routes", uniqueConstraints = @UniqueConstraint(columnNames = {"application_id", "path"}))
public class ExcludedRoute {
    @Id
    private String id;
    @Column(name = "application_id", nullable = false)
    private String applicationId;
    @Column(nullable = false, length = 200)
    private String path;

    protected ExcludedRoute() {}

    public ExcludedRoute(String applicationId, String path) {
        this.id = UUID.randomUUID().toString();
        this.applicationId = applicationId;
        this.path = path;
    }

    public String getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getPath() { return path; }
}
