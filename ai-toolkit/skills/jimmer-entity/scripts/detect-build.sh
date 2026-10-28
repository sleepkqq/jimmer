#!/usr/bin/env bash
set -euo pipefail
project="${1:-.}"
cd "$project"
if [ -x ./gradlew ]; then
  if rg -q "kotlin|ksp" build.gradle.kts build.gradle settings.gradle.kts settings.gradle 2>/dev/null; then
    printf './gradlew compileKotlin\n'
  else
    printf './gradlew compileJava\n'
  fi
elif [ -x ./mvnw ]; then
  printf './mvnw compile\n'
elif [ -f pom.xml ]; then
  printf 'mvn compile\n'
elif [ -f build.gradle.kts ] || [ -f build.gradle ]; then
  if rg -q "kotlin|ksp" build.gradle.kts build.gradle settings.gradle.kts settings.gradle 2>/dev/null; then
    printf 'gradle compileKotlin\n'
  else
    printf 'gradle compileJava\n'
  fi
else
  printf 'No supported Java/Kotlin build file found in %s\n' "$PWD" >&2
  exit 1
fi
