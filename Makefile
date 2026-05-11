BACKEND_DIR ?= backend
FRONTEND_DIR ?= frontend
BACKEND_STATIC_DIR ?= $(BACKEND_DIR)/control-manager/src/main/resources/static
MVN ?= mvn
NPM ?= npm
MAVEN_REPO ?= $(CURDIR)/.m2/repository
MAVEN_ARGS ?= -B -ntp -Dmaven.repo.local=$(MAVEN_REPO)
NPM_CACHE ?= $(CURDIR)/.npm
NPM_ARGS ?= --cache $(NPM_CACHE)

.PHONY: build package backend-build backend-test backend-dev worker-build worker-it frontend-install frontend-build frontend-test frontend-dev frontend-sync-static
.NOTPARALLEL: package

build: backend-build frontend-build

package: frontend-build frontend-sync-static backend-build

backend-build:
	$(MVN) -f $(BACKEND_DIR)/pom.xml $(MAVEN_ARGS) package -DskipTests

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
