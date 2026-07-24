package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.smarthome.platform.api.dto.CreateTemplateRequest;
import pl.smarthome.platform.api.dto.CreateTemplateResponse;
import pl.smarthome.platform.api.dto.TemplateResponse;
import pl.smarthome.platform.api.dto.TemplateSummary;
import pl.smarthome.platform.service.TemplateService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
@Tag(name = "Templates", description = "Szablony konfiguracji testów")
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    @Operation(summary = "Lista wszystkich szablonów")
    public List<TemplateSummary> listTemplates() {
        return templateService.listTemplates();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Pełny szablon z konfiguracją")
    public TemplateResponse getTemplate(@PathVariable("id") UUID id) {
        return templateService.getTemplate(id);
    }

    @PostMapping
    @Operation(summary = "Zapisz nowy szablon")
    public ResponseEntity<CreateTemplateResponse> createTemplate(
            @Valid @RequestBody CreateTemplateRequest req) {
        CreateTemplateResponse response = templateService.createTemplate(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Usuń szablon")
    public ResponseEntity<Void> deleteTemplate(@PathVariable("id") UUID id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }
}
