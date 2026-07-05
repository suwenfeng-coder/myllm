SHELL := /bin/bash
SERVICE ?= myllm
APPCTL := /bin/bash ./scripts/appctl.sh

.PHONY: help start stop restart status logs \
	start-java stop-java restart-java status-java logs-java \
	start-python stop-python restart-python status-python logs-python \
	start-docforge stop-docforge restart-docforge status-docforge logs-docforge \
	start-all stop-all restart-all status-all \
	test test-fast doctor verify check-doc-links

help:
	@printf '%s\n' \
	  'Usage:' \
	  '  make restart              # restart myllm (default)' \
	  '  make restart-docforge     # restart DocForge' \
	  '  make restart-python       # same as restart-docforge' \
	  '  make restart-all          # myllm + docforge + infra' \
	  '  make test-fast            # mvn test (unit tests)' \
	  '  make doctor               # env and service probe' \
	  '  make verify               # test-fast + doc link check' \
	  '  SERVICE=docforge make restart' \
	  '' \
	  'Note: "make restart docforge" is invalid — Make treats them as two targets.'

test: test-fast

test-fast:
	mvn -q test

doctor:
	/bin/bash ./scripts/doctor.sh

check-doc-links:
	/bin/bash ./scripts/check-doc-links.sh

verify: test-fast check-doc-links

start:
	$(APPCTL) start $(SERVICE)

stop:
	$(APPCTL) stop $(SERVICE)

restart:
	$(APPCTL) restart $(SERVICE)

status:
	$(APPCTL) status $(SERVICE)

logs:
	$(APPCTL) logs $(SERVICE)

start-java:
	$(APPCTL) start myllm

stop-java:
	$(APPCTL) stop myllm

restart-java:
	$(APPCTL) restart myllm

status-java:
	$(APPCTL) status myllm

logs-java:
	$(APPCTL) logs myllm

start-python:
	$(APPCTL) start docforge

stop-python:
	$(APPCTL) stop docforge

restart-python:
	$(APPCTL) restart docforge

status-python:
	$(APPCTL) status docforge

logs-python:
	$(APPCTL) logs docforge

start-docforge: start-python

stop-docforge: stop-python

restart-docforge: restart-python

status-docforge: status-python

logs-docforge: logs-python

start-all:
	$(APPCTL) start all

stop-all:
	$(APPCTL) stop all

restart-all:
	$(APPCTL) restart all

status-all:
	$(APPCTL) status all
