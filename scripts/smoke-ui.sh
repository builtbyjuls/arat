#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_name="arat-ui-smoke-$(date +%s)-$$-$RANDOM"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly smoke_label="com.docker.compose.project=${project_name}"
readonly expected_actor_id="10000000-0000-4000-8000-000000000001"
readonly redacted_invitation_token="ui-smoke-token-must-not-be-logged"
readonly deadline_seconds=180

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

if [[ ! "$project_name" =~ ^arat-ui-smoke-[a-z0-9][a-z0-9-]*$ ]]; then
    echo "Refusing to use an invalid UI smoke Compose project name." >&2
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

smoke_resource_ids() {
    docker ps --all --quiet --filter "label=$smoke_label"
    docker network ls --quiet --filter "label=$smoke_label"
    docker volume ls --quiet --filter "label=$smoke_label"
}

if [[ -n "$(smoke_resource_ids)" ]]; then
    echo "Refusing to use an existing UI smoke Compose project." >&2
    exit 1
fi

created_project=false
cleanup() {
    local status=$?
    trap - EXIT

    if [[ "$created_project" == true ]]; then
        if (( status != 0 )); then
            echo "UI smoke check failed; service logs follow." >&2
            compose logs --no-color >&2 || true
        fi

        if ! compose down --volumes --remove-orphans --rmi local >&2; then
            echo "UI smoke cleanup failed for project $project_name." >&2
            status=1
        fi

        if [[ -n "$(smoke_resource_ids)" ]]; then
            echo "UI smoke cleanup left Docker resources for project $project_name." >&2
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

check_deep_link() {
    local response
    response=$(curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        --dump-header - "${web_base_url}/groups/00000000-0000-4000-8000-000000000001")
    grep -qi '^content-type: text/html' <<<"$response" \
        && grep -q '<app-root' <<<"$response"
}

check_whoami() {
    local headers_file
    local body
    local status=0
    headers_file=$(mktemp)

    if ! body=$(curl --connect-timeout 3 --max-time 5 --fail --silent --show-error \
        --header 'Authorization: Bearer arat-local-owner-token' \
        --dump-header "$headers_file" \
        "${web_base_url}/api/v1/dev/whoami"); then
        rm -f "$headers_file"
        return 1
    fi

    grep -qi '^x-correlation-id:' "$headers_file" || status=1
    jq --exit-status ".actorId == \"${expected_actor_id}\"" <<<"$body" >/dev/null || status=1

    rm -f "$headers_file"
    return "$status"
}

created_project=true
echo "Starting isolated UI smoke project $project_name."
compose up --build --wait --wait-timeout "$deadline_seconds" web

web_port=$(http_port web 8080)
web_base_url="http://127.0.0.1:${web_port}"

wait_for "SPA deep-link fallback" check_deep_link
wait_for "proxied local whoami actor and correlation header" check_whoami

missing_asset_response=$(curl --connect-timeout 3 --max-time 5 --silent --show-error \
    --write-out $'\n%{http_code}' "${web_base_url}/missing-ui-smoke.js")
missing_asset_status=${missing_asset_response##*$'\n'}
missing_asset_body=${missing_asset_response%$'\n'*}
if [[ "$missing_asset_status" != "404" ]] || grep -q '<app-root' <<<"$missing_asset_body"; then
    echo "Missing static assets must return a non-SPA 404 response." >&2
    exit 1
fi
echo "Verified missing static asset handling."

curl --connect-timeout 3 --max-time 5 --silent --show-error \
    --header 'Authorization: Bearer arat-local-owner-token' \
    --request POST \
    --output /dev/null \
    "${web_base_url}/api/v1/group-invites/${redacted_invitation_token}/accept"

compose stop app >/dev/null
upstream_failure_status=$(curl --connect-timeout 3 --max-time 5 --silent --show-error \
    --header 'Authorization: Bearer arat-local-owner-token' \
    --request POST \
    --output /dev/null \
    --write-out '%{http_code}' \
    "${web_base_url}/api/v1/group-invites/${redacted_invitation_token}/accept")
if [[ "$upstream_failure_status" != "502" && "$upstream_failure_status" != "504" ]]; then
    echo "The stopped upstream must produce a web proxy 502 or 504 response." >&2
    exit 1
fi

web_logs=$(compose logs --no-color web)
if grep -Fq "$redacted_invitation_token" <<<"$web_logs" \
    || grep -Fq 'arat-local-owner-token' <<<"$web_logs"; then
    echo "Web proxy logs exposed an invitation or Authorization token." >&2
    exit 1
fi
echo "Verified token-safe web proxy logging for healthy and failed upstreams."

runtime_user=$(compose exec --no-TTY web id -u)
if [[ "$runtime_user" == "0" ]]; then
    echo "Web runtime must not run as root." >&2
    exit 1
fi
echo "Verified non-root web runtime."

if [[ "$force_failure" == true ]]; then
    echo "Forcing the controlled UI smoke failure path." >&2
    exit 1
fi

echo "UI smoke check completed successfully."
