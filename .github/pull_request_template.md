## Summary

Describe what this release includes and why.

## Checklist: `development` → `master`

### Branch

- [ ] Base branch is `master` and compare branch is `development`
- [ ] `development` is up to date with `master`
- [ ] No unrelated or unfinished work is included

### Automated checks

- [ ] `./gradlew test` passes
- [ ] `./gradlew test -PrealGitTests=true` passes
- [ ] `./gradlew verifyPlugin` reports the plugin as compatible
- [ ] `./gradlew buildPlugin` creates the zip

### Manual checks in `./gradlew runIde`

Use a throwaway repository.

- [ ] Graph, sidebar and details panel load correctly
- [ ] Dialog buttons show the correct text
- [ ] Checkout, branch, merge and rebase work
- [ ] Interactive rebase, edit message and drop commit work
- [ ] Conflict resolve, continue, skip and abort work
- [ ] Push, pull and fetch work with a real remote
- [ ] Light and dark themes look correct

### Release

- [ ] `pluginVersion` in `gradle.properties` is updated
- [ ] Change notes in `plugin.xml` are updated
- [ ] README matches the behavior in this release
- [ ] No secrets, tokens or personal data are included

### After merging

- [ ] Merged with **Create a merge commit**
- [ ] Release tag is created on `master` and pushed
