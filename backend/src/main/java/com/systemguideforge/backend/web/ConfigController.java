package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.LocalUrlValidator;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/config")
public class ConfigController {
    private final LocalUrlValidator localUrls;
    public ConfigController(LocalUrlValidator localUrls){this.localUrls=localUrls;}
    @GetMapping public ConfigResponse get(){ return new ConfigResponse(localUrls.allowsDockerHost()); }
    public record ConfigResponse(boolean allowDockerHost) {}
}
