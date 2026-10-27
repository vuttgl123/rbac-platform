SET LOCAL ROLE rbac_owner;

-- V13: a Spring Batch job repository per service schema, plus per-service partition maintenance.
--
-- The repository DDL is org/springframework/batch/core/schema-postgresql.sql from spring-batch-core 6.0.6, verbatim
-- (column types, constraint names) apart from two things: every table and sequence name carries the batch_ prefix and
-- lives in the service's schema, so a service points Spring Batch at it with
-- spring.batch.jdbc.table-prefix: <schema>.batch_. Unquoted names fold to lowercase, which is what Spring Batch
-- expects. Batch bumping to a new release means a new migration, never an edit of this one.
--
-- No RLS on these tables: they hold job metadata (job names, parameters, counters), not tenant data, and a job runs
-- outside any tenant. The service role gets exactly what Spring Batch's JDBC repository needs: SELECT, INSERT and
-- UPDATE on the six tables (no DELETE, so history is kept), USAGE and SELECT on the id sequences.
--
-- batch_schedule_slot is not part of Spring Batch. A scheduled job claims a (job_name, scheduled_at) slot with
-- INSERT ... ON CONFLICT DO NOTHING before it launches, so with several instances of a service only the first one
-- to claim a slot runs it. The role gets SELECT and INSERT only.

-- util.create_batch_repository: owner-only (EXECUTE revoked from PUBLIC, not a definer). Creates the tables and
-- sequences in p_schema and grants them to p_role.
CREATE FUNCTION util.create_batch_repository(p_schema text, p_role regrole) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    IF p_schema IS NULL OR p_role IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = p_schema AND n.nspowner = 'rbac_owner'::regrole)
    THEN
        RAISE EXCEPTION 'invalid_argument: schema % must exist and be owned by rbac_owner', p_schema
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_job_instance (
            JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
            VERSION BIGINT,
            JOB_NAME VARCHAR(100) NOT NULL,
            JOB_KEY VARCHAR(32) NOT NULL,
            constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
        )$t$, p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_job_execution (
            JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
            VERSION BIGINT,
            JOB_INSTANCE_ID BIGINT NOT NULL,
            CREATE_TIME TIMESTAMP NOT NULL,
            START_TIME TIMESTAMP DEFAULT NULL,
            END_TIME TIMESTAMP DEFAULT NULL,
            STATUS VARCHAR(10),
            EXIT_CODE VARCHAR(2500),
            EXIT_MESSAGE VARCHAR(2500),
            LAST_UPDATED TIMESTAMP,
            constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
            references %1$I.batch_job_instance(JOB_INSTANCE_ID)
        )$t$, p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_job_execution_params (
            JOB_EXECUTION_ID BIGINT NOT NULL,
            PARAMETER_NAME VARCHAR(100) NOT NULL,
            PARAMETER_TYPE VARCHAR(100) NOT NULL,
            PARAMETER_VALUE VARCHAR(2500),
            IDENTIFYING CHAR(1) NOT NULL,
            constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
            references %1$I.batch_job_execution(JOB_EXECUTION_ID)
        )$t$, p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_step_execution (
            STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
            VERSION BIGINT NOT NULL,
            STEP_NAME VARCHAR(100) NOT NULL,
            JOB_EXECUTION_ID BIGINT NOT NULL,
            CREATE_TIME TIMESTAMP NOT NULL,
            START_TIME TIMESTAMP DEFAULT NULL,
            END_TIME TIMESTAMP DEFAULT NULL,
            STATUS VARCHAR(10),
            COMMIT_COUNT BIGINT,
            READ_COUNT BIGINT,
            FILTER_COUNT BIGINT,
            WRITE_COUNT BIGINT,
            READ_SKIP_COUNT BIGINT,
            WRITE_SKIP_COUNT BIGINT,
            PROCESS_SKIP_COUNT BIGINT,
            ROLLBACK_COUNT BIGINT,
            EXIT_CODE VARCHAR(2500),
            EXIT_MESSAGE VARCHAR(2500),
            LAST_UPDATED TIMESTAMP,
            constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
            references %1$I.batch_job_execution(JOB_EXECUTION_ID)
        )$t$, p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_step_execution_context (
            STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            SHORT_CONTEXT VARCHAR(2500) NOT NULL,
            SERIALIZED_CONTEXT TEXT,
            constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
            references %1$I.batch_step_execution(STEP_EXECUTION_ID)
        )$t$, p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_job_execution_context (
            JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            SHORT_CONTEXT VARCHAR(2500) NOT NULL,
            SERIALIZED_CONTEXT TEXT,
            constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
            references %1$I.batch_job_execution(JOB_EXECUTION_ID)
        )$t$, p_schema);

    EXECUTE format('CREATE SEQUENCE %I.batch_step_execution_seq MAXVALUE 9223372036854775807 NO CYCLE', p_schema);
    EXECUTE format('CREATE SEQUENCE %I.batch_job_execution_seq MAXVALUE 9223372036854775807 NO CYCLE', p_schema);
    EXECUTE format('CREATE SEQUENCE %I.batch_job_instance_seq MAXVALUE 9223372036854775807 NO CYCLE', p_schema);

    EXECUTE format($t$
        CREATE TABLE %1$I.batch_schedule_slot (
            job_name     text        NOT NULL,
            scheduled_at timestamptz NOT NULL,
            claimed_by   text        NOT NULL,
            claimed_at   timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (job_name, scheduled_at)
        )$t$, p_schema);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON '
                   '%1$I.batch_job_instance, %1$I.batch_job_execution, %1$I.batch_job_execution_params, '
                   '%1$I.batch_step_execution, %1$I.batch_step_execution_context, %1$I.batch_job_execution_context '
                   'TO %2$s', p_schema, p_role::text);
    EXECUTE format('GRANT SELECT, INSERT ON %I.batch_schedule_slot TO %s', p_schema, p_role::text);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE '
                   '%1$I.batch_step_execution_seq, %1$I.batch_job_execution_seq, %1$I.batch_job_instance_seq '
                   'TO %2$s', p_schema, p_role::text);
END
$$;

REVOKE EXECUTE ON FUNCTION util.create_batch_repository(text, regrole) FROM PUBLIC;

SELECT util.create_batch_repository('identity', 'identity_app');
SELECT util.create_batch_repository('authz', 'authz_app');
SELECT util.create_batch_repository('audit', 'audit_app');

-- Partition maintenance, one function per service that owns a partitioned table. A service's maintenance job calls
-- its own function as its own role, so the role never needs EXECUTE on util.ensure_monthly_partitions (which can
-- partition any table rbac_owner owns). Current month plus three months ahead, like the pre-created partitions.
CREATE FUNCTION audit.maintain_partitions() RETURNS int
    LANGUAGE sql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT util.ensure_monthly_partitions('audit.events'::regclass, 3)
$$;

CREATE FUNCTION authz.maintain_partitions() RETURNS int
    LANGUAGE sql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT util.ensure_monthly_partitions('authz.relationship_changes'::regclass, 3)
$$;

REVOKE EXECUTE ON FUNCTION audit.maintain_partitions() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION authz.maintain_partitions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.maintain_partitions() TO audit_app;
GRANT EXECUTE ON FUNCTION authz.maintain_partitions() TO authz_app;
