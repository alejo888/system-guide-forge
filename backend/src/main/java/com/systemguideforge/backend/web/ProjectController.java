package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.ProjectApplicationService;
import com.systemguideforge.backend.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController @RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectApplicationService service;
    public ProjectController(ProjectApplicationService service){this.service=service;}
    @PostMapping public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectInput input){ Project p=service.createProject(input.name()); return ResponseEntity.created(URI.create("/api/projects/"+p.getId())).body(new ProjectResponse(p.getId(),p.getName())); }
    @GetMapping("/{id}") public ResponseEntity<ProjectResponse> get(@PathVariable String id){ return service.getProject(id).map(p->ResponseEntity.ok(new ProjectResponse(p.getId(),p.getName()))).orElseGet(()->ResponseEntity.notFound().build()); }
    public record ProjectInput(@NotBlank String name) {}
    public record ProjectResponse(String id,String name) {}
}
