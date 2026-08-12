# Offline Papyrus test dependencies

These JARs are required only to compile/run the offline test harness. They are not packaged into the Papyrus plugin distribution.

Copy the dependency bundle into this directory, preserving its layout:

```text
third_party/papyrus-test-deps/
  junit-platform-console-standalone-1.11.4.jar
  driver/
    driver-sdk-<262-build>.jar
  starter/
    allure-java-commons-2.25.0.jar
    allure-model-2.25.0.jar
    ide-starter-driver-<same-262-build>.jar
    ide-starter-junit5-<same-262-build>.jar
    ide-starter-squashed-<same-262-build>.jar
    kaverit-jvm-2.10.0.jar
    kodein-di-jvm-7.26.1.jar
```

`driver-sdk`, `ide-starter-driver`, `ide-starter-junit5`, and `ide-starter-squashed` must use exactly the same SDK build and must stay on the same IntelliJ Platform branch as the resolved IDE. The current CLion target is `CL-262.9437.136`; the existing `262.8665.337` test SDK is allowed for the first migration gate with a warning because it is still branch 262. If that gate reports Driver/Starter binary or API incompatibility, update all four versioned JARs together.

The IDEA-specific `ide-starter-product-idea-ultimate-*.jar` is no longer required and is explicitly excluded from the test classpath if it is still present in an old local bundle. The build constructs `IdeInfo` from the resolved IDE metadata instead.

The build reads helper JARs only from this project-local directory. The installed CLion tree remains read-only. Test-dependency JARs are intentionally omitted from source handoff archives and from the plugin distribution.
