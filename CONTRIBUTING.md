# Contributing

Thanks for helping. This is a Java project; issues and pull requests are welcome.

## Set up and check your change

Needs JDK 21+ with Maven 3.9+ (and Python 3 for `make audit`). Caches stay in git-ignored folders
inside the repo when you pass `-Dmaven.repo.local=.m2/repository`.

```bash
make setup   # resolve dependencies
make format  # apply the project format (palantir-java-format via Spotless)
make lint    # enforcer, format check, javac -Xlint:all -Werror
make test    # full test suite
make e2e     # fetch one pinned trace, replay it, require the committed numbers
make bench   # all five pinned traces against the committed numbers (downloads 217 MB, then deletes it)
make audit   # known-vulnerability check (as in CI)
```

A pull request is ready when `make lint`, `make test` and `make e2e` pass and CI is green.

## Guidelines

- Keep each change focused; explain the problem it solves in the pull request.
- A new policy implements `Cache`, gets an entry in `Policy`, and a brute-force model in
  `ReferenceCaches` so `ReferenceModelTest` checks it on random operation sequences before any hit
  rate is compared.
- A change that moves any hit rate must re-record `docs/results/m1-hit-rates.tsv` with
  `make results` and explain the change in `docs/results/`, with the command that produced it.
- A new trace needs a public licence that allows redistribution of results, an HTTPS URL pinned
  to a commit, and its exact size and SHA-256 in `traces/pins.tsv`.
- Significant design changes need an ADR in `docs/adr/` (copy `0001` for the format).
- Keep function comments to one short phrase; let names and tests explain the rest.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

By contributing you agree that your work is released under the [MIT License](LICENSE).
