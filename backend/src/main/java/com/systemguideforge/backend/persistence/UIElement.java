package com.systemguideforge.backend.persistence;

import com.systemguideforge.backend.application.ActionClassification;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="ui_elements")
public class UIElement {
    @Id private String id;
    private String pageId;
    private String kind;
    private String selector;
    private String accessibleName;
    @Enumerated(EnumType.STRING) private ActionClassification actionClassification;
    @Column(nullable = false) private boolean manualInclusionApproved = false;
    protected UIElement() {}
    public UIElement(String pageId,String kind,String selector,String accessibleName,ActionClassification classification){this.id=UUID.randomUUID().toString();this.pageId=pageId;this.kind=kind;this.selector=selector;this.accessibleName=accessibleName;this.actionClassification=classification;}
    public String getId(){return id;} public String getPageId(){return pageId;} public String getKind(){return kind;} public String getSelector(){return selector;} public String getAccessibleName(){return accessibleName;} public ActionClassification getActionClassification(){return actionClassification;} public boolean isManualInclusionApproved(){return manualInclusionApproved;}
    public void setManualInclusionApproved(boolean approved) { if (actionClassification != ActionClassification.UNKNOWN) throw new IllegalStateException("Only unknown actions can be approved for manual inclusion"); manualInclusionApproved = approved; }
}
