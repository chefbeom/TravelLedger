# Secret Scanning Contract

Updated: 2026-06-30

This contract keeps committed source, documentation, workflow files, and checked environment examples free of high-risk secret values. It complements `docs/security_baseline_checklist.md` and the runtime configuration sync gate.

## Scope

| Surface | Required control |
| --- | --- |
| Git-tracked files | `scripts/scan-secrets.ps1` scans `git ls-files` so the gate follows the repository state rather than local ignored files. |
| Token-shaped values | The scanner blocks high-risk patterns for AWS, GitHub, GitLab, npm, OpenAI-style, Google, Stripe, SendGrid, Slack, JWT, and private-key material. |
| Sensitive assignments | Variables containing `API_KEY`, `SECRET`, `TOKEN`, `PASSWORD`, `PRIVATE_KEY`, `JWT_KEY`, `ACCESS_KEY`, `CREDENTIAL`, or `PASSPHRASE` must use placeholders in committed files. |
| Placeholder values | Examples must be obviously non-secret: `change-me`, `replace-me`, `placeholder`, `dummy`, `sample`, `fixture`, `local`, `dev`, shell expansion, or angle-bracket placeholders. |
| Generated/vendor paths | Build output, dependency folders, Git metadata, Gradle output, and run logs remain excluded from this lightweight repo gate. |

## Required workflow

1. Keep real `.env`, provider keys, database passwords, OAuth tokens, rclone credentials, signed URLs, presigned URLs, and private keys outside Git.
2. Use checked examples only for placeholders that are visibly non-production.
3. Run `scripts/scan-secrets.ps1` before pushing changes that touch env examples, CI, docs, provider clients, backup scripts, or auth/admin/sharing code.
4. If the scanner flags a real secret, rotate it before merging even if the value is deleted from Git.
5. If the scanner flags a safe fixture, rewrite the fixture to an obvious placeholder instead of adding a broad allowlist.
6. Update this document and `scripts/verify-secret-scan-contract.ps1` when adding or removing scanner patterns.

## CI contract

- `secret-scan` runs `scripts/scan-secrets.ps1` on push and pull request.
- `secret-scan-contract` runs `scripts/verify-secret-scan-contract.ps1` so the scanner, this document, the security baseline, the roadmap, and the release gate stay aligned.
- `release-gate` must require both jobs before the build can pass.

## Release evidence

A release that changes secrets, credentials, provider URLs, backup tooling, OCR/AI integrations, or CI workflow definitions should include:

- A passing `secret-scan` result or local `scripts/scan-secrets.ps1` run.
- A passing `secret-scan-contract` result or local `scripts/verify-secret-scan-contract.ps1` run.
- Confirmation that any previously exposed real secret was rotated outside this repository.
- Confirmation that docs and env examples use placeholders instead of copied production values.