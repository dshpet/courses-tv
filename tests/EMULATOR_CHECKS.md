# Emulator checks

Manual checks on a Google TV emulator with your own account.

## Rules

- Use `assembleReadOnly`: account changes are logged as `Not sent (read-only build)`. Progress, My
  List, Remove and Mark checks need the release build and change the account.
- One key per `adb shell input keyevent` call. Screenshot before any Select in a menu.
- Type credentials from a script (`adb shell input text`, e.g. from the git-ignored `.env`); keep
  them out of logs and screenshots. Close the TV keyboard before moving between fields
  (`adb shell dumpsys input_method` shows `mInputShown=true` while it is open).
- Playing past 2:00 saves progress: use lectures already under way.

## Sign-in

- After `adb shell pm clear`: the welcome screen, Sign in focused → the website's dialog.
- Cookie banner: Down → Accept All, Right → Cookie Settings, Select → first switch, Down → Done.
- Email, Down, password, Down, Sign In → Home with Continue Watching. Back closes the dialog, then
  leaves.

## Home and search

- The first card is focused. From the second start, rows show at once (`am start -W`, the
  `Fully drawn` log line).
- A reload that puts a new first card in a focused row moves the focus to it (edit the progress in a
  debug build's `cache/api/`, then cold-start).
- Course cards show a progress bar or a check; the hero shows title, teacher, lectures and hours.
- Up from the first row → the menu; moving along it switches tabs. Back: content → menu → Home → out.
- Continue Watching: one card per course, at most 10, no finished courses. Select held → Play,
  Play from the beginning, Go to course, Remove (the second Select removes).
- Search: Down onto the field opens the keyboard; "roman history" → 8 courses; Back, Down → results.

## Course

- Play button: "Resume lecture N" with the time left, "Play lecture N", or "Watch again" once
  finished.
- Down from the buttons → that lecture; Up from the first lecture → Play.
- My List toggles (✓ / +) and persists.
- Select held on a lecture → Play, Play from the beginning, Mark as watched / not watched.
- Back from the player focuses the lecture reached; started from Play, the focus stays on Play.
- Guidebook: "Loading the guidebook… N%", Left/Right turn pages, Down scrolls then turns, Select
  zooms; reopens at the last page.

## Player

- Starts at the saved position. Select pauses; Left/Right seek 10 s (held: 30 s); the controls hide
  after 5 s.
- Down → Lectures (the course page at this lecture), Captions, Settings, Next lecture.
- Quality, speed and caption size apply at once and persist.
- Caption style: `adb shell settings put secure accessibility_captioning_preset 2` and
  `… accessibility_captioning_font_scale 1.5`; undo with `settings delete secure …`.
- In the last 20 s, "Next lecture in N s"; Select plays it.
- Still watching: on a finished course, end each lecture with
  `adb shell cmd media_session dispatch fast-forward` (15 s a press; not counted as the remote).
  Lecture 4 waits at 0:00 behind the prompt and `dumpsys power` shows no wake lock. Any remote key
  resets the count.
- Slow network: `adb emu network speed 1:1`, Right ×10 → "This lecture can't play" within about
  30 s; `emu network speed full`, Try again. If the launcher comes to the front
  (`dumpsys activity activities | grep topResumedActivity`), reopen with
  `am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n …`.

## System

- While playing, `dumpsys power` lists `SCREEN_BRIGHT_WAKE_LOCK` and `dumpsys media_session` shows
  the session; `cmd media_session dispatch pause|play` works. Both are gone after the player closes.
- Home during a lecture pauses and saves; reopening shows the player paused.
- Recreation: a lecture, Next lecture, Home, `adb shell am kill io.github.dshpet.coursestv`,
  reopen → the lecture reached at the same second, paused. Back, Back rebuilds the course and menu.
- Sign out → the welcome screen, still signed out after a relaunch.
- Offline: `adb shell cmd connectivity set-chain3-enabled true` and
  `… set-package-networking-enabled false io.github.dshpet.coursestv`, force-stop and reopen →
  "Can't connect" on Home and Search; My List says it couldn't be changed. Undo with
  `set-chain3-enabled false`.
