#!/usr/bin/env bash
set -e

if [[ -z "${HEL_HOME}" ]]; then
  echo "HEL_HOME environment variable is not set!"
  exit 255
fi

cd ${HEL_HOME} && docker-compose \
    -f .docker/docker-compose.development.yml \
    --project-name hel \
    --project-directory . $@