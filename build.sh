#!/usr/bin/env bash
# Compiles the app and the tests, then runs the tests. Fails if any test fails.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
find src/main/java src/test/java -name "*.java" > /tmp/cpg-sources.txt
javac -d out @/tmp/cpg-sources.txt
rm /tmp/cpg-sources.txt
java -cp out com.nusrat.chargepoint.ocpp.OcppSelfTest
echo
echo "Built and tested. Run with: PORT=8080 java -cp out com.nusrat.chargepoint.Application"
