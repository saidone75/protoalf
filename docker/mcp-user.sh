#!/bin/sh
# One-shot, idempotent: prepares the Alfresco user the MCP server works as.
#  - user $MCP_USERNAME (not an administrator); its home folder is /User Homes/$MCP_USERNAME (alias -my-)
#  - folder /Company Home/$MCP_FOLDER where the user is Coordinator (create, edit, delete)
# Everywhere else the user only has the permissions Alfresco gives to every user (usually read-only).
set -eu

API="${ALFRESCO_URL}/alfresco/api/-default-/public/alfresco/versions/1"
AUTH="${ADMIN_USERNAME}:${ADMIN_PASSWORD}"

# curl wrapper: prints the HTTP status, body goes to /tmp/body
call() {
    method="$1"; path="$2"; shift 2
    curl -s -o /tmp/body -w '%{http_code}' -u "$AUTH" -X "$method" -H 'Content-Type: application/json' "$@" "${API}${path}"
}

echo "Waiting for Alfresco at ${ALFRESCO_URL} ..."
until [ "$(curl -s -o /dev/null -w '%{http_code}' "${API}/probes/-ready-")" = "200" ]; do sleep 5; done

status=$(call POST /people -d "{\"id\":\"${MCP_USERNAME}\",\"firstName\":\"MCP\",\"lastName\":\"Server\",\"email\":\"${MCP_USERNAME}@example.com\",\"password\":\"${MCP_PASSWORD}\"}")
case "$status" in
    201) echo "User ${MCP_USERNAME} created" ;;
    409)
        # Already there: keep its password in sync with the configuration
        status=$(call PUT "/people/${MCP_USERNAME}" -d "{\"password\":\"${MCP_PASSWORD}\"}")
        [ "$status" = "200" ] || { echo "Cannot update user ${MCP_USERNAME}: HTTP ${status}"; cat /tmp/body; exit 1; }
        echo "User ${MCP_USERNAME} already exists" ;;
    *) echo "Cannot create user ${MCP_USERNAME}: HTTP ${status}"; cat /tmp/body; exit 1 ;;
esac

status=$(call POST "/nodes/-root-/children" -d "{\"name\":\"${MCP_FOLDER}\",\"nodeType\":\"cm:folder\"}")
case "$status" in
    201|409) ;;
    *) echo "Cannot create folder ${MCP_FOLDER}: HTTP ${status}"; cat /tmp/body; exit 1 ;;
esac
status=$(call GET "/nodes/-root-?relativePath=${MCP_FOLDER}&fields=id")
[ "$status" = "200" ] || { echo "Cannot read folder ${MCP_FOLDER}: HTTP ${status}"; cat /tmp/body; exit 1; }
folder=$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' /tmp/body)

status=$(call PUT "/nodes/${folder}" -d "{\"permissions\":{\"isInheritanceEnabled\":true,\"locallySet\":[{\"authorityId\":\"${MCP_USERNAME}\",\"name\":\"Coordinator\",\"accessStatus\":\"ALLOWED\"}]}}")
[ "$status" = "200" ] || { echo "Cannot set permissions on ${MCP_FOLDER}: HTTP ${status}"; cat /tmp/body; exit 1; }
echo "Folder /Company Home/${MCP_FOLDER} (${folder}): ${MCP_USERNAME} is Coordinator"
