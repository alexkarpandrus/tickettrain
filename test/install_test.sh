#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

mkdir -p "${TEMP_DIR}/empty-bin" "${TEMP_DIR}/mock-bin"

"${ROOT_DIR}/bin/ttt" --help | grep -Fq "ttt update"

if PATH="${TEMP_DIR}/empty-bin" HOME="${TEMP_DIR}/no-git-home" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/no-git.out" 2>&1; then
  echo "installer succeeded without git" >&2
  exit 1
fi
grep -Fq "tickettrain requires git" "${TEMP_DIR}/no-git.out"

cat >"${TEMP_DIR}/mock-bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${MOCK_GIT_LOG}"
if [[ "${1}" == "ls-remote" ]]; then
  [[ "${MOCK_NO_RELEASE:-0}" == "1" ]] || printf '%s\t%s\n' "1111111111111111111111111111111111111111" "refs/tags/v0.1.0"
elif [[ "${1}" == "clone" ]]; then
  destination="${*: -1}"
  mkdir -p "${destination}/.git" "${destination}/bin"
  cat > "${destination}/bin/ttt" <<'SCRIPT'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${MOCK_TTT_LOG}"
[[ "${1:-}" == "version" ]]
SCRIPT
  chmod +x "${destination}/bin/ttt"
elif [[ "${1}" == "-C" ]]; then
  root_dir="${2}"
  case "${3}" in
    rev-parse) printf 'true\n' ;;
    symbolic-ref)
      [[ "${MOCK_ATTACHED:-0}" == "1" ]] || exit 1
      printf 'refs/heads/main\n'
      ;;
    describe) printf 'v%s\n' "$(cat "${root_dir}/version.txt")" ;;
    status) if [[ "${MOCK_DIRTY:-0}" == "1" ]]; then printf ' M bin/ttt\n'; fi ;;
    fetch) ;;
    checkout)
      release_tag="${*: -1}"
      printf '%s\n' "${release_tag#v}" >"${root_dir}/version.txt"
      ;;
    *)
      echo "unexpected git command: $*" >&2
      exit 1
      ;;
  esac
else
  echo "unexpected git command: $*" >&2
  exit 1
fi
EOF
chmod +x "${TEMP_DIR}/mock-bin/git"

if PATH="${TEMP_DIR}/mock-bin" HOME="${TEMP_DIR}/no-bb-home" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/no-bb.out" 2>&1; then
  echo "installer succeeded without Babashka" >&2
  exit 1
fi
grep -Fq "tickettrain requires Babashka" "${TEMP_DIR}/no-bb.out"

cat >"${TEMP_DIR}/mock-bin/bb" <<'EOF'
#!/usr/bin/env bash
[[ "${MOCK_BB_FAIL:-0}" != "1" ]]
EOF
chmod +x "${TEMP_DIR}/mock-bin/bb"

export HOME="${TEMP_DIR}/home"
export TTT_INSTALL_DIR="${HOME}/share/tickettrain"
export MOCK_GIT_LOG="${TEMP_DIR}/git.log"
export MOCK_TTT_LOG="${TEMP_DIR}/ttt.log"
BASE_PATH="${TEMP_DIR}/mock-bin:${PATH}"

if MOCK_NO_RELEASE=1 PATH="${BASE_PATH}" HOME="${TEMP_DIR}/no-release-home" TTT_INSTALL_DIR="${TEMP_DIR}/no-release-install" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/no-release.out" 2>&1; then
  echo "installer succeeded without a tagged release" >&2
  exit 1
fi
grep -Fq "No tickettrain release is available yet." "${TEMP_DIR}/no-release.out"

PATH="${BASE_PATH}" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/install.out"
test -L "${HOME}/.local/bin/ttt"
test "$(readlink "${HOME}/.local/bin/ttt")" = "${TTT_INSTALL_DIR}/bin/ttt"
grep -Fq "clone --depth 1 --branch v0.1.0 https://github.com/alexkarpandrus/tickettrain.git ${TTT_INSTALL_DIR}" "${MOCK_GIT_LOG}"
test -f "${TTT_INSTALL_DIR}/.git/ttt-install"
grep -Fq "✓ Installed and verified:" "${TEMP_DIR}/install.out"
grep -Fq "⚠ Add tickettrain to your PATH:" "${TEMP_DIR}/install.out"
grep -Fq "  ttt → ${HOME}/.local/bin/ttt" "${TEMP_DIR}/install.out"
grep -Fq "→ Optional: install the agent skill" "${TEMP_DIR}/install.out"
grep -Fq "→ Next: enter a target repository" "${TEMP_DIR}/install.out"

PATH="${HOME}/.local/bin:${BASE_PATH}" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/update.out"
grep -Fq -- "-C ${TTT_INSTALL_DIR} fetch --depth 1 https://github.com/alexkarpandrus/tickettrain.git refs/tags/v0.1.0:refs/tags/v0.1.0 --quiet" "${MOCK_GIT_LOG}"
grep -Fq -- "-C ${TTT_INSTALL_DIR} checkout --detach --quiet v0.1.0" "${MOCK_GIT_LOG}"
test "$(grep -c '^ls-remote --tags --refs --sort=-v:refname https://github.com/alexkarpandrus/tickettrain.git v\[0-9\]\*$' "${MOCK_GIT_LOG}")" -eq 3
if grep -Fq "⚠ Add tickettrain to your PATH:" "${TEMP_DIR}/update.out"; then
  echo "installer reported a PATH problem when its bin directory was present" >&2
  exit 1
fi
test "$(grep -c '^version$' "${MOCK_TTT_LOG}")" -eq 2

UPDATE_ROOT="${TEMP_DIR}/release-install"
mkdir -p "${UPDATE_ROOT}/.git" "${UPDATE_ROOT}/bin" "${UPDATE_ROOT}/config"
UPDATE_ROOT="$(cd "${UPDATE_ROOT}" && pwd -P)"
cp "${ROOT_DIR}/bin/ttt" "${ROOT_DIR}/bin/install" "${UPDATE_ROOT}/bin/"
printf '0.0.9\n' >"${UPDATE_ROOT}/version.txt"
printf '{:tracker {:provider :linear}}\n' >"${UPDATE_ROOT}/config/ttt.local.edn"
: > "${UPDATE_ROOT}/.git/ttt-install"

PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/self-update.out"
grep -Fq "✓ Updated ttt 0.0.9 → 0.1.0." "${TEMP_DIR}/self-update.out"
test "$(cat "${UPDATE_ROOT}/version.txt")" = "0.1.0"
grep -Fq ':tracker {:provider :linear}' "${UPDATE_ROOT}/config/ttt.local.edn"
grep -Fq -- "-C ${UPDATE_ROOT} fetch --depth 1 https://github.com/alexkarpandrus/tickettrain.git refs/tags/v0.1.0:refs/tags/v0.1.0 --quiet" "${MOCK_GIT_LOG}"
grep -Fq -- "-C ${UPDATE_ROOT} checkout --detach --quiet v0.1.0" "${MOCK_GIT_LOG}"

PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/up-to-date.out"
grep -Fq "✓ ttt 0.1.0 is already up to date." "${TEMP_DIR}/up-to-date.out"

rm "${UPDATE_ROOT}/.git/ttt-install"
if PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/detached-source.out" 2>&1; then
  echo "updater changed a detached source checkout" >&2
  exit 1
fi
grep -Fq "only works for installer-managed release installations" "${TEMP_DIR}/detached-source.out"
: > "${UPDATE_ROOT}/.git/ttt-install"

if MOCK_ATTACHED=1 PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/attached.out" 2>&1; then
  echo "updater changed a source checkout" >&2
  exit 1
fi
grep -Fq "only works for a tagged release installation" "${TEMP_DIR}/attached.out"

if MOCK_DIRTY=1 PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/dirty.out" 2>&1; then
  echo "updater changed a dirty installation" >&2
  exit 1
fi
grep -Fq "installation has local changes" "${TEMP_DIR}/dirty.out"

printf '0.0.8\n' >"${UPDATE_ROOT}/version.txt"
if MOCK_BB_FAIL=1 PATH="${BASE_PATH}" "${UPDATE_ROOT}/bin/ttt" update >"${TEMP_DIR}/rollback.out" 2>&1; then
  echo "updater accepted a broken release" >&2
  exit 1
fi
grep -Fq "Update verification failed; restored ttt 0.0.8." "${TEMP_DIR}/rollback.out"
test "$(cat "${UPDATE_ROOT}/version.txt")" = "0.0.8"

echo "Installer smoke test passed."
