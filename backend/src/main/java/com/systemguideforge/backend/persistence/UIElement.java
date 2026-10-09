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
    /** Same-origin path (no query or fragment) an anchor points to; null for other elements and non-navigable anchors. */
    private String targetPath;
    @Enumerated(EnumType.STRING) private ActionClassification actionClassification;
    /** True when an anchor or button sits inside a navigation landmark (nav, header, aside, role navigation or banner). */
    @Column(nullable = false) private boolean inNavigation = false;
    /** Normalized input type (text, checkbox, radio, email...) of a captured input, never its value; null for other elements and legacy rows. */
    @Column(length = 32) private String controlType;
    @Column(nullable = false) private boolean manualInclusionApproved = false;
    protected UIElement() {}
    public UIElement(String pageId,String kind,String selector,String accessibleName,ActionClassification classification){this(pageId,kind,selector,accessibleName,classification,null);}
    public UIElement(String pageId,String kind,String selector,String accessibleName,ActionClassification classification,String targetPath){this(pageId,kind,selector,accessibleName,classification,targetPath,false);}
    public UIElement(String pageId,String kind,String selector,String accessibleName,ActionClassification classification,String targetPath,boolean inNavigation){this(pageId,kind,selector,accessibleName,classification,targetPath,inNavigation,null);}
    public UIElement(String pageId,String kind,String selector,String accessibleName,ActionClassification classification,String targetPath,boolean inNavigation,String controlType){this.controlType=controlType;this.inNavigation=inNavigation;this.id=UUID.randomUUID().toString();this.pageId=pageId;this.kind=kind;this.selector=selector;this.accessibleName=accessibleName;this.actionClassification=classification;this.targetPath=targetPath;}
    public String getId(){return id;} public String getPageId(){return pageId;} public String getKind(){return kind;} public String getSelector(){return selector;} public String getAccessibleName(){return accessibleName;} public String getTargetPath(){return targetPath;} public boolean isInNavigation(){return inNavigation;} public String getControlType(){return controlType;} public ActionClassification getActionClassification(){return actionClassification;} public boolean isManualInclusionApproved(){return manualInclusionApproved;}
    public void setManualInclusionApproved(boolean approved) { if (actionClassification != ActionClassification.UNKNOWN) throw new IllegalStateException("Only unknown actions can be approved for manual inclusion"); manualInclusionApproved = approved; }
}
