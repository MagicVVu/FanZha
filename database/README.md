# 数据库说明

`schema.sql` 定义应用自行维护的 `users` 和 `fraud_news` 表。Docker Compose 会在 MySQL 首次启动时，将该脚本挂载到初始化目录并执行。

对于已有环境，应先评审脚本，再按照常规数据库变更流程执行。当前项目尚未接入版本化迁移工具，后续计划引入 Flyway 或 Liquibase。
