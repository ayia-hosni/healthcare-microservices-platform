# ADR-007: `common` library build and versioning strategy

## Status
Accepted

## Context
Every REST service's Docker build previously used the Maven reactor
(`mvn -f pom.xml -pl common,<service> -am`) to link `common` in-memory, which required
every service's Dockerfile to `COPY` every *other* service's `pom.xml` just to satisfy
the reactor's module list — a real coupling smell for microservice build isolation
(patient-service's build shouldn't need billing-service's pom.xml to exist).

## Decision
Each service's Dockerfile now `mvn install`s `common` into the local Maven repo as a
standalone single-project build, then builds the target service as an ordinary
single-project build that resolves `common` from the local repo like any other
dependency. No reactor, no sibling pom/source references, in any Dockerfile.

`common`'s version stays pinned to `${project.version}` (the monorepo's shared version)
for now — this repo has one team and one release train, so there's no benefit yet to
independent semantic versioning for `common`.

We are **not** publishing `common` to a real artifact registry (GitHub Packages, Nexus,
Artifactory) yet. There's no CI/CD pipeline in this repo to drive a publish step, and no
consumer of `common` outside this monorepo to justify the operational cost of running
or depending on a registry.

## Consequences
+ Every service Dockerfile is fully self-contained: it only ever references the root
  `pom.xml`, `common/`, and its own directory.
+ Adding an 11th service touches exactly one new Dockerfile, never the existing ten.
+ `common` is still a normal versioned Maven dependency from each service's point of
  view, not a reactor-linked sibling.
- `common` rebuilds (recompiles) once per service Docker build rather than being
  fetched pre-built from a registry — acceptable given `common` is small and the
  `.m2` BuildKit cache mount avoids any network cost.
- `common`'s version is still coupled to the monorepo's release version. **Migration
  trigger**: once a CI/CD pipeline exists, or a second team/repo needs to consume
  `common` independently, move to publishing versioned `common` artifacts to GitHub
  Packages (lowest-friction option — no hosting, integrates with GitHub Actions) or a
  self-hosted Nexus/Artifactory if a private, on-prem-style registry story is wanted.
  At that point `common`'s version should decouple from `${project.version}` and each
  service would pin an explicit `common` version instead.
