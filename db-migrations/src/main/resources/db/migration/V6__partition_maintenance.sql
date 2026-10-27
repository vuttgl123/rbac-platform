SET LOCAL ROLE rbac_owner;

-- V6: monthly range-partition maintenance for tables partitioned on a timestamptz column
-- (authz.relationship_changes by changed_at, audit.events by occurred_at; created in V7/V8).
--
-- Conventions
--   * Months are UTC months whatever the session TimeZone is. The functions pin TimeZone and
--     DateStyle themselves, so bound literals they read and write are always '... +00'.
--   * Partition name: <parent relname>_p<YYYYMM> in the parent's schema (UTC month).
--   * The DEFAULT partition (<parent>_default) is created by the migration that creates the
--     parent. These functions never create or drop it.
--   * Functions are SECURITY DEFINER owned by rbac_owner, so they can only partition tables
--     rbac_owner owns (all platform tables). New partitions get no grants: only the owner touches
--     children directly, everyone else goes through the parent and its RLS.
--   * Locking: the parent is locked SHARE UPDATE EXCLUSIVE first (serializes concurrent callers),
--     then DEFAULT ACCESS EXCLUSIVE (only when it exists). The parent lock is never escalated, so
--     queries holding the parent (ACCESS SHARE / ROW EXCLUSIVE) cannot deadlock with the job.
--     Dropping a partition still takes ACCESS EXCLUSIVE on the parent (inherent to DROP TABLE).

-- util.partition_range_column: validates that the table is range-partitioned on exactly one plain
-- timestamptz column and returns where it lives. Internal helper: owner-only EXECUTE.
CREATE FUNCTION util.partition_range_column(p_parent regclass)
    RETURNS TABLE (schema_name text, table_name text, column_name text, default_oid oid)
    LANGUAGE plpgsql STABLE
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_strat   "char";
    v_natts   smallint;
    v_attnum  smallint;
    v_typ     oid;
BEGIN
    SELECT n.nspname, c.relname, pt.partstrat, pt.partnatts, pt.partattrs[0], pt.partdefid
    INTO schema_name, table_name, v_strat, v_natts, v_attnum, default_oid
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_partitioned_table pt ON pt.partrelid = c.oid
    WHERE c.oid = p_parent;

    IF v_strat IS NULL THEN
        RAISE EXCEPTION 'partition_unsupported: % is not a partitioned table', p_parent
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF v_strat <> 'r' OR v_natts <> 1 OR v_attnum = 0 THEN
        RAISE EXCEPTION 'partition_unsupported: % must be RANGE partitioned on a single plain column', p_parent
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    SELECT a.attname, a.atttypid INTO column_name, v_typ
    FROM pg_attribute a
    WHERE a.attrelid = p_parent AND a.attnum = v_attnum;

    IF v_typ <> 'timestamptz'::regtype THEN
        RAISE EXCEPTION 'partition_unsupported: partition column %.% must be timestamptz',
            p_parent, column_name
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    RETURN NEXT;
END
$$;

-- util.create_monthly_partition: creates <parent>_pYYYYMM for the UTC month containing p_month,
-- bounds [month start, next month start). Returns false when a partition with exactly those
-- bounds already exists. Rows already sitting in the DEFAULT partition inside the range are moved
-- into the new partition (late job). With a DEFAULT partition present it always builds a loose table,
-- moves the in-range rows and ATTACHes it (see the locking note below); without one it uses
-- CREATE ... PARTITION OF.
CREATE FUNCTION util.create_monthly_partition(p_parent regclass, p_month date) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
    SET TimeZone = 'UTC'
    SET DateStyle = 'ISO, MDY'
AS $$
DECLARE
    v_schema   text;
    v_table    text;
    v_col      text;
    v_default  oid;
    v_start    timestamptz;
    v_end      timestamptz;
    v_child    text;
    v_cols     text;
BEGIN
    IF p_parent IS NULL OR p_month IS NULL OR NOT isfinite(p_month) THEN
        RAISE EXCEPTION 'invalid_argument: p_parent and a finite p_month are required'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    SELECT k.schema_name, k.table_name, k.column_name, k.default_oid
    INTO v_schema, v_table, v_col, v_default
    FROM util.partition_range_column(p_parent) k;

    v_start := date_trunc('month', p_month::timestamp) AT TIME ZONE 'UTC';
    v_end   := v_start + interval '1 month';
    v_child := v_table || '_p' || to_char(v_start AT TIME ZONE 'UTC', 'YYYYMM');
    IF length(v_child) > 63 THEN
        RAISE EXCEPTION 'partition_unsupported: partition name % exceeds 63 characters', v_child
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    -- Serializes concurrent maintenance on this parent and makes the existence check race-free.
    EXECUTE format('LOCK TABLE %I.%I IN SHARE UPDATE EXCLUSIVE MODE', v_schema, v_table);

    IF EXISTS (
        SELECT 1
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        CROSS JOIN LATERAL regexp_match(
            pg_get_expr(c.relpartbound, c.oid),
            '^FOR VALUES FROM \(''([^'']+)''\) TO \(''([^'']+)''\)$') AS m
        WHERE i.inhparent = p_parent
          AND m IS NOT NULL
          AND m[1]::timestamptz = v_start
          AND m[2]::timestamptz = v_end
    ) THEN
        RETURN false;
    END IF;

    IF v_default = 0 THEN
        EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES FROM (%L) TO (%L)',
                       v_schema, v_child, v_schema, v_table, v_start, v_end);
        RETURN true;
    END IF;

    -- A DEFAULT partition exists. CREATE ... PARTITION OF would take ACCESS EXCLUSIVE on the parent
    -- (deadlock-prone against queries holding the parent and then wanting DEFAULT) and fails when
    -- DEFAULT holds rows in range. So always: loose table, move rows out of DEFAULT, ATTACH, which
    -- needs only SHARE UPDATE EXCLUSIVE on the parent. Lock order is parent first (above), then
    -- DEFAULT; the parent lock is never escalated afterwards. DEFAULT stays locked from here to the
    -- end of the transaction so no row can slip in between the move and the ATTACH check.
    EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', v_default::regclass);

    SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) INTO v_cols
    FROM pg_attribute a
    WHERE a.attrelid = p_parent AND a.attnum > 0 AND NOT a.attisdropped AND a.attgenerated = '';

    EXECUTE format('CREATE TABLE %I.%I (LIKE %I.%I INCLUDING DEFAULTS INCLUDING CONSTRAINTS)',
                   v_schema, v_child, v_schema, v_table);
    EXECUTE format(
        'WITH moved AS (DELETE FROM %s WHERE %I >= %L AND %I < %L RETURNING %s) '
        'INSERT INTO %I.%I (%s) SELECT %s FROM moved',
        v_default::regclass, v_col, v_start, v_col, v_end, v_cols,
        v_schema, v_child, v_cols, v_cols);
    EXECUTE format('ALTER TABLE %I.%I ATTACH PARTITION %I.%I FOR VALUES FROM (%L) TO (%L)',
                   v_schema, v_table, v_schema, v_child, v_start, v_end);
    RETURN true;
END
$$;

-- util.ensure_monthly_partitions: partitions for the current UTC month and p_months_ahead months
-- after it. Returns how many were newly created.
CREATE FUNCTION util.ensure_monthly_partitions(p_parent regclass, p_months_ahead int) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
    SET TimeZone = 'UTC'
    SET DateStyle = 'ISO, MDY'
AS $$
DECLARE
    v_current date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    v_created int := 0;
BEGIN
    IF p_parent IS NULL OR p_months_ahead IS NULL OR p_months_ahead < 0 THEN
        RAISE EXCEPTION 'invalid_argument: p_parent and a non-negative p_months_ahead are required'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    FOR i IN 0..p_months_ahead LOOP
        IF util.create_monthly_partition(p_parent, (v_current + make_interval(months => i))::date) THEN
            v_created := v_created + 1;
        END IF;
    END LOOP;
    RETURN v_created;
END
$$;

-- util.drop_partitions_older_than: drops partitions whose upper bound is <= now() - p_keep.
-- Partitions are identified by their bounds (never by name) and DEFAULT is never dropped.
-- p_keep has a 90-day floor so a retention call can never wipe recent audit or changelog history.
-- Returns how many were dropped.
CREATE FUNCTION util.drop_partitions_older_than(p_parent regclass, p_keep interval) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
    SET TimeZone = 'UTC'
    SET DateStyle = 'ISO, MDY'
AS $$
DECLARE
    v_schema  text;
    v_table   text;
    v_cutoff  timestamptz;
    v_child   record;
    v_dropped int := 0;
BEGIN
    IF p_parent IS NULL OR p_keep IS NULL THEN
        RAISE EXCEPTION 'invalid_argument: p_parent and p_keep are required'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF p_keep < interval '90 days' THEN
        RAISE EXCEPTION 'retention_too_short: p_keep must be at least 90 days'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    SELECT k.schema_name, k.table_name INTO v_schema, v_table
    FROM util.partition_range_column(p_parent) k;

    EXECUTE format('LOCK TABLE %I.%I IN SHARE UPDATE EXCLUSIVE MODE', v_schema, v_table);
    v_cutoff := now() - p_keep;

    FOR v_child IN
        SELECT n.nspname AS child_schema, c.relname AS child_name
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        CROSS JOIN LATERAL regexp_match(
            pg_get_expr(c.relpartbound, c.oid),
            '^FOR VALUES FROM \(''([^'']+)''\) TO \(''([^'']+)''\)$') AS m
        WHERE i.inhparent = p_parent
          AND m IS NOT NULL                  -- DEFAULT (and anything not a plain range) has no match
          AND m[2]::timestamptz <= v_cutoff
        ORDER BY m[2]::timestamptz
    LOOP
        EXECUTE format('DROP TABLE %I.%I', v_child.child_schema, v_child.child_name);
        v_dropped := v_dropped + 1;
    END LOOP;
    RETURN v_dropped;
END
$$;

REVOKE EXECUTE ON FUNCTION util.partition_range_column(regclass) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION util.create_monthly_partition(regclass, date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION util.ensure_monthly_partitions(regclass, int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION util.drop_partitions_older_than(regclass, interval) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION util.create_monthly_partition(regclass, date) TO rbac_admin;
GRANT EXECUTE ON FUNCTION util.ensure_monthly_partitions(regclass, int) TO rbac_admin;
GRANT EXECUTE ON FUNCTION util.drop_partitions_older_than(regclass, interval) TO rbac_admin;
