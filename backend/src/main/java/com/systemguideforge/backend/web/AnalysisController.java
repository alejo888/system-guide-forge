package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.AnalysisService;
import com.systemguideforge.backend.persistence.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
public class AnalysisController {
    private final AnalysisService service;
    public AnalysisController(AnalysisService service){this.service=service;}
    @PostMapping("/api/applications/{applicationId}/analyses") public ResponseEntity<AnalysisResponse> start(@PathVariable String applicationId){Analysis a=service.start(applicationId);return ResponseEntity.created(URI.create("/api/analyses/"+a.getId())).body(to(a));}
    @GetMapping("/api/analyses/{id}") public ResponseEntity<AnalysisResponse> get(@PathVariable String id){return service.get(id).map(a->ResponseEntity.ok(to(a))).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/api/analyses/{id}/pages") public List<PageResponse> pages(@PathVariable String id){return service.pages(id).stream().map(p->new PageResponse(p.getId(),p.getAnalysisId(),p.getUrl(),p.getTitle())).toList();}
    @GetMapping("/api/pages/{id}/elements") public List<ElementResponse> elements(@PathVariable String id){return service.elements(id).stream().map(e->new ElementResponse(e.getId(),e.getKind(),e.getSelector(),e.getAccessibleName(),e.getActionClassification())).toList();}
    @GetMapping("/api/pages/{id}/screenshot") public ResponseEntity<byte[]> screenshot(@PathVariable String id){return service.screenshot(id).map(s->ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(s.getContent())).orElseGet(()->ResponseEntity.notFound().build());}
    @ExceptionHandler(AnalysisService.AnalysisInProgressException.class) ResponseEntity<ErrorResponse> active(AnalysisService.AnalysisInProgressException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));}
    private static AnalysisResponse to(Analysis a){return new AnalysisResponse(a.getId(),a.getApplicationId(),a.getStatus(),a.getStartedAt(),a.getCompletedAt(),a.getFailureMessage());}
    public record AnalysisResponse(String id,String applicationId,AnalysisStatus status,java.time.Instant startedAt,java.time.Instant completedAt,String failureMessage){}
    public record PageResponse(String id,String analysisId,String url,String title){}
    public record ElementResponse(String id,String kind,String selector,String accessibleName,com.systemguideforge.backend.application.ActionClassification actionClassification){}
    public record ErrorResponse(String message){}
}
