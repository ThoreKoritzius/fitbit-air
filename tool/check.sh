#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_dir="$(cd "$script_dir/.." && pwd)"

cd "$package_dir"
flutter pub get
flutter analyze
flutter test

cd "$package_dir/example"
flutter test
flutter build apk --debug

cd "$package_dir/example/android"
air_java_home="${JAVA_HOME:-}"
if [[ -z "$air_java_home" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  air_java_home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [[ -n "$air_java_home" ]]; then
  JAVA_HOME="$air_java_home" ./gradlew :app:testDebugUnitTest :fitbit_air_core:testDebugUnitTest --console=plain
else
  ./gradlew :app:testDebugUnitTest :fitbit_air_core:testDebugUnitTest --console=plain
fi

cd "$package_dir"
dart pub publish --dry-run
