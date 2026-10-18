# NextTask

A mobile client for [Nextcloud Tasks](https://github.com/nextcloud/tasks), with first-class nested subtasks.
Tasks are synced over CalDAV (VTODO); a subtask points at its parent with `RELATED-TO;RELTYPE=PARENT`.

## Layout

- `shared/` – Kotlin Multiplatform module (Android, JVM, iOS): iCalendar parser/writer, `Task` model, task tree and indent/outdent/reparent logic.
- `androidApp/` – Android app (Jetpack Compose).

## Development

Requires a JDK 17+ (not just a JRE) and, for the Android app, the Android SDK (`ANDROID_HOME`).

```sh
./gradlew :shared:jvmTest       # shared logic tests
./gradlew :androidApp:assembleDebug
```

The iOS targets only build on macOS.
