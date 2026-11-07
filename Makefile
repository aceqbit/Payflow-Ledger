COMPOSE = docker compose -f deploy/docker-compose.yml
.PHONY: up down test it bench chaos tamper report seed clean

seed:
	bash scripts/seed.sh

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
	bash scripts/bench.sh

chaos:
	@echo TODO; exit 1

tamper:
	@echo TODO; exit 1

report:
	python perf/report.py

clean:
	mvn -q clean
