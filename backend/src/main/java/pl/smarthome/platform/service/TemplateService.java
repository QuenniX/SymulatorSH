package pl.smarthome.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.CreateTemplateRequest;
import pl.smarthome.platform.api.dto.CreateTemplateResponse;
import pl.smarthome.platform.api.dto.TemplateResponse;
import pl.smarthome.platform.api.dto.TemplateSummary;
import pl.smarthome.platform.domain.TemplateEntity;
import pl.smarthome.platform.repository.TemplateRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<TemplateSummary> listTemplates() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplate(UUID id) {
        TemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Szablon nie znaleziony: " + id));
        return toResponse(entity);
    }

    @Transactional
    public CreateTemplateResponse createTemplate(CreateTemplateRequest req) {
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(req.getConfig());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Nie udało się zserializować config JSON", e);
        }

        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setConfigJson(configJson);
        entity.setCreatedAt(Instant.now());
        templateRepository.save(entity);

        log.info("Utworzono szablon {} ('{}')", entity.getId(), entity.getName());
        return CreateTemplateResponse.builder()
                .templateId(entity.getId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        if (!templateRepository.existsById(id)) {
            throw new IllegalArgumentException("Szablon nie znaleziony: " + id);
        }
        templateRepository.deleteById(id);
        log.info("Usunięto szablon {}", id);
    }

    private TemplateSummary toSummary(TemplateEntity e) {
        return TemplateSummary.builder()
                .templateId(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private TemplateResponse toResponse(TemplateEntity e) {
        Object config;
        try {
            config = objectMapper.readValue(e.getConfigJson(), Object.class);
        } catch (JsonProcessingException ex) {
            log.error("Nie udało się zdeserializować config JSON szablonu {}", e.getId(), ex);
            config = null;
        }
        return TemplateResponse.builder()
                .templateId(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .config(config)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
