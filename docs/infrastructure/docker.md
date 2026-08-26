# 🐳 Containerization

> Part of [Infrastructure](README.md).

The complete platform can be launched locally with Docker Compose.

The development environment includes PostgreSQL, Redis, Kafka, Zookeeper, RabbitMQ,
Elasticsearch, MinIO, Prometheus, Grafana, and Zipkin, alongside all nine Spring Boot
domain services plus `graphql-gateway`. Application containers include health checks and
resource limits to better approximate production deployment behavior. The frontend is not
part of `docker-compose.yml` — it always runs on the host (`npm start`).

For the exact commands (and two other ways to run the platform locally), see
[`../development/local-development.md`](../development/local-development.md).
