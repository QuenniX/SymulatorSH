package pl.smarthome.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.smarthome.platform.domain.TemplateEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, UUID> {

    List<TemplateEntity> findAllByOrderByCreatedAtDesc();
}
