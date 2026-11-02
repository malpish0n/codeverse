# Security policy

CODEVERSE reads committed objects from repositories; it does not execute repository code,
perform checkout/reset/fetch/push, or upload source code. Symlink entries and submodules
are metadata only. LFS pointers are not downloaded. There is no telemetry or login.

Limits: 100,000 files, 2,000 history commits, 1 MiB per parsed blob, 64 MiB retained source
per snapshot. SQLite caches source text locally; delete the CODEVERSE application-data
folder to remove it. Do not share cache files. Screenshots may expose names; anonymous
export includes only the scene viewport.

Report vulnerabilities privately through GitHub's security reporting facility if enabled;
otherwise request a private reporting channel from the maintainer without disclosing
sensitive repository data in a public issue. Preview builds are not security audited.
