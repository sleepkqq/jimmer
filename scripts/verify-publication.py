"""Verify all fork POMs/Gradle metadata and the Quarkus deployment descriptor.

Usage: python3 scripts/verify-publication.py MAVEN_REPOSITORY_OR_URL VERSION
"""
import io
import json
import pathlib
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

repository, version = sys.argv[1:]
group = "com.github.sleepkqq.jimmer"
modules = [
    "jimmer-bom", "jimmer-core", "jimmer-core-kotlin", "jimmer-apt", "jimmer-ksp",
    "jimmer-sql", "jimmer-sql-kotlin", "jimmer-client", "jimmer-client-swagger",
    "jimmer-client-scalar", "jimmer-dto-compiler", "jimmer-ddl-compiler",
    "jimmer-mapstruct-apt", "jimmer-spring-boot-starter",
    "quarkus-jimmer", "quarkus-jimmer-deployment",
]


def read(module, suffix):
    path = f"{group.replace('.', '/')}/{module}/{version}/{module}-{version}.{suffix}"
    if repository.startswith(("https://", "http://")):
        with urllib.request.urlopen(f"{repository.rstrip('/')}/{path}", timeout=120) as response:
            return response.read()
    return (pathlib.Path(repository) / path).read_bytes()


for module in modules:
    pom = ET.fromstring(read(module, "pom"))
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    assert pom.findtext("m:groupId", namespaces=ns) == group, module
    assert pom.findtext("m:version", namespaces=ns) == version, module
    for dependency in pom.findall(".//m:dependency", ns):
        dependency_group = dependency.findtext("m:groupId", namespaces=ns)
        assert dependency_group != "org.babyfish.jimmer", (module, "upstream POM dependency")
        if dependency_group == group:
            assert dependency.findtext("m:version", namespaces=ns) == version, module

    metadata = json.loads(read(module, "module"))
    assert metadata["component"]["group"] == group, module
    assert metadata["component"]["version"] == version, module
    for variant in metadata["variants"]:
        for dependency in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
            assert dependency["group"] != "org.babyfish.jimmer", (module, "upstream Gradle dependency")
            if dependency["group"] == group:
                assert dependency["version"].get("requires") == version, (module, dependency)
    print(f"OK {module}:{version} (POM + Gradle metadata)")

with zipfile.ZipFile(io.BytesIO(read("quarkus-jimmer", "jar"))) as jar:
    descriptor = jar.read("META-INF/quarkus-extension.properties").decode()
    expected = f"deployment-artifact={group}\\:quarkus-jimmer-deployment\\:{version}"
    assert expected in descriptor, descriptor
print("OK Quarkus deployment descriptor")
