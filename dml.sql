  SELECT a.archive_id,
         a.archive_path,
         a.archive_name,
         ROUND (a.archive_size / (1 * 1024 * 1024), 1) AS archive_size_mb,
         e.entry_path,
         e.entry_name,
         ROUND (e.uncompressed_size / (1 * 1024 * 1024), 1) AS uncompressed_size_mb,
         e.crc,
         e.entry_id
    FROM myschema.archives a
         JOIN myschema.archive_entries e ON a.archive_id = e.archive_id
   WHERE     UPPER (a.archive_name) LIKE UPPER ('%%')
         AND e.uncompressed_size > 1 * 1024 * 1024
         AND UPPER (e.entry_name) NOT LIKE '%.JP%G'
         AND UPPER (e.entry_name) NOT LIKE '%.PNG'
         AND UPPER (e.entry_name) NOT LIKE '%.NEF'
         AND UPPER (e.entry_name) NOT LIKE '%.PDF'
         AND UPPER (e.entry_name) NOT LIKE '%.TIF%'
ORDER BY archive_path,
         archive_name,
         entry_path,
         entry_name;
