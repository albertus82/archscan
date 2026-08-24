# Archive Scanner

```
Usage: archscan [-RLThV] [-M=<mode>] [-K=<type>] -J=<jdbc-url> [-S=<schema>]
                [-U=<username>] [-P[=<password>]] <path>
Scans ZIP, RAR and 7-Zip archives and stores their metadata into the configured
database.
      <path>                 Base directory to scan.
  -R, --recursive            Scan subdirectories recursively.
  -L, --follow-links         Follow symbolic links while scanning.
  -T, --ignore-date          Ignore the last modified date when matching
                               archives against database records; match by path
                               and size only.
  -M, --path-mode=<mode>     How archive paths are stored.
                             Valid values: relative (R), parent (P), absolute
                               (A).
                             Default: relative.
  -K, --key-type=<type>      Type of keys generated for database rows.
                             Valid values: sequence, uuid.
                             Default: uuid.
  -J, --db-url=<jdbc-url>    Database JDBC connection URL.
  -S, --db-schema=<schema>   Database schema.
  -U, --db-user=<username>   Database username.
  -P, --db-password[=<password>]
                             Database password.
                             If omitted, you will be prompted securely. Avoid
                               specifying the password on the command line
                               because it may be visible in process listings,
                               shell history, or logs.
  -h, --help                 Show this help message and exit.
  -V, --version              Print version information and exit.
```
