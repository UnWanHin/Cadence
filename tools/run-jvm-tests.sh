#!/usr/bin/env bash
# Compiles and runs the pure-Java room tests on a desktop JVM.
# These classes are shared with the APK, so this is the protocol test gate.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${TMPDIR:-/tmp}/cadence-room-tests"
rm -rf "$out" && mkdir -p "$out"

mapfile -t sources < <(find "$root/android/app/src/main/java/com/campus/sport/lan" -name '*.java')
sources+=("$root/tools/room-server/RoomServerMain.java" "$root/tools/room-server/RoomAddressTest.java" "$root/tools/room-server/RoomDiscoveryTest.java" "$root/tools/room-server/HostElectionTest.java" "$root/tools/room-server/LanAddressesTest.java")

javac --release 17 -encoding UTF-8 -d "$out" "${sources[@]}"
java -cp "$out" com.campus.sport.lan.RoomAddressTest
java -cp "$out" com.campus.sport.lan.RoomDiscoveryTest
java -cp "$out" com.campus.sport.lan.HostElectionTest
java -cp "$out" com.campus.sport.lan.LanAddressesTest
