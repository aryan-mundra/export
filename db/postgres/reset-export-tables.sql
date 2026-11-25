-- Drops every table of the Export module (ver_export_*), including those of earlier versions of the module, so they
-- can be created again, empty, with ver-export-schema.sql. The source tables (the table groups) are not touched.
-- Test databases only: this deletes lists, subscribers, data versions and send history.
-- Run with psql -f, or in pgAdmin's Query Tool.

-- Tables of earlier versions of the module: per-version data tables named in ver_export_snapshot, then the rest.
DO $$
DECLARE version_table text;
BEGIN
    IF to_regclass('ver_export_snapshot') IS NOT NULL THEN
        FOR version_table IN SELECT table_name FROM ver_export_snapshot LOOP
            EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(version_table);
        END LOOP;
    END IF;
END $$;
DROP TABLE IF EXISTS ver_export_snapshot;
DROP TABLE IF EXISTS ver_export_snapshot_series;
DROP TABLE IF EXISTS ver_export_file_record;
DROP TABLE IF EXISTS ver_export_file;
DROP TABLE IF EXISTS ver_export_quarantine;
DROP TABLE IF EXISTS ver_export_group_record;
DROP TABLE IF EXISTS ver_export_group_version;

-- Current tables.
DROP TABLE IF EXISTS ver_export_data_record;
DROP TABLE IF EXISTS ver_export_data_version;
DROP TABLE IF EXISTS ver_export_delivery;
DROP TABLE IF EXISTS ver_export_audit;
DROP TABLE IF EXISTS ver_export_job_subscriber;
DROP TABLE IF EXISTS ver_export_job;
DROP TABLE IF EXISTS ver_export_template_filter;
DROP TABLE IF EXISTS ver_export_template_field;
DROP TABLE IF EXISTS ver_export_template;
DROP TABLE IF EXISTS ver_export_subscriber;
DROP TABLE IF EXISTS ver_export_user;
-- ver_export_dump_import is kept: it records which dump this database holds.
