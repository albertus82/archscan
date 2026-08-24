-- SYS
CREATE TABLESPACE myschema_data DATAFILE 'myschema_data.dat' SIZE 1M AUTOEXTEND ON;
CREATE TABLESPACE myschema_idx  DATAFILE 'myschema_idx.dat'  SIZE 1M AUTOEXTEND ON;

CREATE TEMPORARY TABLESPACE myschema_tmp TEMPFILE 'myschema_tmp.dat' SIZE 2M AUTOEXTEND ON;

CREATE USER myschema IDENTIFIED BY /****/ DEFAULT TABLESPACE myschema_data TEMPORARY TABLESPACE myschema_tmp;
GRANT CREATE SESSION           TO myschema;
GRANT CREATE SEQUENCE          TO myschema;
GRANT CREATE TABLE             TO myschema;
GRANT CREATE VIEW              TO myschema;
GRANT CREATE MATERIALIZED VIEW TO myschema;
GRANT UNLIMITED TABLESPACE     TO myschema;

CREATE USER myschema_appl IDENTIFIED BY /****/;
GRANT CREATE SESSION TO myschema_appl;


-- MYSCHEMA
--DROP SEQUENCE myschema.archives_seq;
--DROP SEQUENCE myschema.archive_entries_seq;

--DROP TABLE myschema.zip_archive_entries      PURGE;
--DROP TABLE myschema.rar_archive_entries      PURGE;
--DROP TABLE myschema.sevenzip_archive_entries PURGE;
--DROP TABLE myschema.archive_entries          PURGE;
--DROP TABLE myschema.zip_archives             PURGE;
--DROP TABLE myschema.rar_archives             PURGE;
--DROP TABLE myschema.sevenzip_archives        PURGE;
--DROP TABLE myschema.archives                 PURGE;


CREATE SEQUENCE myschema.archives_seq START WITH 1 ORDER;
GRANT SELECT ON myschema.archives_seq TO myschema_appl;

CREATE SEQUENCE myschema.archive_entries_seq START WITH 1 ORDER;
GRANT SELECT ON myschema.archive_entries_seq TO myschema_appl;


CREATE TABLE myschema.archives (
    archive_id            VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT archives_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert            TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert            VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    archive_path          VARCHAR2 (1024 CHAR),
    archive_name          VARCHAR2 (1024 CHAR) NOT NULL,
    archive_size          NUMBER (19, 0) NOT NULL,
    last_modified         TIMESTAMP (9) WITH TIME ZONE NOT NULL,
    archive_format        VARCHAR2 (255 CHAR) NOT NULL CONSTRAINT archives_cc_archive_format CHECK (archive_format IN ('ZIP', 'RAR', '7Z')),
    entry_count           NUMBER (10, 0) NOT NULL,
    total_packed_size     NUMBER (19, 0),
    total_unpacked_size   NUMBER (19, 0) NOT NULL,
    archive_comment       CLOB,
    CONSTRAINT archives_uk_archive_name_archive_size UNIQUE (archive_name, archive_size) USING INDEX TABLESPACE myschema_idx
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

ALTER TABLE myschema.archives MODIFY LOB (archive_comment) (COMPRESS);

CREATE INDEX myschema.archives_ix_archive_path ON myschema.archives (archive_path) TABLESPACE myschema_idx;
CREATE INDEX myschema.archives_ix_archive_name ON myschema.archives (archive_name) TABLESPACE myschema_idx;

GRANT SELECT, INSERT, DELETE ON myschema.archives TO myschema_appl;


CREATE TABLE myschema.zip_archives (
    archive_id                    VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ CONSTRAINT zip_archives_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert                    TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert                    VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    encoding                      VARCHAR2 (255 CHAR),
    first_local_file_hdr_offset   NUMBER (19, 0),
    CONSTRAINT zip_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.zip_archives TO myschema_appl;


CREATE TABLE myschema.rar_archives (
    archive_id              VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT rar_archives_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert              TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert              VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    format_version          VARCHAR2 (255 CHAR),
    is_solid                NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_solid CHECK (is_solid IN (0, 1)),
    is_locked               NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_locked CHECK (is_locked IN (0, 1)),
    is_protected            NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_protected CHECK (is_protected IN (0, 1)),
    is_av                   NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_av CHECK (is_av IN (0, 1)),
    is_new_numbering        NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_new_numbering CHECK (is_new_numbering IN (0, 1)),
    is_multi_volume         NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_multi_volume CHECK (is_multi_volume IN (0, 1)),
    is_first_volume         NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_first_volume CHECK (is_first_volume IN (0, 1)),
    is_encrypted            NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_encrypted CHECK (is_encrypted IN (0, 1)),
    is_password_protected   NUMBER (1, 0) CONSTRAINT rar_archives_cc_is_password_protected CHECK (is_password_protected IN (0, 1)),
    has_archive_comment     NUMBER (1, 0) CONSTRAINT rar_archives_cc_has_archive_comment CHECK (has_archive_comment IN (0, 1)),
    high_pos_av             NUMBER (19, 0),
    pos_av                  NUMBER (19, 0),
    encrypt_version         NUMBER (19, 0),
    recovery_data_size      NUMBER (19, 0),
    flags                   VARCHAR2 (4 BYTE) CONSTRAINT rar_archives_cc_flags CHECK (LENGTH(flags) = 4),
    CONSTRAINT rar_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.rar_archives TO myschema_appl;


CREATE TABLE myschema.sevenzip_archives (
    archive_id   VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ CONSTRAINT sevenzip_archives_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert   TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert   VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    CONSTRAINT sevenzip_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.sevenzip_archives TO myschema_appl;


CREATE TABLE myschema.archive_entries (
    entry_id              VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT archive_entries_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert            TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert            VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    archive_id            VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL,
    entry_index           NUMBER (19, 0) NOT NULL,
    entry_path            VARCHAR2 (1024 CHAR),
    entry_name            VARCHAR2 (1024 CHAR) NOT NULL,
    uncompressed_size     NUMBER (19, 0),
    compressed_size       NUMBER (19, 0),
    last_modified         TIMESTAMP (9) WITH TIME ZONE,
    creation_time         TIMESTAMP (9) WITH TIME ZONE,
    last_access           TIMESTAMP (9) WITH TIME ZONE,
    crc                   VARCHAR2 (8 BYTE) CONSTRAINT archive_entries_cc_crc CHECK (LENGTH(crc) = 8),
    platform              VARCHAR2 (255 CHAR),
    method                NUMBER (10, 0),
    internal_attributes   NUMBER (19, 0),
    external_attributes   NUMBER (19, 0),
    unix_mode             NUMBER (19, 0),
    data_offset           NUMBER (19, 0),
    version_required      NUMBER (10, 0),
    is_directory          NUMBER (1, 0) NOT NULL CONSTRAINT archive_entries_cc_is_directory CHECK (is_directory IN (0, 1)),
    is_encrypted          NUMBER (1, 0) NOT NULL CONSTRAINT archive_entries_cc_is_encrypted CHECK (is_encrypted IN (0, 1)),
    is_unicode            NUMBER (1, 0) CONSTRAINT archive_entries_cc_is_unicode CHECK (is_unicode IN (0, 1)),
    is_symbolic_link      NUMBER (1, 0) CONSTRAINT archive_entries_cc_is_symbolic_link CHECK (is_symbolic_link IN (0, 1)),
    entry_comment         CLOB,
    CONSTRAINT archive_entries_uk_archive_id_entry_index UNIQUE (archive_id, entry_index) USING INDEX COMPRESS TABLESPACE myschema_idx,
    CONSTRAINT archive_entries_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id),
    CONSTRAINT archive_entries_cc_is_directory_crc CHECK (NOT (is_directory = 1 AND crc IS NOT NULL)),
    CONSTRAINT archive_entries_cc_is_directory_uncompressed_size CHECK (NOT (is_directory = 1 AND uncompressed_size IS NOT NULL)),
    CONSTRAINT archive_entries_cc_is_directory_compressed_size CHECK (NOT (is_directory = 1 AND compressed_size IS NOT NULL))
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

ALTER TABLE myschema.archive_entries MODIFY LOB (entry_comment) (COMPRESS);

CREATE INDEX myschema.archive_entries_fx_archive_id ON myschema.archive_entries (archive_id) TABLESPACE myschema_idx;

GRANT SELECT, INSERT, DELETE ON myschema.archive_entries TO myschema_appl;


CREATE TABLE myschema.zip_archive_entries (
    entry_id               VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT zip_archive_entries_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert             TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert             VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    flag_data_descriptor   NUMBER (1, 0) NOT NULL CONSTRAINT zip_archive_entries_cc_flag_data_descriptor CHECK (flag_data_descriptor IN (0, 1)),
    encryption_method      VARCHAR2 (255 CHAR),
    extra_field_count      NUMBER (19, 0),
    local_extra_length     NUMBER (19, 0),
    central_extra_length   NUMBER (19, 0),
    version_made_by        NUMBER (10, 0),
    disk_number_start      NUMBER (19, 0),
    CONSTRAINT zip_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.zip_archive_entries TO myschema_appl;


CREATE TABLE myschema.rar_archive_entries (
    entry_id            VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT rar_archive_entries_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert          TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert          VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    recovery_sectors    NUMBER (10, 0),
    is_solid            NUMBER (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_solid CHECK (is_solid IN (0, 1)),
    is_split_before     NUMBER (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_split_before CHECK (is_split_before IN (0, 1)),
    is_split_after      NUMBER (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_split_after CHECK (is_split_after IN (0, 1)),
    is_rar5_container   NUMBER (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_rar5_container CHECK (is_rar5_container IN (0, 1)),
    is_rar5_family      NUMBER (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_rar5_family CHECK (is_rar5_family IN (0, 1)),
    hash_type           VARCHAR2 (255 CHAR),
    hash_digest         VARCHAR2 (2048 BYTE),
    CONSTRAINT rar_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.rar_archive_entries TO myschema_appl;


CREATE TABLE myschema.sevenzip_archive_entries (
    entry_id             VARCHAR2 (22 BYTE) /* NUMBER (19, 0) */ NOT NULL CONSTRAINT sevenzip_archive_entries_pk PRIMARY KEY USING INDEX TABLESPACE myschema_idx,
    tms_insert           TIMESTAMP (3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    usr_insert           VARCHAR2 (128 CHAR) DEFAULT USER NOT NULL,
    methods              VARCHAR2 (255 CHAR),
    windows_attributes   VARCHAR2 (8 BYTE) CONSTRAINT sevenzip_archive_entries_cc_windows_attributes CHECK (LENGTH(windows_attributes) = 8),
    has_crc              NUMBER (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_has_crc CHECK (has_crc IN (0, 1)),
    has_stream           NUMBER (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_has_stream CHECK (has_stream IN (0, 1)),
    empty_stream         NUMBER (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_empty_stream CHECK (empty_stream IN (0, 1)),
    anti_item            NUMBER (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_anti_item CHECK (anti_item IN (0, 1)),
    CONSTRAINT sevenzip_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
)
ROW STORE COMPRESS ADVANCED
TABLESPACE myschema_data;

GRANT SELECT, INSERT, DELETE ON myschema.sevenzip_archive_entries TO myschema_appl;


--TRUNCATE TABLE myschema.zip_archive_entries      DROP ALL STORAGE;
--TRUNCATE TABLE myschema.rar_archive_entries      DROP ALL STORAGE;
--TRUNCATE TABLE myschema.sevenzip_archive_entries DROP ALL STORAGE;
--TRUNCATE TABLE myschema.archive_entries          DROP ALL STORAGE;
--TRUNCATE TABLE myschema.zip_archives             DROP ALL STORAGE;
--TRUNCATE TABLE myschema.rar_archives             DROP ALL STORAGE;
--TRUNCATE TABLE myschema.sevenzip_archives        DROP ALL STORAGE;
--TRUNCATE TABLE myschema.archives                 DROP ALL STORAGE;
