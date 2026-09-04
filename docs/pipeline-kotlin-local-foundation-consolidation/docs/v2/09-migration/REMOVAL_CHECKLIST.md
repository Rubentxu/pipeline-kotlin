# Removal checklist

Use this checklist before closing a migration milestone.

- [ ] no production imports of legacy type/path;
- [ ] no Gradle module dependency required only by legacy path;
- [ ] no documentation references it as current;
- [ ] characterization tests have been replaced by canonical behavior tests where appropriate;
- [ ] architecture fitness prevents reintroduction;
- [ ] deprecated adapters have an expiry or are deleted;
- [ ] migration note explains behavior changes;
- [ ] source/history remains available through Git tag/commit;
- [ ] full build/UAT is green after deletion.
