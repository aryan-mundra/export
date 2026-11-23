-- Drops the Export module's own VER_EXPORT_* tables so they can be created again
-- empty with ver-export-schema.sql. Leaves the source tables (VER_VERSION, VER_VERSION_MEMBER, GS_*) alone.
-- Test databases only. Run with psql -f, or in pgAdmin's Query Tool.
-- First the table of every data version (ver_<list>_v<major>_<minor>), named in ver_export_snapshot.
DO $$
DECLARE version_table text;
BEGIN
    IF to_regclass('ver_export_snapshot') IS NOT NULL THEN
        FOR version_table IN SELECT table_name FROM ver_export_snapshot LOOP
            EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(version_table);
        END LOOP;
    END IF;
END $$;
DROP TABLE IF EXISTS VER_EXPORT_SNAPSHOT;
DROP TABLE IF EXISTS VER_EXPORT_SNAPSHOT_SERIES;
DROP TABLE IF EXISTS VER_EXPORT_TEMPLATE_FILTER;
DROP TABLE IF EXISTS VER_EXPORT_FILE_RECORD;
DROP TABLE IF EXISTS VER_EXPORT_FILE;
DROP TABLE IF EXISTS VER_EXPORT_DELIVERY;
DROP TABLE IF EXISTS VER_EXPORT_QUARANTINE;
DROP TABLE IF EXISTS VER_EXPORT_AUDIT;
DROP TABLE IF EXISTS VER_EXPORT_JOB_SUBSCRIBER;
DROP TABLE IF EXISTS VER_EXPORT_JOB;
DROP TABLE IF EXISTS VER_EXPORT_TEMPLATE_FIELD;
DROP TABLE IF EXISTS VER_EXPORT_TEMPLATE;
DROP TABLE IF EXISTS VER_EXPORT_SUBSCRIBER;
DROP TABLE IF EXISTS VER_EXPORT_USER;
