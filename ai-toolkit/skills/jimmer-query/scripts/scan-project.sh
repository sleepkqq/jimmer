#!/usr/bin/env bash
set -euo pipefail
project="${1:-.}"
cd "$project"
printf 'Project: %s\n' "$PWD"
printf '\nBuild files:\n'
rg --files -g 'build.gradle*' -g 'settings.gradle*' -g 'pom.xml' -g 'gradle.properties' || true
printf '\nJimmer config files:\n'
rg --files -g 'application*.yml' -g 'application*.yaml' -g 'application*.properties' | xargs -r rg -l 'jimmer|quarkus\.jimmer' || true
printf '\nEntities:\n'
rg --files | xargs -r rg -l '@Entity|@MappedSuperclass' || true
printf '\nRepositories:\n'
rg --files | xargs -r rg -l 'JRepository|KRepository|sql\(\)|KSqlClient|JSqlClient' || true
printf '\nDTO files:\n'
rg --files -g '*.dto' || true
printf '\nMigrations:\n'
rg --files | rg 'db/(changelog|migration)|liquibase|flyway|migration|changelog' || true
