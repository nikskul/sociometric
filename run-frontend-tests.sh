#!/usr/bin/env bash
#
# Проверки фронтенда для Maven: `mvn verify` вызывает этот скрипт.
# Отдельно — `node src/test/js/run.js`.
#
# Node.js не обязателен: если его нет, сборка не должна падать.

set -euo pipefail

if ! command -v node > /dev/null 2>&1; then
    echo "node не найден — проверки фронтенда пропущены"
    echo "установите Node.js и запустите: node src/test/js/run.js"
    exit 0
fi

exec node "$(dirname "$0")/src/test/js/run.js"
