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
    /** Only set for the LOGIN page: the associated control names captured before credentials are typed, never a field value. */
    private String loginUsernameLabel;
    private String loginPasswordLabel;
    private String loginSubmitLabel;
    protected Page() {}
    public Page(String analysisId,String url,String title){this(analysisId,url,title,null);}
    public Page(String analysisId,String url,String title,PageKind kind){this(analysisId,url,title,kind,null,null,null);}
    public Page(String analysisId,String url,String title,PageKind kind,String loginUsernameLabel,String loginPasswordLabel,String loginSubmitLabel){
        this.id=UUID.randomUUID().toString();this.analysisId=analysisId;this.url=url;this.title=title;this.kind=kind;
        this.loginUsernameLabel=loginUsernameLabel;this.loginPasswordLabel=loginPasswordLabel;this.loginSubmitLabel=loginSubmitLabel;
    }
    public String getId(){return id;} public String getAnalysisId(){return analysisId;} public String getUrl(){return url;} public String getTitle(){return title;} public PageKind getKind(){return kind;}
    public String getLoginUsernameLabel(){return loginUsernameLabel;} public String getLoginPasswordLabel(){return loginPasswordLabel;} public String getLoginSubmitLabel(){return loginSubmitLabel;}
}
