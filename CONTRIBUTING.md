# Contributing

Use JDK 25 and `./gradlew test :desktop-ui:run`. Keep repository analysis read-only and
off the render thread. Changes to Git semantics require fixtures, including merge-base
and rename cases. Keep overlay X/Z positions stable. Never label heuristic results as
proven errors. Run `./gradlew :git-indexer:benchmark` for the reproducible indexing suite.
Package locally with `python3 scripts/package.py`; build on each target OS.
