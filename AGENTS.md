# Repository Guidelines

## Project Structure & Module Organization
This repository is currently an empty scaffold: no `src/`, `tests/`, or build configuration files are present yet. Keep new application code under `src/`, place automated tests under `tests/`, and store static assets in `assets/` if the project grows beyond code-only content. Favor small, focused modules and mirror test paths to source paths, for example `src/adapter/client.py` with `tests/adapter/test_client.py`.

## Build, Test, and Development Commands
No standard build or test commands are defined in the current workspace. When introducing tooling, add the commands to this document and expose them through a single entry point such as:

```bash
make test
make lint
make dev
```

or package-manager scripts such as `npm test` or `python -m pytest`. Keep setup reproducible and avoid undocumented one-off commands.

## Coding Style & Naming Conventions
Use 4 spaces for Python and 2 spaces for JSON, YAML, and Markdown indentation. Prefer descriptive module names, `snake_case` for Python files and functions, `PascalCase` for classes, and lowercase directory names. If you add formatters or linters, wire them into the default workflow early; examples include `ruff format`, `ruff check`, `prettier`, or `eslint`.

## Testing Guidelines
Add tests alongside any new behavior, bug fix, or public interface. Name test files `test_*.py` or `*.spec.ts` according to the chosen stack, and keep test names behavior-focused, such as `test_retries_on_rate_limit`. Target meaningful coverage on core adapter logic, request shaping, and error handling before expanding peripheral features.

## Commit & Pull Request Guidelines
No Git history is available in this workspace, so adopt a simple convention now: use short, imperative commit subjects like `Add Anthropic request mapper` or `Fix timeout handling`. Keep pull requests scoped, describe the change and its impact, link related issues, and include sample requests, logs, or screenshots when behavior changes are user-visible.

## Configuration & Secrets
Do not commit API keys, tokens, or local `.env` files. Document required environment variables in `README.md` or a checked-in `.env.example`, and prefer configuration defaults that fail clearly when secrets are missing.
