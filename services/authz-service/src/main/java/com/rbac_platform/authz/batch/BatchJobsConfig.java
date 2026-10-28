package com.rbac_platform.authz.batch;

import com.rbac_platform.lib.batch.PartitionMaintenanceJob;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** The authz service's jobs. Partition maintenance is not tenant-scoped, so it uses Boot's own transaction manager. */
@Configuration(proxyBeanMethods = false)
class BatchJobsConfig {

    @Bean
    Job partitionMaintenance(JobRepository repository, PlatformTransactionManager tx, JdbcClient jdbc) {
        return PartitionMaintenanceJob.create(repository, tx, jdbc, "authz.maintain_partitions");
    }
}
