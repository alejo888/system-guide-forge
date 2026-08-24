package com.systemguideforge.backend.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity @Table(name="projects")
public class Project {
    @Id private String id;
    private String name;
    protected Project() {}
    public Project(String name) { this.id = UUID.randomUUID().toString(); this.name = name; }
    public String getId() { return id; }
    public String getName() { return name; }
}
