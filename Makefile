.PHONY: setup format lint test fetch bench results e2e clean-data audit ci

# MVNLOCAL (set by the author's repoenv helper) keeps the Maven repository inside the repo; empty in CI.
MVN = mvn -B -q $(MVNLOCAL)
PYTHON ?= python3
LAB = java -Xmx1g -XX:-UsePerfData -cp target/classes portfolio.cacheevictionlab.cli.Main
RESULTS = docs/results/m1-hit-rates.tsv

setup:
	$(MVN) -DskipTests dependency:resolve

# Rewrite sources in the project style (palantir-java-format through Spotless).
format:
	$(MVN) spotless:apply

# Enforcer (JDK 21+, Maven 3.9+), Spotless format check, and javac with -Xlint:all -Werror.
lint:
	$(MVN) -DskipTests compile

# 100 tests (about 35 s): every policy against a brute-force model on random operation sequences,
# the Cache contract, policy behaviour, trace parsing, pins, replay and the CLI end to end.
test:
	$(MVN) verify

# Download the five pinned traces (217 MB) into data/ and check size and SHA-256.
fetch: lint
	$(LAB) fetch

# Replay all five traces at five sizes through five policies, require the committed numbers exactly,
# then delete the downloaded traces (they can be fetched again from their pins).
bench: fetch
	$(LAB) bench --expect $(RESULTS)
	rm -rf data

# Re-record the committed hit-rate table (only after a deliberate policy change).
results: fetch
	$(LAB) bench --out $(RESULTS)
	rm -rf data

# CI end-to-end check on the smallest trace (cluster045, 34.6 MB).
e2e: lint
	$(LAB) fetch --only cluster045
	$(LAB) bench --only cluster045 --expect $(RESULTS)
	rm -rf data

clean-data:
	rm -rf data

# Known-vulnerability check of every resolved dependency (test scope included) against OSV.dev.
audit:
	$(MVN) dependency:list -DincludeScope=test -DoutputFile=target/dependencies.txt
	$(PYTHON) scripts/osv_audit.py Maven target/dependencies.txt

ci: setup lint test e2e audit
