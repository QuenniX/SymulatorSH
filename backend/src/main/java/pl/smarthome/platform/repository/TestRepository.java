package pl.smarthome.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestRepository extends JpaRepository<TestEntity, UUID> {

    List<TestEntity> findAllByOrderByCreatedAtDesc();

    List<TestEntity> findByStatusOrderByCreatedAtDesc(TestStatus status);
}
