# Third-party notices

Runtime dependencies retain their original licenses and notices inside their JARs.
The bundled Java runtime includes its own `legal` directory; redistribution must retain it.

- libGDX 1.14.2: Apache-2.0.
- LWJGL 3.4.3 and its native bindings: BSD-style license; native libraries retain upstream notices.
- Eclipse JGit 7.8.0: Eclipse Distribution License 1.0 (BSD-style).
- SQLite JDBC 3.50.3.0: Apache-2.0; SQLite engine is public domain.
- Gson 2.13.2: Apache-2.0.
- SLF4J 2.0.17: MIT.
- Roboto font: SIL Open Font License 1.1, included next to the font.
- Gradle wrapper: Apache-2.0.

Transitive dependencies are included in the Gradle dependency report. This summary is
not a replacement for the full licenses packaged in each dependency. Release builds
should use Eclipse Temurin JDK 25 so the bundled runtime uses its OpenJDK distribution
license; retain all runtime notices. Developer-local JDKs can have different licenses.
