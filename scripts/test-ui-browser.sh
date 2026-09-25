#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_name="arat-ui-browser-$(date +%s)-$$-$RANDOM"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly browser_label="com.docker.compose.project=${project_name}"
readonly deadline_seconds=180
readonly browser_output_paths=(
    "$repository_root/ui/playwright-report"
    "$repository_root/ui/test-results"
)

if [[ ! "$project_name" =~ ^arat-ui-browser-[a-z0-9][a-z0-9-]*$ ]]; then
    echo "Refusing to use an invalid browser Compose project name." >&2
    exit 1
fi

export ARAT_APP_PORT=0
export ARAT_POSTGRES_PORT=0
export ARAT_PROMETHEUS_PORT=0
export ARAT_WEB_PORT=0
export ARAT_DATABASE_NAME=arat
export ARAT_DATABASE_USERNAME=arat
export ARAT_DATABASE_PASSWORD=arat

compose() {
    docker compose \
        --project-directory "$repository_root" \
        --file "$repository_root/compose.yaml" \
        --project-name "$project_name" \
        "$@"
}

browser_resource_ids() {
    docker ps --all --quiet --filter "label=$browser_label"
    docker network ls --quiet --filter "label=$browser_label"
    docker volume ls --quiet --filter "label=$browser_label"
}

if [[ -n "$(browser_resource_ids)" ]]; then
    echo "Refusing to use an existing browser Compose project." >&2
    exit 1
fi

created_project=false
cleanup() {
    local status=$?
    trap - EXIT

    if [[ "$created_project" == true ]]; then
        if (( status != 0 )); then
            echo "Browser checks failed; service logs follow." >&2
            compose logs --no-color >&2 || true
        fi

        if ! compose down --volumes --remove-orphans --rmi local >&2; then
            echo "Browser cleanup failed for project $project_name." >&2
            status=1
        fi

        if [[ -n "$(browser_resource_ids)" ]]; then
            echo "Browser cleanup left Docker resources for project $project_name." >&2
            status=1
        fi
    fi

    if (( status == 0 )); then
        rm -rf -- "${browser_output_paths[@]}"
    fi

    exit "$status"
}
trap cleanup EXIT

http_port() {
    compose port web 8080 | sed -E 's/.*:([0-9]+)$/\1/'
}

created_project=true
echo "Starting isolated browser project $project_name."
compose up --build --wait --wait-timeout "$deadline_seconds" web

web_port=$(http_port)
readonly PLAYWRIGHT_BASE_URL="http://127.0.0.1:${web_port}"
export PLAYWRIGHT_BASE_URL

curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
    --header 'Authorization: Bearer arat-local-owner-token' \
    "$PLAYWRIGHT_BASE_URL/api/v1/dev/whoami" >/dev/null

echo "Running focused Playwright checks against $PLAYWRIGHT_BASE_URL."
npm --prefix "$repository_root/ui" run test:browser
