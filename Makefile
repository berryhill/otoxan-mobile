SHELL := /usr/bin/env bash

APP_ID ?= com.otoxan.mobile
VOICE_HOST ?= 0.0.0.0
VOICE_PORT ?= 8787
VOICE_ENDPOINT ?= http://100.126.0.110:8787/voice-turn
ADB ?= $(shell if [ -x /home/berry/sdk/platform-tools/adb ]; then echo sudo /home/berry/sdk/platform-tools/adb; else command -v adb 2>/dev/null || echo adb; fi)
GRADLE ?= ./gradlew
PYTHON ?= $(shell if [ -x /home/silas/.hermes/hermes-agent/venv/bin/python ]; then echo /home/silas/.hermes/hermes-agent/venv/bin/python; else echo python3; fi)
APK ?= app/build/outputs/apk/debug/app-debug.apk

.PHONY: help all build clean install reinstall launch run logs devices adb-start backend backend-xander backend-proof smoke-backend test-backend test test-all endpoint doctor

help:
	@printf '%s\n' \
	  'Otoxan Mobile make targets:' \
	  '' \
	  '  make backend                  Run mobile-fast Xander backend on 0.0.0.0:8787' \
	  '  make backend-xander           Explicitly require legacy Xander/Hermes session provider' \
	  '  make backend-proof            Run deterministic proof backend only' \
	  '  make smoke-backend            POST a fake Ray-Ban turn to VOICE_ENDPOINT' \
	  '  make build                    Build debug APK with VOICE_ENDPOINT baked in' \
	  '  make adb-start                Start adb daemon with configured ADB command' \
	  '  make install                  Install debug APK via adb' \
	  '  make reinstall                Uninstall then install debug APK' \
	  '  make launch                   Launch installed app' \
	  '  make all                      Build, reinstall, and launch' \
	  '  make logs                     Tail useful Android logs while testing' \
	  '  make devices                  Show connected adb devices' \
	  '  make test                     Run Android JVM tests + Python backend tests' \
	  '  make endpoint                 Print endpoint currently baked by make build' \
	  '' \
	  'Useful overrides:' \
	  '  make all VOICE_ENDPOINT=http://100.126.0.110:8787/voice-turn' \
	  '  make backend VOICE_HOST=0.0.0.0 VOICE_PORT=8787' \
	  '  make install ADB="sudo /home/berry/sdk/platform-tools/adb"'

endpoint:
	@echo 'VOICE_ENDPOINT=$(VOICE_ENDPOINT)'
	@echo 'ADB=$(ADB)'

backend:
	OTOXAN_VOICE_PROVIDER=mobile-fast $(PYTHON) tools/voice_turn_server.py --host $(VOICE_HOST) --port $(VOICE_PORT)

backend-xander:
	OTOXAN_VOICE_PROVIDER=xander-session $(PYTHON) tools/voice_turn_server.py --host $(VOICE_HOST) --port $(VOICE_PORT)

backend-proof:
	OTOXAN_VOICE_PROVIDER=proof $(PYTHON) tools/voice_turn_server.py --host $(VOICE_HOST) --port $(VOICE_PORT)

smoke-backend:
	$(PYTHON) tools/smoke_voice_turn.py "$(VOICE_ENDPOINT)" --expect-provider "$${OTOXAN_EXPECT_PROVIDER:-}"

clean:
	$(GRADLE) clean

build:
	$(GRADLE) :app:assembleDebug -PXANDER_VOICE_ENDPOINT="$(VOICE_ENDPOINT)"

adb-start:
	$(ADB) start-server

install: adb-start
	$(ADB) install -r $(APK)

reinstall: adb-start
	-$(ADB) uninstall $(APP_ID)
	$(ADB) install $(APK)

launch: adb-start
	$(ADB) shell monkey -p $(APP_ID) -c android.intent.category.LAUNCHER 1

run: launch

all: build reinstall launch

devices:
	$(ADB) devices -l

logs:
	$(ADB) logcat | grep -iE 'otoxan|xander|cleartext|unknownhost|connect|http|AndroidRuntime'

test-backend:
	$(PYTHON) -m unittest app/src/test/python/test_voice_turn_server.py -v

test:
	$(GRADLE) :app:testDebugUnitTest
	$(MAKE) test-backend

test-all: test build

doctor:
	@echo 'Repo:' $$(pwd)
	@echo 'VOICE_ENDPOINT=$(VOICE_ENDPOINT)'
	@echo 'ADB=$(ADB)'
	@$(ADB) devices -l || true
	@ss -ltnp 2>/dev/null | grep ':$(VOICE_PORT)' || true
