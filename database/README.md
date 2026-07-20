# Database

`schema.sql` defines the application-owned `users` and `fraud_news` tables. Docker Compose mounts this file into MySQL's first-start initialization directory.

For an existing environment, review and apply the DDL through the normal database change process. The current project does not yet use a versioned migration tool; adopting Flyway or Liquibase is listed as a roadmap item.
