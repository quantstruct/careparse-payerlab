#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/packages/lock.yaml"
GENERATED_DIR="$ROOT_DIR/generated"

yaml_value() {
  local section="$1"
  local item="$2"
  local key="$3"

  awk -v section="$section" -v item="$item" -v key="$key" '
    /^[^[:space:]][^:]*:/ {
      in_section = ($1 == section ":")
      in_item = 0
    }
    in_section && $0 ~ "^  " item ":" {
      in_item = 1
      next
    }
    in_section && in_item && $0 ~ "^  [A-Za-z0-9_-]+:" {
      in_item = 0
    }
    in_section && in_item && $0 ~ "^    " key ":" {
      sub("^[^:]+:[[:space:]]*", "")
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      gsub(/^"|"$/, "")
      print
      exit
    }
  ' "$LOCK_FILE"
}

print_ok() {
  local label="$1"
  local width=16
  local dots_count=$((width - ${#label} - 1))
  local dots

  if (( dots_count < 2 )); then
    dots_count=2
  fi

  dots="$(printf '%*s' "$dots_count" '' | tr ' ' '.')"
  printf '%s %s OK\n' "$label" "$dots"
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "Missing required file: $path"
}

require_dir() {
  local path="$1"
  [[ -d "$path" ]] || fail "Missing required directory: $path"
}

verify_checksums() {
  local dir="$1"

  require_file "$dir/SHA256SUMS"
  (
    cd "$dir"
    shasum -a 256 -c SHA256SUMS >/dev/null
  ) || fail "Checksum verification failed in $dir"
}

json_version() {
  local package_json="$1"

  sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$package_json" | head -n 1
}

verify_package() {
  local spec="$1"
  local label="$2"
  shift 2

  local version out_dir package_json actual_version resource
  version="$(yaml_value core "$spec" version)"
  out_dir="$GENERATED_DIR/$spec"
  package_json="$out_dir/extracted/package/package.json"

  require_dir "$out_dir"
  require_file "$out_dir/package.tgz"
  require_dir "$out_dir/extracted/package"
  require_file "$package_json"
  verify_checksums "$out_dir"

  actual_version="$(json_version "$package_json")"
  [[ "$actual_version" == "$version" ]] || fail "$label version mismatch: lock=$version package=$actual_version"

  for resource in "$@"; do
    require_file "$out_dir/extracted/$resource"
  done

  print_ok "$label"
}

verify_uscdi() {
  local spec="uscdi"
  local label="USCDI"
  local resolved_version out_dir artifact

  resolved_version="$(yaml_value core "$spec" resolved_version)"
  artifact="$(yaml_value core "$spec" artifact)"
  out_dir="$GENERATED_DIR/$spec"

  require_dir "$out_dir"
  require_file "$out_dir/$artifact"
  require_file "$out_dir/source-metadata.txt"
  verify_checksums "$out_dir"

  grep -q '^"Classification Level","Data Class","Data Class Description","Data Element"' "$out_dir/$artifact" \
    || fail "USCDI CSV header not found"
  grep -q "\"$resolved_version\"" "$out_dir/$artifact" \
    || fail "$resolved_version rows not found in USCDI export"
  grep -q "version=$resolved_version" "$out_dir/source-metadata.txt" \
    || fail "USCDI metadata does not match lock resolved_version"

  print_ok "$label"
}

verify_oidc() {
  local spec="oidc"
  local label="OIDC"
  local version out_dir artifact

  version="$(yaml_value core "$spec" version)"
  artifact="$(yaml_value core "$spec" artifact)"
  out_dir="$GENERATED_DIR/$spec"

  require_dir "$out_dir"
  require_file "$out_dir/$artifact"
  require_file "$out_dir/openid-connect-discovery-1_0.txt"
  require_file "$out_dir/source-metadata.txt"
  verify_checksums "$out_dir"

  grep -q "OpenID Connect Core $version" "$out_dir/$artifact" \
    || fail "OpenID Connect Core $version marker not found"
  grep -q "OpenID Connect Discovery $version" "$out_dir/openid-connect-discovery-1_0.txt" \
    || fail "OpenID Connect Discovery $version marker not found"

  print_ok "$label"
}

verify_package "fhir" "FHIR R4" \
  "package/StructureDefinition-Patient.json" \
  "package/ValueSet-administrative-gender.json" \
  "package/CodeSystem-observation-category.json" \
  "package/OperationDefinition-Resource-validate.json" \
  "package/CapabilityStatement-base.json"

verify_package "uscore" "US Core" \
  "package/StructureDefinition-us-core-patient.json" \
  "package/ValueSet-us-core-clinical-note-type.json" \
  "package/CodeSystem-us-core-category.json" \
  "package/CapabilityStatement-us-core-server.json" \
  "package/OperationDefinition-docref.json"

verify_uscdi

verify_package "smart" "SMART" \
  "package/ImplementationGuide-hl7.fhir.uv.smart-app-launch.json" \
  "package/CapabilityStatement-smart-app-state-server.json" \
  "package/CodeSystem-smart-codes.json" \
  "package/StructureDefinition-smart-app-state-basic.json" \
  "package/ValueSet-smart-launch-types.json"

verify_package "bulk" "Bulk FHIR" \
  "package/ImplementationGuide-hl7.fhir.uv.bulkdata.json" \
  "package/CapabilityStatement-bulk-data.json" \
  "package/OperationDefinition-patient-export.json" \
  "package/OperationDefinition-group-export.json" \
  "package/OperationDefinition-export.json"

verify_oidc
