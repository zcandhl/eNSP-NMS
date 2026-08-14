package com.ensp.nms.controller;

import com.ensp.nms.entity.ConfigTemplate;
import com.ensp.nms.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/config-templates")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ConfigTemplateController {

    private final ConfigTemplateService configTemplateService;

    @GetMapping
    public ResponseEntity<List<ConfigTemplate>> getAllTemplates() {
        return ResponseEntity.ok(configTemplateService.getAllTemplates());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConfigTemplate> getTemplateById(@PathVariable Long id) {
        return configTemplateService.getTemplateById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/device-type/{deviceType}")
    public ResponseEntity<List<ConfigTemplate>> getTemplatesByDeviceType(@PathVariable String deviceType) {
        return ResponseEntity.ok(configTemplateService.getTemplatesByDeviceType(deviceType));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<ConfigTemplate>> getTemplatesByCategory(@PathVariable String category) {
        return ResponseEntity.ok(configTemplateService.getTemplatesByCategory(category));
    }

    @PostMapping
    public ResponseEntity<ConfigTemplate> createTemplate(@RequestBody ConfigTemplate template) {
        return ResponseEntity.ok(configTemplateService.createTemplate(template));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConfigTemplate> updateTemplate(@PathVariable Long id, @RequestBody ConfigTemplate template) {
        try {
            return ResponseEntity.ok(configTemplateService.updateTemplate(id, template));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        configTemplateService.deleteTemplate(id);
        return ResponseEntity.ok().build();
    }
}
