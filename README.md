# Courses TV

An unofficial Android TV app for a [The Great Courses Plus](https://plus.thegreatcourses.com/)
subscription, made for the Chromecast with Google TV, where the service has no TV app of its own. It
runs on other Google TV and Android TV devices (Android 14+) too.

> **Unofficial.** Not affiliated with, endorsed by or sponsored by The Teaching Company, LLC.
> "The Great Courses" and "The Great Courses Plus" are its trademarks, named here only to say what
> the app works with. You need your own subscription: the app plays only what your account can play,
> streams it like the website does and downloads no lectures.

Every screen is native and reads the service's JSON API with your own session. Signing in happens in
the website's own dialog: the app never sees the password and stores no token of its own. A screen
opens at once with what the TV kept from last time and is read again behind it. Lectures play in a
native player that resumes where you left off and saves progress back to your account, so the website
and the phone app continue from the same place.

## Screens

- **Home:** a hero for the focused title over its picture (with the teacher, lecture count and
  length), Continue Watching, Recently Watched, My List and the service's rows (New Releases, Most
  Popular, subjects, …). Every course card shows how much of the course you have watched: a bar, or a
  check once its last lecture is finished.
- **Search** (as you type, over the whole catalog), **Subjects** and learning paths, **My List**,
  **Account** (subscription status, sign out, the app's version).
- **Course:** how much of it you have watched ("3.6 of 18.2 hours watched"), Resume or Play (the next
  lecture), add to or remove from My List, the guidebook, the professor, the lectures with their
  progress, then the professor's other courses; back from the player, the list shows the lecture
  playback reached.
- **Guidebook:** the course's PDF a page at a time, opening at the page last read.
- **Professor:** their photo, degree and biography (Up from the courses to scroll it), and all their
  courses.
- **Player:** resume, progress saved every 30 s and on pause or exit, captions in the TV's caption
  style, quality, speed and caption size (remembered), Lectures (back to the course at this lecture),
  up next, the next lecture starting by itself.

## Progress and Continue Watching

The rules follow common TV-app practice:

- A lecture counts as started after 2 minutes. A shorter play saves nothing, so opening the wrong
  lecture changes nothing.
- It counts as finished at 90% (the summary and credits). Leaving there saves it as watched to the
  end. A finished lecture played again stays finished.
- Continue Watching has one card per course: the lecture under way past the furthest finished one,
  or else the lecture after it. Replaying an earlier lecture doesn't move the card back.
- Finished courses drop off Continue Watching. It shows only courses watched in the last 16 weeks, at
  most 10.
- Hold Select on a card to play it (or play it from the beginning), go to the course, or remove it
  from the row. Removing uses the website's own Remove, so the course leaves Continue Watching there
  too; its progress stays. There is no way to undo it, so Remove asks for a second Select.
- Hold Select on a lecture in a course to play it from the beginning, or to mark it as watched or not
  watched: for lectures watched elsewhere that the service never counted.
- Recently Watched lists every course you have really watched, most recent first; Select opens the
  course.
- After three lectures in a row play with no button pressed (an hour or more), the next one waits at
  its start: "Are you still watching?" So falling asleep doesn't mark the rest of the course as
  watched, and the TV can go to sleep.

## Remote control

| Where | Keys | Action |
| --- | --- | --- |
| Rows and grids | D-pad, Select | Move between cards, open a course; a Continue Watching card plays |
| Continue Watching | Select held | Play, Play from the beginning, Go to course, Remove from Continue Watching |
| Course | Down from Play | To the lecture Play would play |
| Course | Select held on a lecture | Play or Resume, Play from the beginning, Mark as watched / not watched |
| Guidebook | Left/Right; Up/Down; Select | Turn pages; scroll (Down at a page's end: the next page); larger or smaller |
| Menu | Up from the content; Left/Right | Search, Home, Subjects, My List, Account (moving switches tabs) |
| Anywhere in the menu screens | Back | To the menu, then Home, then out of the app |
| Search | Down onto the field; Back; Down | The TV keyboard with its voice button opens; Back closes it (Select opens it again); Down to the results |
| Player | Select; Left/Right (held: faster) | Play/pause; seek 10 s (30 s) |
| Player | Down, Left/Right, Select | Lectures (the course at this lecture), Captions on/off, Settings (quality, speed, caption size), Next lecture |
| Are you still watching? | Select (or Play); Back | Keep watching; leave to the course |
| Player settings | Up/Down, Select; Left or Back | Choose; close |
| Player | Back | Close the settings, hide the controls, then leave (progress is saved) |
| Player | Media keys, Google Assistant | Through the media session |
| Sign-in (the website's dialog) | Up, Down; Select on a field; Back | Move between fields; open the keyboard; close the keyboard, then the dialog |

## How it works

| File | Role |
| --- | --- |
| [Api.kt](app/src/main/java/io/github/dshpet/coursestv/Api.kt), [App.kt](app/src/main/java/io/github/dshpet/coursestv/App.kt) | Service hosts; HTTPS JSON calls with the website session's `global_auth_token` cookie as the Bearer token; the one HTTP client, image loader and catalog |
| [Catalog.kt](app/src/main/java/io/github/dshpet/coursestv/Catalog.kt), [Models.kt](app/src/main/java/io/github/dshpet/coursestv/Models.kt) | API routes for each screen, asking only for the fields used; answers kept on the TV (never the account's), so a screen shows the last ones first; JSON → rows, courses, lectures, progress |
| [MainActivity.kt](app/src/main/java/io/github/dshpet/coursestv/MainActivity.kt) | The launcher: menu and the Home, Search, Subjects, My List and Account tabs |
| [CourseActivity.kt](app/src/main/java/io/github/dshpet/coursestv/CourseActivity.kt), [SubjectActivity.kt](app/src/main/java/io/github/dshpet/coursestv/SubjectActivity.kt), [ProfessorActivity.kt](app/src/main/java/io/github/dshpet/coursestv/ProfessorActivity.kt) | A course with its lectures; a subject's rows; a professor and their courses |
| [PlayerActivity.kt](app/src/main/java/io/github/dshpet/coursestv/PlayerActivity.kt), [ProgressSaver.kt](app/src/main/java/io/github/dshpet/coursestv/ProgressSaver.kt) | Media3 player, TV controls, settings, media session; when and what progress is saved |
| [GuidebookActivity.kt](app/src/main/java/io/github/dshpet/coursestv/GuidebookActivity.kt) | The guidebook PDF, cached on the TV (the last 5 kept) and drawn by Android's `PdfRenderer` |
| [Components.kt](app/src/main/java/io/github/dshpet/coursestv/Components.kt), [Theme.kt](app/src/main/java/io/github/dshpet/coursestv/Theme.kt), [TvActivity.kt](app/src/main/java/io/github/dshpet/coursestv/TvActivity.kt) | Cards, rows, hero, messages and menus; colors, fonts and sizes; the shared screen base |
| [SignInActivity.kt](app/src/main/java/io/github/dshpet/coursestv/SignInActivity.kt), [site.js](app/src/main/assets/site.js) | The website with its sign-in dialog opened, usable with the remote (the page script); closes once signed in. Trackers are blocked |

Built with Jetpack Compose for TV, Media3, Coil and OkHttp. Fonts: DM Sans and Figtree (SIL Open
Font License, [licenses](app/src/main/assets/licenses/)). The launcher icon and TV banner are vector
drawables generated by [art/icons.py](art/icons.py).

## Build and install

There are no prebuilt APKs: build the app and install it on the TV over the network.

1. **Build.** Install [Android Studio](https://developer.android.com/studio), which brings a JDK and
   the Android SDK, and open this folder in it once so it installs the SDK platform the project asks
   for (37). Then, in a terminal in this folder:

   ```sh
   ./gradlew assembleRelease      # Windows: gradlew.bat assembleRelease
   ```

   If it can't find Java, set `JAVA_HOME` to Android Studio's bundled JDK (the `jbr` folder in its
   install directory). Without Android Studio: JDK 17+ and the Android SDK with platform 37
   (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

2. **Connect the TV.** On the TV, open Settings → System → About and press Select on *Android TV OS
   build* seven times. Then in Settings → System → Developer options, turn on *Wireless debugging*
   and choose *Pair device with pairing code*. On the computer, with `adb` from the SDK's
   `platform-tools`:

   ```sh
   adb pair <ip>:<port>       # the pairing address and code the TV shows
   adb connect <ip>:<port>    # the address on the Wireless debugging screen
   ```

3. **Install.**

   ```sh
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell cmd package compile -m speed -f io.github.dshpet.coursestv
   ```

   Courses TV then appears among the TV's apps. The second line compiles it ahead of time; otherwise
   the TV does that by itself a day or two later, and the app runs slower until then. Install the
   release build: Compose is slow unoptimized.

Release builds are signed with `app/sideload.keystore` when it exists (git-ignored), otherwise with
the local debug key. Keep using one key: a build signed with another can't update the installed app.
`assembleReadOnly` builds the release app that changes nothing on the account (debug builds send
nothing either), for testing.

## Test

```sh
./gradlew testDebugUnitTest
node --test tests/
```

The unit tests map invented catalog, progress and My List records in the API's shape to the app's
models and screens, and check the answers kept on the TV. The second runs `site.js` in headless
Chromium (Node 22+, `/usr/bin/chromium` or `CHROMIUM=`) against minimal stand-ins for the website's
dialogs. Remote, player and sign-in behavior on a TV is checked by hand:
[tests/EMULATOR_CHECKS.md](tests/EMULATOR_CHECKS.md).

## Limits

- The API routes and stream links are undocumented; a change on the service's side can break them.
- On a Chromecast with Google TV, Home's rows show about 1.1 s after a cold start once the app is
  compiled ahead of time.
- Remove from Continue Watching can't be undone; the website has no known way to.
- Search covers courses by title and description, not single lectures.
- No pictures while seeking.
- Right after the app opens, a screen can show what was kept from last time, such as a course watched
  since on the phone not yet first in Continue Watching; it is read again within a second or two.
- The service's personalized recommendations and banners are not shown.

## License

[MIT](LICENSE).
