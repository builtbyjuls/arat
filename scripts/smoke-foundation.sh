#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_name="arat-smoke-$(date +%s)-$$-$RANDOM"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly smoke_label="com.docker.compose.project=${project_name}"
readonly expected_actor_id="10000000-0000-4000-8000-000000000001"
readonly deadline_seconds=90

force_failure=false
case "${1:-}" in
    "")
        ;;
    --force-failure)
        force_failure=true
        ;;
    *)
        echo "Usage: $0 [--force-failure]" >&2
        exit 2
        ;;
esac

if [[ ! "$project_name" =~ ^arat-smoke-[a-z0-9][a-z0-9-]*$ ]]; then
    echo "Refusing to use an invalid smoke Compose project name." >&2
    exit 1
fi

export ARAT_APP_PORT=0
export ARAT_POSTGRES_PORT=0
export ARAT_PROMETHEUS_PORT=0
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

smoke_resource_ids() {
    docker ps --all --quiet --filter "label=$smoke_label"
    docker network ls --quiet --filter "label=$smoke_label"
    docker volume ls --quiet --filter "label=$smoke_label"
}

if [[ -n "$(smoke_resource_ids)" ]]; then
    echo "Refusing to use an existing smoke Compose project." >&2
    exit 1
fi

created_project=false
cleanup() {
    local status=$?
    trap - EXIT

    if [[ "$created_project" == true ]]; then
        if (( status != 0 )); then
            echo "Smoke check failed; service logs follow." >&2
            compose logs --no-color >&2 || true
        fi

        if ! compose down --volumes --remove-orphans >&2; then
            echo "Smoke cleanup failed for project $project_name." >&2
            status=1
        fi

        if [[ -n "$(smoke_resource_ids)" ]]; then
            echo "Smoke cleanup left Docker resources for project $project_name." >&2
            status=1
        fi
    fi

    exit "$status"
}
trap cleanup EXIT

wait_for() {
    local description=$1
    local check=$2
    local started_at
    started_at=$(date +%s)

    while true; do
        if "$check"; then
            echo "Verified $description."
            return
        fi

        if (( $(date +%s) - started_at >= deadline_seconds )); then
            echo "Timed out waiting for $description after ${deadline_seconds}s." >&2
            compose ps >&2 || true
            return 1
        fi

        sleep 2
    done
}

http_port() {
    compose port "$1" "$2" | sed -E 's/.*:([0-9]+)$/\1/'
}

check_liveness() {
    curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        "${app_base_url}/actuator/health/liveness" \
        | jq --exit-status '.status == "UP"' >/dev/null
}

check_readiness() {
    curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        "${app_base_url}/actuator/health/readiness" \
        | jq --exit-status '.status == "UP"' >/dev/null
}

check_whoami() {
    curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        --header 'Authorization: Bearer arat-local-owner-token' \
        "${app_base_url}/api/v1/dev/whoami" \
        | jq --exit-status ".actorId == \"${expected_actor_id}\"" >/dev/null
}

check_prometheus_scrape_text() {
    curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        "${app_base_url}/actuator/prometheus" \
        | grep '^jvm_' >/dev/null
}

check_prometheus_target_health() {
    curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        "${prometheus_base_url}/api/v1/targets?state=active" \
        | jq --exit-status \
            '[.data.activeTargets[] | select(.labels.job == "arat" and .health == "up")] | length == 1' \
            >/dev/null
}

created_project=true
echo "Starting isolated smoke project $project_name."
compose up --build --wait --wait-timeout "$deadline_seconds" postgres app prometheus

app_port=$(http_port app 8080)
prometheus_port=$(http_port prometheus 9090)
app_base_url="http://127.0.0.1:${app_port}"
prometheus_base_url="http://127.0.0.1:${prometheus_port}"

wait_for "liveness" check_liveness
wait_for "readiness" check_readiness
wait_for "local whoami actor" check_whoami
wait_for "Prometheus scrape text" check_prometheus_scrape_text
wait_for "Prometheus target health" check_prometheus_target_health

flyway_success=$(compose exec --no-TTY postgres psql --username arat --dbname arat --tuples-only --no-align \
    --command "SELECT success FROM flyway_schema_history WHERE version = '001'")
if [[ "$flyway_success" != "t" ]]; then
    echo "Flyway baseline migration 001 is not successful." >&2
    exit 1
fi
echo "Verified successful Flyway baseline history."

if [[ "$force_failure" == true ]]; then
    echo "Forcing the controlled smoke failure path." >&2
    exit 1
fi

echo "Smoke check completed successfully."
