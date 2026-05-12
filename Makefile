BACKEND_DIR ?= backend
FRONTEND_DIR ?= frontend
BACKEND_STATIC_DIR ?= $(BACKEND_DIR)/control-manager/src/main/resources/static
CONTROL_COMPOSE ?= deploy/docker/docker-compose.control.yml
WORKER_COMPOSE ?= deploy/docker/docker-compose.worker.yml
DOCKER_COMPOSE ?= docker compose
DOCKER_BUILD_ARGS ?=
DOCKER_UP_ARGS ?= -d --force-recreate
DOCKER_IMAGE_TAR ?= home-proxy-images.tar
CONTROL_IMAGE ?= home-proxy-control-manager:latest
WORKER_IMAGE ?= home-proxy-worker:latest
MYSQL_IMAGE ?= mysql:8.4
CLICKHOUSE_IMAGE ?= clickhouse/clickhouse-server:24.8
MVN ?= mvn
NPM ?= npm
MAVEN_REPO ?= $(CURDIR)/.m2/repository
MAVEN_ARGS ?= -B -ntp -Dmaven.repo.local=$(MAVEN_REPO)
NPM_CACHE ?= $(CURDIR)/.npm
NPM_ARGS ?= --cache $(NPM_CACHE)

.PHONY: build package backend-build backend-test backend-dev worker-build worker-it frontend-install frontend-build frontend-test frontend-dev frontend-sync-static docker-build docker-build-control docker-build-worker docker-up-control docker-up-worker docker-reset-control docker-save docker-save-offline
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
	$(DOCKER_COMPOSE) -f $(WORKER_COMPOSE) build $(DOCKER_BUILD_ARGS) proxy-worker

docker-build-control: package
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) build $(DOCKER_BUILD_ARGS) control-manager

docker-build-worker: worker-build
	$(DOCKER_COMPOSE) -f $(WORKER_COMPOSE) build $(DOCKER_BUILD_ARGS) proxy-worker

docker-up-control: docker-build-control
	$(DOCKER_COMPOSE) -f $(CONTROL_COMPOSE) up $(DOCKER_UP_ARGS)

docker-up-worker: docker-build-worker
	$(DOCKER_COMPOSE) -f $(WORKER_COMPOSE) up $(DOCKER_UP_ARGS)

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
