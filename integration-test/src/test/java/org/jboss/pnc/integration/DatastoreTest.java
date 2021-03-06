package org.jboss.pnc.integration;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.transaction.api.annotation.TransactionMode;
import org.jboss.arquillian.transaction.api.annotation.Transactional;
import org.jboss.pnc.datastore.repositories.BuildRecordRepository;
import org.jboss.pnc.integration.deployments.Deployments;
import org.jboss.pnc.model.BuildDriverStatus;
import org.jboss.pnc.model.BuildRecord;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Arquillian.class)
@Transactional(TransactionMode.ROLLBACK)
public class DatastoreTest {

    public static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    private BuildRecordRepository buildRecordRepository;


    @Deployment
    public static EnterpriseArchive deploy() {
        EnterpriseArchive enterpriseArchive = Deployments.baseEarWithTestDependencies();
        WebArchive war = enterpriseArchive.getAsType(WebArchive.class, "/pnc-web.war");
        war.addClass(DatastoreTest.class);
        logger.info(enterpriseArchive.toString(true));
        return enterpriseArchive;
    }

    @Test
    public void shouldStoreResults() {
        //given
        BuildRecord objectToBeStored = new BuildRecord();
        objectToBeStored.setStatus(BuildDriverStatus.CANCELLED);

        //when
        buildRecordRepository.save(objectToBeStored);
        List<BuildRecord> objectsInDb = buildRecordRepository.findAll();

        //then
        assertThat(objectsInDb).hasSize(1).containsExactly(objectToBeStored);
    }

}
