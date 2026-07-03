#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
CONFIG_FILE="$PROJECT_DIR/conf/conf.properties"

JAVA_HOME_WIN="${JAVA_HOME_WIN:-C:\\Program Files\\JetBrains\\IntelliJ IDEA Community Edition 2025.2.3\\jbr}"

if command -v cygpath >/dev/null 2>&1; then
  export JAVA_HOME="$(cygpath -u "$JAVA_HOME_WIN")"
else
  export JAVA_HOME="$JAVA_HOME_WIN"
fi

JAVA_CMD="$JAVA_HOME/bin/java"
if [[ ! -x "$JAVA_CMD" ]]; then
  echo "Java executable not found: $JAVA_CMD" >&2
  exit 1
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Config file not found: $CONFIG_FILE" >&2
  exit 1
fi

set_config_value() {
  local key="$1"
  local value="$2"
  local tmp
  tmp="$(mktemp)"
  if grep -q "^${key}=" "$CONFIG_FILE"; then
    sed "s|^${key}=.*|${key}=${value}|" "$CONFIG_FILE" > "$tmp"
  else
    cat "$CONFIG_FILE" > "$tmp"
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$CONFIG_FILE"
}

run_java_app() {
  cd "$PROJECT_DIR"
  mkdir -p target
  "$JAVA_CMD" -cp "lib/*" org.qypp.AzureInfraTool \
    > >(tee target/azure-current-report.txt) \
    2> >(cat >&2)
}
