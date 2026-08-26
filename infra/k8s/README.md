# Kubernetes

## Layout

- **`base/`** — production-shaped manifests: the frontend + all 9 Spring Boot services,
  a namespace, a ConfigMap, a Secret, and an `Ingress` (`base/ingress.yaml`) that's the
  platform's single external entry point — see `docs/adr/0001-api-gateway-ingress.md`.
  It routes `/api/v1/{auth,patients,doctors,appointments}` to their owning service,
  `/graphql`+`/graphiql` to `graphql-gateway` (the GraphQL BFF), and everything else to
  the frontend. `DB_HOST`/`KAFKA_BOOTSTRAP_SERVERS`/`REDIS_HOST`/`RABBITMQ_HOST`/
  `MINIO_ENDPOINT` all point at in-cluster Service DNS names
  (`postgres.healthcare-platform.svc.cluster.local`, etc.) — base does **not** include the
  datastores themselves. A real environment would point those at managed services instead
  (RDS, MSK, ElastiCache, CloudAMQP, S3) and just override the env values; this repo doesn't
  provision that (see the comments in `base/configmap.yaml`). It also doesn't install an
  Ingress *controller* for a real cluster (e.g. the `ingress-nginx` Helm chart on AKS) —
  only the `Ingress` resource itself; see `docs/adr/0001-api-gateway-ingress.md`.
- **`overlays/dev/`** — a self-contained local cluster. On top of base, it adds:
  - `datastores/` — Postgres, Redis, Zookeeper+Kafka, RabbitMQ, and MinIO as plain
    single-replica Deployments, named to match the FQDNs base already hardcodes.
    Elasticsearch/Zipkin/Prometheus/Grafana are skipped — no service code actually talks to
    Elasticsearch yet (README's "extension points" list), and the observability stack isn't
    needed to run the app.
  - `patches/` — see "Deviations from base" below.
  - a real (not placeholder) `platform-secrets`, paired with the datastore credentials.
  - `replicas:` overrides (1 per service) and lowered resource requests/limits, sized to fit
    a local minikube VM.

## Deviations from base (dev overlay only)

| What | Why |
|---|---|
| Kafka runs PLAINTEXT, no SASL/TLS | docker-compose's Kafka setup uses SASL_SSL with a generated dev CA (`infra/docker/kafka/`). Replicating certs + JAAS in-cluster is a lot of moving parts for a disposable local cluster. `patches/kafka-plaintext.yaml` overrides `spring.kafka.properties.security.protocol` to `PLAINTEXT` on all 9 services to match. |
| analytics-service's Quartz job store is `memory`, not `jdbc` | The `jdbc` store needs Quartz's standard `QRTZ_*` Postgres tables, which **no script in this repo creates** — `application.yml` even says as much ("applied once at infra-provisioning time"). Against a fresh Postgres, `SchedulerFactoryBean` fails at startup and the pod crash-loops. `patches/quartz-memory.yaml` fixes this; the tradeoff is the daily report job doesn't survive a pod restart, which is fine for dev. |
| `imagePullPolicy: IfNotPresent` everywhere | Every image is tagged `:latest`, which defaults to `Always` — the kubelet would try to pull `healthcare-platform/doctor-service:latest` etc. from a real registry instead of using what you built locally. |
| Lower CPU/memory requests | Base assumes a real cluster with room to spare (9 services × 250m/384Mi requests + 2 replicas each). Dev overlay drops that to 1 replica and 150m/320Mi per service so the whole stack fits in a modest minikube VM. |

## Running it on minikube

**Check your resources first.** The full dev stack (9 services + frontend + 6 datastores, even
at 1 replica each) requests roughly 3 CPU / 5.5Gi memory. If you're on Docker Desktop, make
sure it's allocated enough (Settings → Resources) *before* sizing minikube — minikube's
docker driver runs inside Docker Desktop's own VM, so it can only use what Docker Desktop
itself has. If Docker Desktop is already running other unrelated containers, account for
those too.

```bash
minikube start --cpus=4 --memory=7168 --driver=docker
infra/k8s/scripts/deploy-to-minikube.sh
```

The script enables minikube's `ingress` addon, builds all 9 backend images (full Maven
build per service — slow the first time, fast afterwards from Docker's layer cache) and
the frontend image, loads all 10 into minikube, applies `overlays/dev`, and waits for
deployments to become available. Everything is reachable through the Ingress — see
"Open the app" in the main README, or:

```bash
kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80   # -> http://localhost:8080/
```

Useful follow-ups:

```bash
kubectl -n healthcare-platform get pods -w      # watch startup
kubectl -n healthcare-platform logs deploy/appointment-service
```

Rebuilding after a code change: re-run the same script (Docker's cache makes unchanged
services nearly instant), or build+load just the one service you changed and
`kubectl -n healthcare-platform rollout restart deployment/<name>`.

### GitOps instead of manual `kubectl apply -k`

`infra/argocd/` wires this same `overlays/dev` output up to ArgoCD instead — see
`infra/argocd/README.md` and [ADR-0005](../../docs/adr/0005-gitops-with-argocd.md). It
still needs images built+loaded into minikube first (the deploy script above); what it
replaces is manually re-running `kubectl apply -k` for manifest-only changes.

### Startup order note

There's no init-container gating on Postgres/Kafka being ready — the 9 services will start,
fail Flyway/JPA validation against a not-yet-ready Postgres, and get restarted by Kubernetes
until Postgres (and their own DB/user, created by the init script in
`datastores/postgres.yaml`) is up. Expect a few `CrashLoopBackOff` cycles on a cold start;
they resolve on their own within a minute or two. `emr-service` additionally waits on MinIO
being reachable (it creates its bucket on startup) and will restart the same way if MinIO
isn't up yet.
