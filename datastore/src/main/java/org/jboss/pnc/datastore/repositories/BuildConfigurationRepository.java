package org.jboss.pnc.datastore.repositories;

import java.util.List;

import org.jboss.pnc.model.BuildConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/*
 * How to use Spring Data: https://speakerdeck.com/olivergierke/spring-data-repositories-a-deep-dive-2
 */
public interface BuildConfigurationRepository extends JpaRepository<BuildConfiguration, Integer> {

    @Query("select u from BuildConfiguration u where u.project.id = ?1")
    List<BuildConfiguration> findByProjectId(Integer projectId);

    @Query("select u from BuildConfiguration u where u.project.id = ?1 and u.id = ?2")
    BuildConfiguration findByProjectIdAndConfigurationId(Integer projectId, Integer id);
}
