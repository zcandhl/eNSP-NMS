package com.ensp.nms.service;

import com.ensp.nms.entity.ConfigTemplate;
import com.ensp.nms.repository.ConfigTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    private final ConfigTemplateRepository configTemplateRepository;

    public List<ConfigTemplate> getAllTemplates() {
        return configTemplateRepository.findAll();
    }

    public Optional<ConfigTemplate> getTemplateById(Long id) {
        return configTemplateRepository.findById(id);
    }

    public List<ConfigTemplate> getTemplatesByDeviceType(String deviceType) {
        return configTemplateRepository.findByDeviceType(deviceType);
    }

    public List<ConfigTemplate> getTemplatesByCategory(String category) {
        return configTemplateRepository.findByCategory(category);
    }

    @Transactional
    public ConfigTemplate createTemplate(ConfigTemplate template) {
        return configTemplateRepository.save(template);
    }

    @Transactional
    public ConfigTemplate updateTemplate(Long id, ConfigTemplate templateDetails) {
        ConfigTemplate template = configTemplateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        
        template.setName(templateDetails.getName());
        template.setCategory(templateDetails.getCategory());
        template.setContent(templateDetails.getContent());
        template.setDescription(templateDetails.getDescription());
        template.setDeviceType(templateDetails.getDeviceType());
        
        return configTemplateRepository.save(template);
    }

    @Transactional
    public void deleteTemplate(Long id) {
        configTemplateRepository.deleteById(id);
    }
}
