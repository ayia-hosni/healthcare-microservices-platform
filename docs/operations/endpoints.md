# 🌐 Development Endpoints

> Part of [Operations](README.md).

| Component            | URL                      |
| ---------------------- | -------------------------- |
| Identity Service      | `http://localhost:8081`  |
| Patient Service       | `http://localhost:8082`  |
| Doctor Service        | `http://localhost:8083`  |
| Appointment Service   | `http://localhost:8084`  |
| EMR Service           | `http://localhost:8085`  |
| Billing Service       | `http://localhost:8086`  |
| Notification Service  | `http://localhost:8087`  |
| Audit Service         | `http://localhost:8088`  |
| Analytics Service     | `http://localhost:8089`  |
| GraphQL Gateway       | `http://localhost:8090`  |
| Adminer (DB browser)  | `http://localhost:8091`  |
| RabbitMQ Management   | `http://localhost:15672` |
| Elasticsearch         | `http://localhost:9200`  |
| MinIO Console         | `http://localhost:9001`  |
| Prometheus            | `http://localhost:9090`  |
| Grafana               | `http://localhost:3000`  |
| Zipkin                | `http://localhost:9411`  |

API documentation is available on each application service at `/docs.html`. These are the
Docker Compose default ports — see [Local Development](../development/local-development.md)
for the other workflows (Minikube, hybrid, fully native) and how their endpoints differ.

**Adminer login:** System = PostgreSQL, Server = `postgres`, then either the `postgres`
superuser (password `postgres`) to see every database, or one service's own
`<service>_user` / `<service>_pass` (e.g. `patient_user` / `patient_pass`) scoped to just its
own `*_db` — same credentials `docker-compose.yml`'s `POSTGRES_MULTIPLE_DATABASES` sets up.
