# Milestone gates

Every milestone receipt must answer **yes** to all applicable questions.

## Universal gate

- Is there exactly one new canonical path for the concept changed?
- Is the prior path deleted or expiry-tagged?
- Are public behavior changes reflected in compatibility docs?
- Do all architecture fitness tests pass?
- Do targeted UAT and full regression suite pass?
- Are failure/cancellation paths tested, not just happy paths?
- Is the sample reference pipeline still runnable?
- Did the milestone reduce or keep flat the critical/high debt count?

## Performance-sensitive changes

- Was memory measured with realistic output/process scale?
- Are queues/buffers bounded?
- Are cancellation/resource cleanup measured?

## Security-sensitive changes

- Are missing/invalid credentials fail-closed?
- Is secret redaction tested before persistence?
- Are file permissions/lifetimes tested?

## Plugin/API changes

- Does an external plugin compile against the published API?
- Is binary/source compatibility policy clear?
- Can KSP metadata be regenerated deterministically?
- Are plugin API and runtime versions checked explicitly?
