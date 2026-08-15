# module-api-fuzz

`module-api-fuzz` is a thin adapter that runs the external Schemathesis CLI against OpenAPI specifications registered in `forge.api-discovery.specs`.
By default, execution is safe-by-default and restricted to read-only HTTP methods (`GET`, `HEAD`, `OPTIONS`) unless unsafe methods are explicitly enabled.
Schemathesis is NOT a build dependency of TestForge and must be installed separately in the runtime environment.
The default Gradle build remains completely offline and will not download or execute external tools.
