# Contributing

## Development

Install Git, Java 21, and Babashka 1.12.217 or newer. Then run:

```bash
bb test
./test/install_test.sh
```

Open a focused pull request. Do not include credentials or `config/ttt.local.edn`.

## Commit messages

Use Conventional Commits. Use `feat:` for user-visible features and `fix:` for user-visible fixes. Put the area in a scope when useful, such as `fix(setup):` or `fix(install):`. Use `docs:`, `test:`, `refactor:`, `ci:`, or `chore:` for changes that do not need a release.

## Releases

Release Please opens and updates the release pull request from commits on `main`. Merging that pull request updates `version.txt` and `CHANGELOG.md`, creates the `vX.Y.Z` Git tag, and publishes the GitHub Release. Do not edit those release outputs or create release tags manually.

Report vulnerabilities through the process in [SECURITY.md](SECURITY.md).
