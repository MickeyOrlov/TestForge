# module-api-discovery

Reads a service's OpenAPI document, builds a catalog of its endpoints, probes
the safe ones, and records the **structure** of what came back — types only,
never values.

Full documentation lands with the runner. See
[ApiDiscoveryProperties](src/main/java/io/testforge/api/discovery/ApiDiscoveryProperties.java)
for the configuration surface in the meantime.
