package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="analysis_pages")
public class Page {
    @Id private String id;
    private String analysisId;
    private String url;
    private String title;
    @Enumerated(EnumType.STRING) private PageKind kind;
    protected Page() {}
    public Page(String analysisId,String url,String title){this(analysisId,url,title,null);}
    public Page(String analysisId,String url,String title,PageKind kind){this.id=UUID.randomUUID().toString();this.analysisId=analysisId;this.url=url;this.title=title;this.kind=kind;}
    public String getId(){return id;} public String getAnalysisId(){return analysisId;} public String getUrl(){return url;} public String getTitle(){return title;} public PageKind getKind(){return kind;}
}
