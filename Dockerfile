ARG QUARKUS_NATIVE_BUILDER_IMAGE=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21
ARG QUARKUS_RUNTIME_IMAGE=quay.io/quarkus/ubi9-quarkus-micro-image:2.0

FROM ${QUARKUS_NATIVE_BUILDER_IMAGE} AS builder

USER root
WORKDIR /project
RUN chown -R 1001:0 /project

USER 1001
COPY --chown=1001:0 .mvn/ .mvn/
COPY --chown=1001:0 mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY --chown=1001:0 src/ src/
RUN ./mvnw -B -ntp package \
    -Dnative \
    -Dmaven.test.skip=true \
    -Dquarkus.native.container-build=false

FROM ${QUARKUS_RUNTIME_IMAGE}

WORKDIR /work/
RUN chown 1001 /work \
    && chmod "g+rwX" /work \
    && chown 1001:root /work

COPY --from=builder --chown=1001:root --chmod=0755 /project/target/*-runner /work/application

EXPOSE 8080
USER 1001

ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
