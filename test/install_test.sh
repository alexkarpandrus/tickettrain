#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

mkdir -p "${TEMP_DIR}/empty-bin" "${TEMP_DIR}/mock-bin"

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
elif [[ "${1}" == "-C" && ( "${3}" == "fetch" || "${3}" == "checkout" ) ]]; then
  :
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
exit 0
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
grep -Fq "Installed and verified:" "${TEMP_DIR}/install.out"
grep -Fq "Add tickettrain to your PATH:" "${TEMP_DIR}/install.out"

PATH="${HOME}/.local/bin:${BASE_PATH}" /bin/bash <"${ROOT_DIR}/bin/install" >"${TEMP_DIR}/update.out"
grep -Fq -- "-C ${TTT_INSTALL_DIR} fetch --depth 1 origin refs/tags/v0.1.0:refs/tags/v0.1.0 --quiet" "${MOCK_GIT_LOG}"
grep -Fq -- "-C ${TTT_INSTALL_DIR} checkout --detach --quiet v0.1.0" "${MOCK_GIT_LOG}"
test "$(grep -c '^ls-remote --tags --refs --sort=-v:refname https://github.com/alexkarpandrus/tickettrain.git v\[0-9\]\*$' "${MOCK_GIT_LOG}")" -eq 3
if grep -Fq "Add tickettrain to your PATH:" "${TEMP_DIR}/update.out"; then
  echo "installer reported a PATH problem when its bin directory was present" >&2
  exit 1
fi
test "$(grep -c '^version$' "${MOCK_TTT_LOG}")" -eq 2

echo "Installer smoke test passed."
