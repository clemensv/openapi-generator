#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_JAR="${CLI_JAR:-${ROOT_DIR}/modules/openapi-generator-cli/target/openapi-generator-cli.jar}"
SPEC_DIR="${ROOT_DIR}/modules/openapi-generator/src/test/resources/3_1"
NPM_REGISTRY="${NPM_CONFIG_REGISTRY:-https://packagefeedproxy.microsoft.io/npm/}"
NUGET_SOURCE="${NUGET_SOURCE:-https://pkgs.dev.azure.com/dnceng/public/_packaging/dotnet-public/nuget/v3/index.json}"

if [[ -z "${OUTPUT_DIR:-}" ]]; then
  OUTPUT_DIR="$(mktemp -d)"
  REMOVE_OUTPUT=true
else
  mkdir -p "${OUTPUT_DIR}"
  REMOVE_OUTPUT=false
fi

GENERATORS=(java csharp go python typescript-fetch kotlin rust php)
FIXTURES=(
  json-structure-safe
  json-structure-containers
  json-structure-mixed
  json-structure-namespaces
  json-structure-wire-shapes
)

if [[ ! -f "${CLI_JAR}" ]]; then
  echo "OpenAPI Generator CLI JAR not found: ${CLI_JAR}" >&2
  echo "Build it with: mvn -pl modules/openapi-generator-cli -am -DskipTests package" >&2
  exit 1
fi

cleanup() {
  if [[ "${REMOVE_OUTPUT}" == true && -z "${KEEP_OUTPUT:-}" ]]; then
    docker run --rm --mount "type=bind,source=${OUTPUT_DIR},target=/work" \
      composer:2 sh -lc 'find /work -mindepth 1 -depth -delete' >/dev/null
    rmdir "${OUTPUT_DIR}"
  elif [[ -n "${KEEP_OUTPUT:-}" ]]; then
    echo "Generated projects retained in ${OUTPUT_DIR}"
  fi
}
trap cleanup EXIT

for generator in "${GENERATORS[@]}"; do
  for fixture in "${FIXTURES[@]}"; do
    output="${OUTPUT_DIR}/${generator}/${fixture}"
    extra_args=()
    if [[ "${fixture}" == "json-structure-wire-shapes" ]]; then
      extra_args=(-p jsonStructureCompatibilityMode=true)
    fi
    java -jar "${CLI_JAR}" generate \
      -g "${generator}" \
      -i "${SPEC_DIR}/${fixture}.yaml" \
      -o "${output}" \
      --skip-validate-spec \
      --global-property apiTests=false,modelTests=false \
      "${extra_args[@]}" >/dev/null
  done
done

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/java,target=/work" \
  maven:3.9.11-eclipse-temurin-17 sh -lc \
  'for d in /work/*; do echo "JAVA $(basename "$d")"; mvn -q -f "$d/pom.xml" -DskipTests package || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/csharp,target=/work" \
  -e "NUGET_SOURCE=${NUGET_SOURCE}" mcr.microsoft.com/dotnet/sdk:10.0 sh -lc \
  'for d in /work/*; do echo "CSHARP $(basename "$d")"; dotnet restore "$d/Org.OpenAPITools.sln" --source "$NUGET_SOURCE" --ignore-failed-sources -v:q && dotnet build "$d/Org.OpenAPITools.sln" --no-restore --nologo -v:q || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/go,target=/work" \
  golang:1.25 sh -lc \
  'for d in /work/*; do echo "GO $(basename "$d")"; cd "$d" && /usr/local/go/bin/go test ./... || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/python,target=/work" \
  python:3.13 sh -lc \
  'for d in /work/*; do echo "PYTHON $(basename "$d")"; python -m compileall -q "$d/openapi_client" || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/typescript-fetch,target=/work" \
  -e "NPM_CONFIG_REGISTRY=${NPM_REGISTRY}" node:22 sh -lc \
  'for d in /work/*; do echo "TYPESCRIPT $(basename "$d")"; cd "$d" && npx --yes --package typescript@5.9.3 tsc --noEmit --target ES2020 --module Node16 --moduleResolution Node16 --lib ES2020,DOM index.ts || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/kotlin,target=/work" \
  gradle:8.14.3-jdk17 sh -lc \
  'for d in /work/*; do echo "KOTLIN $(basename "$d")"; gradle -p "$d" compileKotlin --no-daemon -q || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/rust,target=/work" \
  rust:1.93 sh -lc \
  'for d in /work/*; do echo "RUST $(basename "$d")"; cd "$d" && /usr/local/cargo/bin/cargo check --all-targets --quiet || exit 1; done'

docker run --rm --mount "type=bind,source=${OUTPUT_DIR}/php,target=/work" \
  composer:2 sh -lc \
  'for d in /work/*; do echo "PHP $(basename "$d")"; cd "$d" && composer validate --no-check-publish --quiet && find lib -name "*.php" -exec php -l {} \; >/dev/null || exit 1; done'

echo "All JSON Structure focused outputs built successfully."
