# Pull Request

**Type of Change**
- [ ] Bug fix (non-breaking, fixes issue #_)
- [ ] Feature (non-breaking, adds API/abstraction)
- [ ] Breaking change (API change; requires consumer project updates)
- [ ] Documentation update

**Related Issue**
Closes #(issue)

## Description

Please include a summary of the changes and the motivation behind them:

- What problem does this solve for consumer projects?
- How was this tested?
- Are there any breaking changes to the public API?

## Testing

Describe how you tested your changes:

- [ ] Tested on emulator (AAOS 9, MT2712)
- [ ] Tested on physical device (firmware version: _)
- [ ] Unit tests added/updated for new public APIs
- [ ] Integration tests verify behavior with consumer projects

**Firmware Compatibility Testing (if applicable)**
- [ ] No firmware-specific changes (API-level only)
- [ ] Tested on firmware versions: SWI132, SWI133, SWI165 (list all tested)
- [ ] Property ID mappings verified for each firmware
- [ ] Binder compatibility confirmed

## Code Review Checklist

- [ ] Code follows project style and conventions
- [ ] Public API is well-documented (JavaDoc / KDoc)
- [ ] No hardcoded secrets, URLs, or credentials
- [ ] Comments explain complex vehicle access logic
- [ ] Breaking changes documented and explained
- [ ] README.md updated with new features
- [ ] No unnecessary dependencies added

**API Safety Considerations**
- [ ] All public methods validate input (no nulls, invalid property IDs)
- [ ] Documentation clarifies consumer responsibility (e.g., speed gate checks)
- [ ] Error cases handled gracefully (thrown exceptions documented)
- [ ] No vehicle writes performed without consumer app intent

**Stability Considerations**
- [ ] No crashes observed during testing
- [ ] No ANR (Application Not Responding) warnings
- [ ] Binder communication timeouts handled
- [ ] Memory usage reasonable (no unbounded caches without cleanup)

## Consumer Project Impact

If this is a breaking change:
- [ ] List affected consumer projects: EVProfile, EVABRPUploader, etc.
- [ ] Provide migration guide for dependent projects
- [ ] Update example code in documentation

## CI/CD Status

Ensure all checks pass:
- [ ] Tests pass locally (`./gradlew test`)
- [ ] No new lint errors (`./gradlew lint`)
- [ ] Library builds without warnings (`./gradlew build`)
- [ ] Security checks pass (gitleaks, mobsfscan, dependency-check)
- [ ] JavaDoc/KDoc generates without errors

## Claude-Assisted Description (Optional)

*If you used Claude AI to refine this PR description, API design, or commit messages, summarize how it was improved:*
- Original issue: _
- Claude suggestions applied: _
- Confidence in description clarity: high / medium / low

---

**Note:** All contributions are subject to [CONTRIBUTING.md](CONTRIBUTING.md). Please ensure your PR maintains backward compatibility or clearly documents breaking changes that consumer projects must address.
