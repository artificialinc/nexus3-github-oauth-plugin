FROM maven:3-jdk-8 as builder
MAINTAINER matt.brewster@base2s.com
COPY . /build
WORKDIR /build
RUN --mount=type=cache,target=/m2 mvn clean package  -Dmaven.repo.local=/m2 -X

FROM sonatype/nexus3:3.52.0
USER root
COPY --from=builder /build/target/nexus3-github-oauth-plugin-*.kar /opt/sonatype/nexus/deploy
COPY githuboauth.properties /opt/sonatype/nexus/etc/githuboauth.properties
USER nexus
