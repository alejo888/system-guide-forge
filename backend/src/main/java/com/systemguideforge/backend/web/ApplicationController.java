package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.*;
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
    @PostMapping("/api/projects/{projectId}/applications") public ResponseEntity<ApplicationResponse> create(@PathVariable String projectId,@Valid @RequestBody ApplicationInput input){ TargetApplication a=service.createApplication(projectId,input.name(),input.baseUrl(),input.loginUrl(),input.username(),input.password(),input.maxCrawlDepth(),input.excludedRoutes()); return ResponseEntity.created(URI.create("/api/applications/"+a.getId())).body(toResponse(a)); }
    @GetMapping("/api/applications/{id}") public ResponseEntity<ApplicationResponse> get(@PathVariable String id){ return service.getApplication(id).map(a->ResponseEntity.ok(toResponse(a))).orElseGet(()->ResponseEntity.notFound().build()); }
    @PutMapping("/api/applications/{id}") public ResponseEntity<ApplicationResponse> update(@PathVariable String id,@Valid @RequestBody ApplicationInput input){
        try { TargetApplication a=service.updateApplication(id,input.name(),input.baseUrl(),input.loginUrl(),input.username(),input.password(),input.maxCrawlDepth(),input.excludedRoutes()); return ResponseEntity.ok(toResponse(a)); }
        catch (java.util.NoSuchElementException ex) { return ResponseEntity.notFound().build(); }
    }
    @PostMapping("/api/applications/{id}/test-access") public ResponseEntity<AccessResult> test(@PathVariable String id){
        try { return ResponseEntity.ok(service.testAccess(id)); }
        catch (java.util.NoSuchElementException ex) { return ResponseEntity.notFound().build(); }
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidConfiguration(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); }
    private ApplicationResponse toResponse(TargetApplication a){return new ApplicationResponse(a.getId(),a.getProjectId(),a.getName(),a.getBaseUrl(),a.getLoginUrl(),a.getMaxCrawlDepth(),a.getExcludedRoutes());}
    public record ApplicationInput(@NotBlank String name,@NotBlank String baseUrl,@NotBlank String loginUrl,@NotBlank String username,@NotBlank String password, Integer maxCrawlDepth, List<String> excludedRoutes) {}
    public record ApplicationResponse(String id,String projectId,String name,String baseUrl,String loginUrl,int maxCrawlDepth,List<String> excludedRoutes) {}
    public record ErrorResponse(String message) {}
}
