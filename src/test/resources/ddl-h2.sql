CREATE SCHEMA myschema;


CREATE TABLE myschema.archives (
    archive_id            VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT archives_pk PRIMARY KEY,
    tms_insert            TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert            VARCHAR (128) DEFAULT USER NOT NULL,
    archive_path          VARCHAR (1024),
    archive_name          VARCHAR (1024) NOT NULL,
    archive_size          NUMERIC (19, 0) NOT NULL,
    last_modified         TIMESTAMP (9) NOT NULL,
    archive_format        VARCHAR (255) NOT NULL CONSTRAINT archives_cc_archive_format CHECK (archive_format IN ('ZIP', 'RAR', '7Z')),
    entry_count           NUMERIC (10, 0) NOT NULL,
    total_packed_size     NUMERIC (19, 0),
    total_unpacked_size   NUMERIC (19, 0) NOT NULL,
    archive_comment       CLOB,
    CONSTRAINT archives_uk_archive_name_archive_size UNIQUE (archive_name, archive_size) 
);

CREATE INDEX myschema.archives_ix_archive_path ON myschema.archives (archive_path);
CREATE INDEX myschema.archives_ix_archive_name ON myschema.archives (archive_name);


CREATE TABLE myschema.zip_archives (
    archive_id                    VARCHAR (22) /* NUMERIC (19, 0) */ CONSTRAINT zip_archives_pk PRIMARY KEY,
    tms_insert                    TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert                    VARCHAR (128) DEFAULT USER NOT NULL,
    encoding                      VARCHAR (255),
    first_local_file_hdr_offset   NUMERIC (19, 0),
    CONSTRAINT zip_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
);


CREATE TABLE myschema.rar_archives (
    archive_id              VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT rar_archives_pk PRIMARY KEY,
    tms_insert              TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert              VARCHAR (128) DEFAULT USER NOT NULL,
    format_version          VARCHAR (255),
    is_solid                NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_solid CHECK (is_solid IN (0, 1)),
    is_locked               NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_locked CHECK (is_locked IN (0, 1)),
    is_protected            NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_protected CHECK (is_protected IN (0, 1)),
    is_av                   NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_av CHECK (is_av IN (0, 1)),
    is_new_numbering        NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_new_numbering CHECK (is_new_numbering IN (0, 1)),
    is_multi_volume         NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_multi_volume CHECK (is_multi_volume IN (0, 1)),
    is_first_volume         NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_first_volume CHECK (is_first_volume IN (0, 1)),
    is_encrypted            NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_encrypted CHECK (is_encrypted IN (0, 1)),
    is_password_protected   NUMERIC (1, 0) CONSTRAINT rar_archives_cc_is_password_protected CHECK (is_password_protected IN (0, 1)),
    has_archive_comment     NUMERIC (1, 0) CONSTRAINT rar_archives_cc_has_archive_comment CHECK (has_archive_comment IN (0, 1)),
    high_pos_av             NUMERIC (19, 0),
    pos_av                  NUMERIC (19, 0),
    encrypt_version         NUMERIC (19, 0),
    recovery_data_size      NUMERIC (19, 0),
    flags                   VARCHAR (4) CONSTRAINT rar_archives_cc_flags CHECK (LENGTH(flags) = 4),
    CONSTRAINT rar_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
);


CREATE TABLE myschema.sevenzip_archives (
    archive_id   VARCHAR (22) /* NUMERIC (19, 0) */ CONSTRAINT sevenzip_archives_pk PRIMARY KEY,
    tms_insert   TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert   VARCHAR (128) DEFAULT USER NOT NULL,
    CONSTRAINT sevenzip_archives_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id)
);


CREATE TABLE myschema.archive_entries (
    entry_id              VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT archive_entries_pk PRIMARY KEY,
    tms_insert            TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert            VARCHAR (128) DEFAULT USER NOT NULL,
    archive_id            VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL,
    entry_index           NUMERIC (19, 0) NOT NULL,
    entry_path            VARCHAR (1024),
    entry_name            VARCHAR (1024) NOT NULL,
    uncompressed_size     NUMERIC (19, 0),
    compressed_size       NUMERIC (19, 0),
    last_modified         TIMESTAMP (9) WITH TIME ZONE,
    creation_time         TIMESTAMP (9) WITH TIME ZONE,
    last_access           TIMESTAMP (9) WITH TIME ZONE,
    crc                   VARCHAR (8) CONSTRAINT archive_entries_cc_crc CHECK (LENGTH(crc) = 8),
    platform              VARCHAR (255),
    method                NUMERIC (10, 0),
    internal_attributes   NUMERIC (19, 0),
    external_attributes   NUMERIC (19, 0),
    unix_mode             NUMERIC (19, 0),
    data_offset           NUMERIC (19, 0),
    version_required      NUMERIC (10, 0),
    is_directory          NUMERIC (1, 0) NOT NULL CONSTRAINT archive_entries_cc_is_directory CHECK (is_directory IN (0, 1)),
    is_encrypted          NUMERIC (1, 0) NOT NULL CONSTRAINT archive_entries_cc_is_encrypted CHECK (is_encrypted IN (0, 1)),
    is_unicode            NUMERIC (1, 0) CONSTRAINT archive_entries_cc_is_unicode CHECK (is_unicode IN (0, 1)),
    is_symbolic_link      NUMERIC (1, 0) CONSTRAINT archive_entries_cc_is_symbolic_link CHECK (is_symbolic_link IN (0, 1)),
    entry_comment         CLOB,
    CONSTRAINT archive_entries_uk_archive_id_entry_index UNIQUE (archive_id, entry_index),
    CONSTRAINT archive_entries_fk_archive_id FOREIGN KEY (archive_id) REFERENCES myschema.archives (archive_id),
    CONSTRAINT archive_entries_cc_is_directory_crc CHECK (NOT (is_directory = 1 AND crc IS NOT NULL)),
    CONSTRAINT archive_entries_cc_is_directory_uncompressed_size CHECK (NOT (is_directory = 1 AND uncompressed_size IS NOT NULL)),
    CONSTRAINT archive_entries_cc_is_directory_compressed_size CHECK (NOT (is_directory = 1 AND compressed_size IS NOT NULL))
);

CREATE INDEX myschema.archive_entries_fx_archive_id ON myschema.archive_entries (archive_id);


CREATE TABLE myschema.zip_archive_entries (
    entry_id               VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT zip_archive_entries_pk PRIMARY KEY,
    tms_insert             TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert             VARCHAR (128) DEFAULT USER NOT NULL,
    flag_data_descriptor   NUMERIC (1, 0) NOT NULL CONSTRAINT zip_archive_entries_cc_flag_data_descriptor CHECK (flag_data_descriptor IN (0, 1)),
    encryption_method      VARCHAR (255),
    extra_field_count      NUMERIC (19, 0),
    local_extra_length     NUMERIC (19, 0),
    central_extra_length   NUMERIC (19, 0),
    version_made_by        NUMERIC (10, 0),
    disk_number_start      NUMERIC (19, 0),
    CONSTRAINT zip_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
);


CREATE TABLE myschema.rar_archive_entries (
    entry_id            VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT rar_archive_entries_pk PRIMARY KEY,
    tms_insert          TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert          VARCHAR (128) DEFAULT USER NOT NULL,
    recovery_sectors    NUMERIC (10, 0),
    is_solid            NUMERIC (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_solid CHECK (is_solid IN (0, 1)),
    is_split_before     NUMERIC (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_split_before CHECK (is_split_before IN (0, 1)),
    is_split_after      NUMERIC (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_split_after CHECK (is_split_after IN (0, 1)),
    is_rar5_container   NUMERIC (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_rar5_container CHECK (is_rar5_container IN (0, 1)),
    is_rar5_family      NUMERIC (1, 0) NOT NULL CONSTRAINT rar_archive_entries_cc_is_rar5_family CHECK (is_rar5_family IN (0, 1)),
    hash_type           VARCHAR (255),
    hash_digest         VARCHAR (2048),
    CONSTRAINT rar_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
);


CREATE TABLE myschema.sevenzip_archive_entries (
    entry_id             VARCHAR (22) /* NUMERIC (19, 0) */ NOT NULL CONSTRAINT sevenzip_archive_entries_pk PRIMARY KEY,
    tms_insert           TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usr_insert           VARCHAR (128) DEFAULT USER NOT NULL,
    methods              VARCHAR (255),
    windows_attributes   VARCHAR (8) CONSTRAINT sevenzip_archive_entries_cc_windows_attributes CHECK (LENGTH(windows_attributes) = 8),
    has_crc              NUMERIC (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_has_crc CHECK (has_crc IN (0, 1)),
    has_stream           NUMERIC (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_has_stream CHECK (has_stream IN (0, 1)),
    empty_stream         NUMERIC (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_empty_stream CHECK (empty_stream IN (0, 1)),
    anti_item            NUMERIC (1, 0) NOT NULL CONSTRAINT sevenzip_archive_entries_cc_anti_item CHECK (anti_item IN (0, 1)),
    CONSTRAINT sevenzip_archive_entries_fk_entry_id FOREIGN KEY (entry_id) REFERENCES myschema.archive_entries (entry_id)
);
