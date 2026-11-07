COMPOSE = docker compose -f deploy/docker-compose.yml
.PHONY: up down test it bench chaos tamper report clean

up:
	mvn -q -DskipTests package jib:dockerBuild
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down -v

test:
	mvn -q test

it:
	mvn -q verify

bench:
	@echo TODO; exit 1

chaos:
	@echo TODO; exit 1

tamper:
	@echo TODO; exit 1

report:
	@echo TODO; exit 1

clean:
	mvn -q clean
