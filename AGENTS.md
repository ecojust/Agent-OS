# Agent OS repository workflow

- Follow `docs/DESIGN_RULES.md`, `docs/COMPATIBILITY.md`, `docs/APP_COMPATIBILITY.md` and `docs/PERFORMANCE.md`. Keep implemented behavior, test evidence and future targets distinct.
- Finish and verify each independent module, then commit it promptly as `agent`. Coordinate shared files before editing; do not stage another contributor's incomplete work.
- **Every new commit must update the README development progress section.** Use `python3 scripts/update_progress.py --note 'Concrete change and verification state'`. Change checkpoint status only when the stated acceptance condition is supported by evidence. Run `python3 scripts/update_progress.py --check` before committing.
- Keep `README.md`, `docs/progress.json` and the generated progress SVG consistent. The percentage counts engineering checkpoints; it is not phone compatibility coverage or a measure of total effort.
- **Archive real UI test screenshots under `screenshots/` and commit them with their manifest.** Record the tested source SHA, API/device, run URL, case, result and capture status. Use the evidence publishing workflow for CI captures, then bring its reviewed evidence commit into the main branch. The evidence commit must update README progress too.
- Run UI instrumentation only on dedicated synthetic test devices. Never commit real credentials, accounts or personal screens. Product secure-window behavior must remain enabled. An empty-screen screenshot exception belongs only to the test APK and must restore the original flags in `finally`.
- Record protected/missing captures and nonvisual tests honestly. Do not create mock images or present a screenshot as proof of installation, task completion or general compatibility. Pure JVM/build checks have no UI screenshot.
- Report the exact tested device/API/app combination. Simulator success is not physical-device, ROM, performance-budget or third-party application acceptance. Keep failed and unverified samples in the denominator.
- APK support does not imply a flashable image. ROM work requires an identified reference device, kernel/vendor support and recovery evidence; preserve standard Android application behavior.
