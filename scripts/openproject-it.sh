#!/usr/bin/env bash
# Local OpenProject instance (Docker, all-in-one image) for integration tests and manual testing.
#
#   scripts/openproject-it.sh up      start (or create) the container and wait until the API answers
#   scripts/openproject-it.sh token   create an API token for the seeded "admin" user and print it
#   scripts/openproject-it.sh env     print OPENPROJECT_IT_URL / OPENPROJECT_IT_TOKEN exports (eval-able)
#   scripts/openproject-it.sh test    up + token + ./gradlew integrationTest (extra args go to Gradle)
#   scripts/openproject-it.sh logs    follow the container log
#   scripts/openproject-it.sh down    stop and remove the container
#
# Environment: OP_IMAGE (default openproject/openproject:17), OP_PORT (8080), OP_CONTAINER (op-it),
#              OP_TIMEOUT seconds to wait for the first boot (900).
set -euo pipefail

NAME="${OP_CONTAINER:-op-it}"
IMAGE="${OP_IMAGE:-openproject/openproject:17}"
PORT="${OP_PORT:-8080}"
URL="http://localhost:${PORT}"
TIMEOUT="${OP_TIMEOUT:-900}"
TOKEN_FILE="${TMPDIR:-/tmp}/${NAME}.token"

exists() { docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; }

secret() { head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

up() {
  if exists; then
    docker start "$NAME" >/dev/null
  else
    echo "Starting $IMAGE as $NAME on $URL (first boot migrates and seeds the database, this takes a few minutes)" >&2
    local key
    key="$(secret)"
    # OpenProject 17 refuses to start with the image's placeholder SECRET_KEY_BASE.
    docker run -d --name "$NAME" -p "${PORT}:80" \
      -e SECRET_KEY_BASE="$key" \
      -e OPENPROJECT_SECRET_KEY_BASE="$key" \
      -e OPENPROJECT_HOST__NAME="localhost:${PORT}" \
      -e OPENPROJECT_HTTPS=false \
      -e OPENPROJECT_DEFAULT__LANGUAGE=en \
      "$IMAGE" >/dev/null
  fi
  local deadline=$((SECONDS + TIMEOUT)) code
  until code=$(curl -s -o /dev/null -w '%{http_code}' --noproxy localhost "$URL/api/v3") && [[ "$code" == "200" || "$code" == "401" ]]; do
    if ((SECONDS > deadline)); then
      echo "OpenProject did not become ready within ${TIMEOUT}s; see: $0 logs" >&2
      exit 1
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
      echo "Container $NAME stopped:" >&2
      docker logs --tail 50 "$NAME" >&2
      exit 1
    fi
    sleep 5
  done
  echo "OpenProject is ready at $URL" >&2
}

token() {
  if [[ -s "$TOKEN_FILE" ]] && curl -sf --noproxy localhost -u "apikey:$(cat "$TOKEN_FILE")" "$URL/api/v3/users/me" -o /dev/null; then
    cat "$TOKEN_FILE"
    return
  fi
  local value
  # Also enables time tracking ("costs") in the demo project; older images seed it disabled.
  value=$(docker exec "$NAME" bash -lc 'cd /app && bundle exec rails runner "
    project = Project.find_by(identifier: %q(demo-project))
    if project && !project.enabled_module_names.include?(%q(costs))
      project.enabled_module_names = project.enabled_module_names + [%q(costs)]
      project.save!
    end
    user = User.find_by!(login: %q(admin))
    token = Token::API.create!(user: user)
    puts %Q(OPENPROJECT_TOKEN=#{token.plain_value})
  "' 2>/dev/null | sed -n 's/^OPENPROJECT_TOKEN=//p' | tail -1)
  if [[ -z "$value" ]]; then
    echo "Could not create an API token in $NAME" >&2
    exit 1
  fi
  printf '%s' "$value" > "$TOKEN_FILE"
  echo "$value"
}

case "${1:-}" in
  up) up ;;
  token) token ;;
  env)
    t=$(token)
    echo "export OPENPROJECT_IT_URL=$URL"
    echo "export OPENPROJECT_IT_TOKEN=$t"
    ;;
  test)
    shift
    up
    OPENPROJECT_IT_URL="$URL" OPENPROJECT_IT_TOKEN="$(token)" "$(dirname "$0")/../gradlew" -p "$(dirname "$0")/.." integrationTest "$@"
    ;;
  logs) docker logs -f "$NAME" ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -f "$TOKEN_FILE"
    ;;
  *)
    sed -n '2,13p' "$0"
    exit 2
    ;;
esac
