#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/packages/lock.yaml"
GENERATED_DIR="$ROOT_DIR/generated"
USER_AGENT="CareParse-PayerLab-Standards-Fetch/0.1"

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

download() {
  local url="$1"
  local output="$2"

  curl --fail --location --silent --show-error \
    --user-agent "$USER_AGENT" \
    "$url" \
    --output "$output"
}

write_checksums() {
  local dir="$1"
  shift

  (
    cd "$dir"
    shasum -a 256 "$@" > SHA256SUMS
  )
}

prepare_dir() {
  local spec="$1"
  local out_dir="$GENERATED_DIR/$spec"

  rm -rf "$out_dir"
  mkdir -p "$out_dir"
  printf '%s\n' "$out_dir"
}

write_metadata() {
  local out_dir="$1"
  local name="$2"
  local version="$3"
  local url="$4"

  {
    printf 'name=%s\n' "$name"
    printf 'version=%s\n' "$version"
    printf 'source_url=%s\n' "$url"
    printf 'fetched_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  } > "$out_dir/source-metadata.txt"
}

fetch_package() {
  local section="$1"
  local spec="$2"
  local label="$3"
  local version package_name url out_dir artifact

  version="$(yaml_value "$section" "$spec" version)"
  package_name="$(yaml_value "$section" "$spec" package_name)"
  url="$(yaml_value "$section" "$spec" url)"
  out_dir="$(prepare_dir "$spec")"
  artifact="package.tgz"

  printf 'Fetching %s %s\n' "$label" "$version"
  download "$url" "$out_dir/$artifact"
  mkdir -p "$out_dir/extracted"
  tar -xzf "$out_dir/$artifact" -C "$out_dir/extracted"
  write_metadata "$out_dir" "$package_name" "$version" "$url"
  write_checksums "$out_dir" "$artifact"
}

fetch_fhir_package() {
  local spec="$1"
  local label="$2"

  fetch_package "core" "$spec" "$label"
}

fetch_uscdi() {
  local spec="uscdi"
  local version resolved_version url out_dir artifact

  version="$(yaml_value core "$spec" version)"
  resolved_version="$(yaml_value core "$spec" resolved_version)"
  url="$(yaml_value core "$spec" url)"
  artifact="$(yaml_value core "$spec" artifact)"
  out_dir="$(prepare_dir "$spec")"

  printf 'Fetching USCDI %s (%s)\n' "$version" "$resolved_version"
  download "$url" "$out_dir/$artifact"
  write_metadata "$out_dir" "USCDI" "$resolved_version" "$url"
  write_checksums "$out_dir" "$artifact"
}

fetch_oidc() {
  local spec="oidc"
  local version url discovery_url out_dir artifact discovery_artifact

  version="$(yaml_value core "$spec" version)"
  url="$(yaml_value core "$spec" url)"
  discovery_url="$(yaml_value core "$spec" discovery_url)"
  artifact="$(yaml_value core "$spec" artifact)"
  discovery_artifact="openid-connect-discovery-1_0.txt"
  out_dir="$(prepare_dir "$spec")"

  printf 'Fetching OpenID Connect %s\n' "$version"
  download "$url" "$out_dir/$artifact"
  download "$discovery_url" "$out_dir/$discovery_artifact"
  write_metadata "$out_dir" "OpenID Connect" "$version" "$url"
  write_checksums "$out_dir" "$artifact" "$discovery_artifact"
}

mkdir -p "$GENERATED_DIR"

fetch_fhir_package "fhir" "FHIR R4"
fetch_fhir_package "uscore" "US Core"
fetch_uscdi
fetch_fhir_package "smart" "SMART App Launch"
fetch_fhir_package "bulk" "Bulk FHIR"
fetch_oidc
fetch_package "payer" "pas" "Da Vinci PAS"
fetch_package "payer" "crd" "Da Vinci CRD"
fetch_package "payer" "dtr" "Da Vinci DTR"
fetch_package "payer" "hrex" "Da Vinci HRex"

printf 'Standards artifacts written to %s\n' "$GENERATED_DIR"
