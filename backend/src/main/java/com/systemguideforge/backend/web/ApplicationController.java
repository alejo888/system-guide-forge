package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.*;
import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
import com.systemguideforge.backend.persistence.TargetApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
public class ApplicationController {
    private final ProjectApplicationService service;
    public ApplicationController(ProjectApplicationService service){this.service=service;}
    @PostMapping("/api/projects/{projectId}/applications") public ResponseEntity<ApplicationResponse> create(@PathVariable String projectId,@Valid @RequestBody ApplicationInput input){ TargetApplication a=service.createApplication(projectId,input.name(),input.baseUrl(),input.loginUrl(),input.username(),input.password(),input.maxCrawlDepth(),input.excludedRoutes(),toRules(input.approvedOpeners())); return ResponseEntity.created(URI.create("/api/applications/"+a.getId())).body(toResponse(a)); }
    @GetMapping("/api/applications") public List<ApplicationResponse> list(){ return service.listApplications().stream().map(this::toResponse).toList(); }
    @GetMapping("/api/applications/{id}") public ResponseEntity<ApplicationResponse> get(@PathVariable String id){ return service.getApplication(id).map(a->ResponseEntity.ok(toResponse(a))).orElseGet(()->ResponseEntity.notFound().build()); }
    @PutMapping("/api/applications/{id}") public ResponseEntity<ApplicationResponse> update(@PathVariable String id,@Valid @RequestBody ApplicationInput input){
        try { TargetApplication a=service.updateApplication(id,input.name(),input.baseUrl(),input.loginUrl(),input.username(),input.password(),input.maxCrawlDepth(),input.excludedRoutes(),toRules(input.approvedOpeners())); return ResponseEntity.ok(toResponse(a)); }
        catch (java.util.NoSuchElementException ex) { return ResponseEntity.notFound().build(); }
    }
    /** RF24: approves one opener without the credentials that PUT requires; an already approved opener is a no-op. */
    @PostMapping("/api/applications/{id}/approved-openers") public ResponseEntity<ApplicationResponse> approveOpener(@PathVariable String id,@RequestBody ApprovedOpenerPayload input){
        try { return ResponseEntity.ok(toResponse(service.approveOpener(id,toRule(input)))); }
        catch (java.util.NoSuchElementException ex) { return ResponseEntity.notFound().build(); }
    }
    @PostMapping("/api/applications/{id}/test-access") public ResponseEntity<AccessResult> test(@PathVariable String id){
        try { return ResponseEntity.ok(service.testAccess(id)); }
        catch (java.util.NoSuchElementException ex) { return ResponseEntity.notFound().build(); }
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidConfiguration(IllegalArgumentException e) {
        String code = e instanceof ProjectApplicationService.InvalidApplicationConfigurationException invalid ? invalid.code() : null;
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage(), code));
    }
    private ApplicationResponse toResponse(TargetApplication a){return new ApplicationResponse(a.getId(),a.getProjectId(),a.getName(),a.getBaseUrl(),a.getLoginUrl(),a.getMaxCrawlDepth(),a.getExcludedRoutes(),a.getApprovedOpeners().stream().map(r->new ApprovedOpenerPayload(r.routePattern(),r.accessibleName())).toList());}
    private static List<ApprovedOpenerRule> toRules(List<ApprovedOpenerPayload> openers){ return openers == null ? null : openers.stream().map(ApplicationController::toRule).toList(); }
    private static ApprovedOpenerRule toRule(ApprovedOpenerPayload opener){ return opener == null ? null : new ApprovedOpenerRule(opener.routePattern(),opener.accessibleName()); }
    public record ApplicationInput(@NotBlank String name,@NotBlank String baseUrl,@NotBlank String loginUrl,@NotBlank String username,@NotBlank String password, Integer maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerPayload> approvedOpeners) {}
    public record ApplicationResponse(String id,String projectId,String name,String baseUrl,String loginUrl,int maxCrawlDepth,List<String> excludedRoutes,List<ApprovedOpenerPayload> approvedOpeners) {}
    public record ApprovedOpenerPayload(String routePattern, String accessibleName) {}
    /** {@code code} is omitted when the error has none (for example a non-local URL), so older clients see the same body. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String message, String code) {
        public ErrorResponse(String message) { this(message, null); }
    }
}
