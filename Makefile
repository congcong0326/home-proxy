BACKEND_DIR ?= backend
FRONTEND_DIR ?= frontend
BACKEND_STATIC_DIR ?= $(BACKEND_DIR)/control-manager/src/main/resources/static
CONTROL_COMPOSE ?= deploy/docker/docker-compose.control.yml
WORKER_COMPOSE ?= deploy/docker/docker-compose.worker.yml
WORKER_ENV_FILE ?= $(CURDIR)/deploy/docker/worker.env.local
DOCKER_COMPOSE ?= docker compose
DOCKER_BUILD_ARGS ?=
DOCKER_UP_ARGS ?= -d --force-recreate
DOCKER_IMAGE_TAR ?= home-proxy-images.tar
CONTROL_IMAGE ?= home-proxy-control-manager:latest
WORKER_IMAGE ?= home-proxy-worker:latest
WORKER_BUILD_ID ?= image-build
WORKER_BUILD_CONTROL_MANAGER_HOST_IP ?= 127.0.0.1
MYSQL_IMAGE ?= mysql:8.4
CLICKHOUSE_IMAGE ?= clickhouse/clickhouse-server:24.8
MVN ?= mvn
NPM ?= npm
MAVEN_REPO ?= $(CURDIR)/.m2/repository
MAVEN_ARGS ?= -B -ntp -Dmaven.repo.local=$(MAVEN_REPO)
NPM_CACHE ?= $(CURDIR)/.npm
NPM_ARGS ?= --cache $(NPM_CACHE)

.PHONY: build package backend-build backend-test backend-dev worker-build worker-it e2e-transparent e2e-fake-ip-transparent e2e-fake-ip-persistent e2e-direct-outbound-safety e2e-worker-proxy e2e-worker-proxy-setup frontend-install frontend-build frontend-test frontend-dev frontend-sync-static docker-build docker-build-control docker-build-worker docker-up-control docker-up-worker docker-reset-control docker-save docker-save-offline
.NOTPARALLEL: package

build: backend-build frontend-build

package: frontend-build frontend-sync-static backend-build

backend-build:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) clean package -DskipTests

backend-test:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) test -DskipTests=false

backend-dev:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) -pl control-manager -am spring-boot:run

worker-build:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) -pl proxy-worker -am package -DskipTests

worker-it:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) -pl proxy-worker -am verify -Pworker-it -DskipTests=false

# Requires Linux, Docker, iproute2/iptables, and permission to create a network namespace.
# It builds isolated E2E images by default; set E2E_BUILD=0 to reuse them.
e2e-transparent:
	bash scripts/e2e/transparent-proxy-e2e.sh

# Includes real DNS fixtures, cache/failure probes, audit and worker restart checks.
e2e-fake-ip-transparent: e2e-transparent

# Keeps local DNS/origin services, namespace and controller fixtures for log inspection.
e2e-fake-ip-persistent:
	python3 scripts/e2e/fake-ip-persistent-e2e.py setup
	python3 scripts/e2e/fake-ip-persistent-e2e.py test

# Starts an isolated control-manager and worker to verify DIRECT address safety
# across SOCKS5, HTTP CONNECT, Shadowsocks, and TLS SNI TCP.
e2e-direct-outbound-safety:
	bash scripts/e2e/direct-outbound-address-safety-e2e.sh

# Reuses the running control-manager and home-01 worker. Provision fixtures once,
# then keep the regular E2E read-only for release checks.
e2e-worker-proxy:
	bash scripts/e2e/worker-proxy-e2e.sh

e2e-worker-proxy-setup:
	bash scripts/e2e/worker-proxy-e2e.sh --provision

frontend-install:
	$(NPM) $(NPM_ARGS) --prefix $(FRONTEND_DIR) ci

frontend-build: frontend-install
	$(NPM) $(NPM_ARGS) --prefix $(FRONTEND_DIR) run build

frontend-test: frontend-install
	$(NPM) $(NPM_ARGS) --prefix $(FRONTEND_DIR) test -- --watch=false

frontend-dev:
	$(NPM) $(NPM_ARGS) --prefix $(FRONTEND_DIR) start

frontend-sync-static: frontend-build
	rm -rf "$(BACKEND_STATIC_DIR)"
	mkdir -p "$(BACKEND_STATIC_DIR)"
	cp -R "$(FRONTEND_DIR)/build/." "$(BACKEND_STATIC_DIR)/"

docker-build: package
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) build $(DOCKER_BUILD_ARGS) control-manager
	WORKER_ID="$(WORKER_BUILD_ID)" CONTROL_MANAGER_HOST_IP="$(WORKER_BUILD_CONTROL_MANAGER_HOST_IP)" $(DOCKER_COMPOSE) -f $(WORKER_COMPOSE) build $(DOCKER_BUILD_ARGS) proxy-worker

docker-build-control: package
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) build $(DOCKER_BUILD_ARGS) control-manager

docker-build-worker: worker-build
	WORKER_ID="$(WORKER_BUILD_ID)" CONTROL_MANAGER_HOST_IP="$(WORKER_BUILD_CONTROL_MANAGER_HOST_IP)" $(DOCKER_COMPOSE) -f $(WORKER_COMPOSE) build $(DOCKER_BUILD_ARGS) proxy-worker

docker-up-control: docker-build-control
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) up $(DOCKER_UP_ARGS)

docker-up-worker: docker-build-worker
	@test -f "$(WORKER_ENV_FILE)" || (echo "Worker environment file not found: $(WORKER_ENV_FILE)"; exit 1)
	$(DOCKER_COMPOSE) --env-file "$(WORKER_ENV_FILE)" -f $(WORKER_COMPOSE) up $(DOCKER_UP_ARGS)

docker-reset-control:
	@test "$(CONFIRM)" = "1" || (echo "This removes control-plane containers and volumes. Re-run with CONFIRM=1 to continue."; exit 1)
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) down -v
	$(MAKE) docker-up-control

docker-save: docker-build
	docker save -o $(DOCKER_IMAGE_TAR) $(CONTROL_IMAGE) $(WORKER_IMAGE)

docker-save-offline: docker-build
	docker pull $(MYSQL_IMAGE)
	docker pull $(CLICKHOUSE_IMAGE)
	docker save -o $(DOCKER_IMAGE_TAR) $(CONTROL_IMAGE) $(WORKER_IMAGE) $(MYSQL_IMAGE) $(CLICKHOUSE_IMAGE)
