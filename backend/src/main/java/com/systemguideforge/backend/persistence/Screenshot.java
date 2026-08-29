package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="screenshots")
public class Screenshot {
    @Id private String id;
    private String pageId;
    private boolean sanitized;
    @Column(columnDefinition = "bytea") private byte[] content;
    protected Screenshot() {}
    public Screenshot(String pageId, byte[] content){this.id=UUID.randomUUID().toString();this.pageId=pageId;this.content=content;this.sanitized=true;}
    public String getId(){return id;} public String getPageId(){return pageId;} public boolean isSanitized(){return sanitized;} public byte[] getContent(){return content;}
}
