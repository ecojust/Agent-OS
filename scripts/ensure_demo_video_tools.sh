#!/usr/bin/env bash
# Dedicated CI preparation; keep system repository configuration and signatures intact.
set -euo pipefail

if command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null; then
  printf '%s\n' 'Using installed ffmpeg and ffprobe.'
  exit 0
fi
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && "${GITHUB_REPOSITORY:-}" == ecojust/Agent-OS ]] || {
  printf '%s\n' 'Tool installation is restricted to the trusted Linux workflow.' >&2
  exit 1
}
# The current hosted Ubuntu image is noble. Do not silently install another release's packages.
source /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_CODENAME" == noble ]] || {
  printf '%s\n' 'Unsupported runner distribution for the pinned video-tool source.' >&2
  exit 1
}
keyring=/usr/share/keyrings/ubuntu-archive-keyring.gpg
[[ -r "$keyring" ]]
tool_sources=$(mktemp -d "${RUNNER_TEMP:?}/demo-apt.XXXXXX")
trap 'sudo rm -rf -- "$tool_sources"' EXIT
mkdir -p "$tool_sources/lists/partial"
chmod 755 "$tool_sources"
cat > "$tool_sources/sources.list" <<EOF
deb [signed-by=$keyring] https://archive.ubuntu.com/ubuntu noble main universe
deb [signed-by=$keyring] https://archive.ubuntu.com/ubuntu noble-updates main universe
deb [signed-by=$keyring] https://security.ubuntu.com/ubuntu noble-security main universe
EOF
options=(-o "Dir::Etc::sourcelist=$tool_sources/sources.list" -o Dir::Etc::sourceparts=-
         -o "Dir::State::lists=$tool_sources/lists" -o APT::Update::Error-Mode=any
         -o APT::Get::List-Cleanup=0 -o Acquire::Retries=1
         -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20)
sudo timeout --kill-after=10s 120s apt-get "${options[@]}" update
sudo timeout --kill-after=10s 360s apt-get "${options[@]}" install --no-install-recommends -y ffmpeg
command -v ffmpeg
command -v ffprobe
