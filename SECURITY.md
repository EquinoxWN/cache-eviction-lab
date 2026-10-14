# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security > Report a vulnerability** on this repository
(`https://github.com/EquinoxWN/cache-eviction-lab/security/advisories/new`). Do not open a public issue.

Include what you found, how to reproduce it, and the impact you expect. You will get an answer
within 7 days. Fixes are released as soon as they are ready, and you are credited unless you ask
not to be.

## Supported versions

This project is pre-1.0. Only the latest commit on `main` receives fixes.

## Scope

- **In scope:** a trace or pins file that makes the tool crash, hang, write outside the data
  folder or use memory beyond its documented limits (lines over 4,096 bytes, pins over 256 MiB,
  more than 200 caches per replay, capacities over 16,777,216 entries); a download that is
  accepted although its size or SHA-256 differs from its pin; a download from a host or scheme
  other than `https://raw.githubusercontent.com`.
- **Known limitation, out of scope:** trace keys are reduced to 64-bit MurmurHash3 values, which
  are not cryptographic. Someone who controls a trace can make two keys collide and change a hit
  rate. The pinned traces are fixed files, so this only matters for traces you replay yourself.
- **Out of scope:** hit rates that differ from other simulators because of a documented modelling
  choice (look-aside replay, entry-count capacity, see ADR 0002).

## How this repository protects itself

- Every GitHub Action is pinned to a full commit SHA, and workflows run with read-only
  permissions and without persisted credentials.
- Dependabot update pull requests are paused (`open-pull-requests-limit: 0`) so the repository keeps a single `main` branch; dependencies are updated by hand and checked by the CI audit.
- CI runs the format check, `javac -Xlint:all -Werror`, the tests, an end-to-end replay of a
  pinned trace against the committed numbers, and an OSV.dev check of every resolved dependency on
  every push and pull request.
- Downloads use HTTPS only, follow no redirects, stop as soon as they exceed the pinned size, and
  are renamed into place only after the size and SHA-256 match. Trace names are restricted to
  `[a-z0-9-]`, so a pins file cannot point a download outside the data folder.
- There are no runtime dependencies; JUnit is the only dependency, in test scope.
