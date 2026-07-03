#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

echo "Running Azure autodiscovery..."
echo "Project: $PROJECT_DIR"
echo "Runtime: lib/*.jar"
echo "Report:  target/azure-current-report.txt"
echo "Graph:   target/azure-demo-graph.html"

set_config_value azure_delete_demo false
set_config_value azure_full_lb_demo_recreate false

run_java_app

echo
echo "Autodiscovery complete."
echo "Open: $PROJECT_DIR/target/azure-demo-graph.html"
