#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
simroot=${SIMAVR_ROOT:-/opt/simavr-src/simavr}
lib="$simroot/obj-x86_64-linux-gnu"
mkdir -p "$root/local-resources/simavr"
gcc -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror \
  -I"$simroot/sim" "$root/native/simavr/firmware_runner.c" \
  -L"$lib" -Wl,-rpath,"$lib" -lsimavr \
  -o "$root/local-resources/simavr/firmware-runner"
