#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

echo "Deleting sample Azure infrastructure..."
echo "Project: $PROJECT_DIR"
echo "Runtime: lib/*.jar"
echo "Report:  target/azure-current-report.txt"

set_config_value azure_full_lb_demo_recreate false
set_config_value azure_create_demo false
set_config_value azure_traffic_manager_create false
set_config_value azure_delete_demo true

cleanup() {
  set_config_value azure_delete_demo false
}
trap cleanup EXIT

run_java_app

echo
echo "Delete complete."
echo "Delete flag reset to false."
