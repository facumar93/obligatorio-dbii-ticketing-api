package uy.edu.ucu.ticketing.api;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppHealthController {

    @GetMapping("/app/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "OK",
            "app", "ticketing-api",
            "message", "Spring Boot responde correctamente",
            "timestamp", OffsetDateTime.now().toString()
        );
    }
}