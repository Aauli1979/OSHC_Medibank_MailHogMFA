# Container dependency-resolution fix

Date: 2026-09-29

## Reproduced failure

The original attachment and the first MailHog MFA port used the same backend POM:
Spring Boot 3.5.4, Yubico 2.9.0 and the parent's Maven Dependency Plugin 3.8.1.
`mvn verify` passed, but the container's separate `dependency:go-offline` goal failed.
The failure was reproduced with this dependency path:

```text
webauthn-server-core:2.9.0
  -> yubico-util:2.9.0
  -> jackson-dataformat-cbor:2.18.5
  -> jackson-databind:2.18.9-SNAPSHOT
  -> missing jackson-base:2.18.9-SNAPSHOT
```

Yubico's published parent POM declares version ranges for Jackson, Guava, CBOR,
HTTP Client and SLF4J. The 3.8.1 prefetch goal resolves individual dependency
coordinates without passing the project's managed dependency list into that
resolution. Consequently, a successful normal build did not prove that the
container prefetch step could resolve the same graph.

## Changes

- Preserve Java 21, Spring Boot 3.5.4, Yubico 2.9.0 and the original application/test
  dependencies from the first attachment.
- Pin Maven Dependency Plugin to **3.9.0**. Its `go-offline` implementation passes
  the project's dependency management into Maven Resolver.
- Explicitly retain the Spring Boot release Jackson BOM at **2.19.2**, keeping
  Jackson Core, Databind, Annotations, CBOR and datatype modules aligned.
- Pin Guava to **33.7.1-jre** and CBOR to **4.5.6** through `dependencyManagement`.
  In particular, Yubico's `[4.5.1,5)` CBOR range could otherwise select the
  `5.0.0-alpha2` prerelease. HTTP Client and SLF4J remain managed by Spring Boot.
- Keep the container dependency-cache layer, and run the following package step
  offline so it cannot select a different online dependency graph.

No snapshot repository was added. No dependency-resolution error is ignored.
The application, Passkey functionality and single Compose file are unchanged.

## Rebuild an existing checkout

Replace `backend/pom.xml` and `backend/Containerfile` with the copies in this archive.
Then run from the folder containing `compose.yaml`:

```sh
podman compose build --no-cache backend
podman compose up -d
```

If invoking the provider directly:

```sh
podman-compose build --no-cache backend
podman-compose up -d
```

Docker Compose accepts the corresponding `docker compose` commands. This rebuild
keeps the database volume. There is no need to delete application data or run
`down -v`.

## Independent validation

The fix is checked using a new empty Maven repository and a temporary build folder.
Initially that folder contains only `pom.xml`, matching the container's prefetch
layer. After prefetch succeeds, source files are copied into it and the same cache
is used for offline packaging. See `TEST_REPORT.md` for the executed results.

To repeat the dependency checks without relying on your existing Maven cache:

```sh
cd backend
mvn -B -ntp -Dmaven.repo.local=/tmp/oshc-clean-m2 -DskipTests dependency:go-offline
mvn -B -ntp -o -Dmaven.repo.local=/tmp/oshc-clean-m2 -DskipTests package
mvn -B -ntp -Dmaven.repo.local=/tmp/oshc-clean-m2 verify
mvn -B -ntp -Dmaven.repo.local=/tmp/oshc-clean-m2 dependency:tree
```

Choose a new cache directory for a genuinely clean run. Packaging offline verifies
that the prefetch was sufficient; this is distinct from running Docker or Podman.

## Upstream references

- [Yubico 2.9.0 published parent POM](https://repo.maven.apache.org/maven2/com/yubico/webauthn-server-parent/2.9.0/webauthn-server-parent-2.9.0.pom)
- [Maven Dependency Plugin 3.8.1 prefetch implementation](https://github.com/apache/maven-dependency-plugin/blob/maven-dependency-plugin-3.8.1/src/main/java/org/apache/maven/plugins/dependency/resolvers/GoOfflineMojo.java)
- [Maven Dependency Plugin 3.9.0 prefetch implementation](https://github.com/apache/maven-dependency-plugin/blob/maven-dependency-plugin-3.9.0/src/main/java/org/apache/maven/plugins/dependency/resolvers/GoOfflineMojo.java)
- [Maven dependency management](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html)
